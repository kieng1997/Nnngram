package xraybridge

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"net/url"
	"strconv"
	"strings"
)

const (
	maxLinkLen = 16 << 10
	maxPort    = 65535
)

var (
	errLinkTooLong  = errors.New("xraybridge: link is too long")
	errUnsupported  = errors.New("xraybridge: unsupported link")
	errInvalidLink  = errors.New("xraybridge: invalid link")
	errPlugin       = errors.New("xraybridge: shadowsocks plugin is not supported")
	errUOT          = errors.New("xraybridge: shadowsocks UoT is not supported")
	errBadPort      = errors.New("xraybridge: port out of range")
	errBadHost      = errors.New("xraybridge: invalid host")
	errBadUUID      = errors.New("xraybridge: invalid vmess uuid")
	errBadAID       = errors.New("xraybridge: vmess alterId must be 0")
	errBadTransport = errors.New("xraybridge: unsupported vmess transport")
	errBadHeader    = errors.New("xraybridge: vmess obfuscation header is not supported")
	errBadSecurity  = errors.New("xraybridge: unsupported vmess security")
	errInsecure     = errors.New("xraybridge: certificate verification cannot be disabled")
	errReality      = errors.New("xraybridge: reality/xtls is not supported")
	errBadMethod    = errors.New("xraybridge: unsupported shadowsocks method")
	errBadPassword  = errors.New("xraybridge: invalid shadowsocks password")
)

// Profile is a parsed share link. Every accessor is read-only.
type Profile struct {
	protocol  string // "shadowsocks" or "vmess"
	address   string
	port      int
	method    string // shadowsocks cipher, empty for vmess
	password  string
	uuid      string
	security  string // vmess scy
	transport string // "tcp" or "ws"
	tls       bool
	sni       string
	host      string // ws Host header
	path      string // ws path
	alpn      []string
}

func (p *Profile) Protocol() string  { return p.protocol }
func (p *Profile) Address() string   { return p.address }
func (p *Profile) Port() int         { return p.port }
func (p *Profile) Transport() string { return p.transport }
func (p *Profile) TLS() bool         { return p.tls }

// Parse accepts ss:// (SIP002 and legacy base64) and vmess:// (base64 JSON) links.
// Error values never contain the input link, credentials or UUID.
func Parse(link string) (*Profile, error) {
	if len(link) > maxLinkLen {
		return nil, errLinkTooLong
	}
	link = strings.TrimSpace(link)
	switch {
	case strings.HasPrefix(link, "ss://"):
		return parseSS(link)
	case strings.HasPrefix(link, "vmess://"):
		return parseVMess(link)
	default:
		return nil, errUnsupported
	}
}

func parseSS(link string) (*Profile, error) {
	body := link[len("ss://"):]
	body = dropFragment(body)
	body, query := splitQuery(body)

	q, err := url.ParseQuery(query)
	if err != nil {
		return nil, errInvalidLink
	}
	if v := q.Get("plugin"); v != "" {
		return nil, errPlugin
	}
	if v := q.Get("uot"); v != "" && v != "0" {
		return nil, errUOT
	}

	// Legacy: the whole body is base64 of method:password@host:port.
	if !strings.Contains(body, "@") {
		decoded, err := decodeBase64(body)
		if err != nil {
			return nil, errInvalidLink
		}
		body = string(decoded)
		if i := strings.IndexByte(body, '#'); i >= 0 {
			body = body[:i]
		}
	}

	at := strings.LastIndexByte(body, '@')
	if at <= 0 {
		return nil, errInvalidLink
	}
	userInfo, hostPort := body[:at], body[at+1:]
	// SIP002 allows a single trailing root path; anything else is unsupported.
	if i := strings.IndexByte(hostPort, '/'); i >= 0 {
		if hostPort[i:] != "/" {
			return nil, errUnsupported
		}
		hostPort = hostPort[:i]
	}

	method, password, err := splitUserInfo(userInfo)
	if err != nil {
		return nil, err
	}

	host, port, err := splitHostPort(hostPort)
	if err != nil {
		return nil, err
	}

	method = normalizeSSMethod(strings.ToLower(method))
	if method == "" {
		return nil, errBadMethod
	}
	if password == "" {
		return nil, errBadPassword
	}
	if strings.HasPrefix(method, "2022-") && !validSS2022Key(method, password) {
		return nil, errBadPassword
	}

	return &Profile{
		protocol:  "shadowsocks",
		address:   host,
		port:      port,
		method:    method,
		password:  password,
		security:  "auto",
		transport: "tcp",
	}, nil
}

// splitUserInfo returns method and password. SIP002 encodes "method:password"
// as base64url; plain percent-encoded userinfo is also accepted.
func splitUserInfo(s string) (method, password string, err error) {
	if decoded, decErr := decodeBase64(s); decErr == nil {
		if i := strings.IndexByte(string(decoded), ':'); i > 0 {
			return string(decoded[:i]), string(decoded[i+1:]), nil
		}
	}
	if decoded, decErr := url.PathUnescape(s); decErr == nil {
		s = decoded
	}
	i := strings.IndexByte(s, ':')
	if i <= 0 {
		return "", "", errInvalidLink
	}
	return s[:i], s[i+1:], nil
}

// dropFragment strips the #tag part; it carries only a display name.
func dropFragment(s string) string {
	if i := strings.IndexByte(s, '#'); i >= 0 {
		return s[:i]
	}
	return s
}

func splitQuery(s string) (body, query string) {
	if i := strings.IndexByte(s, '?'); i >= 0 {
		return s[:i], s[i+1:]
	}
	return s, ""
}

func splitHostPort(s string) (host string, port int, err error) {
	host, portStr, err := net.SplitHostPort(s)
	if err != nil {
		return "", 0, errInvalidLink
	}
	port, convErr := strconv.Atoi(portStr)
	if convErr != nil || port < 1 || port > maxPort {
		return "", 0, errBadPort
	}
	if !validHost(host) {
		return "", 0, errBadHost
	}
	return host, port, nil
}

func validHost(host string) bool {
	if host == "" || len(host) > 255 {
		return false
	}
	for _, r := range host {
		if r <= 0x20 || r == 0x7f || r == '/' || r == '@' || r == '#' || r == '?' {
			return false
		}
	}
	return true
}

// normalizeSSMethod maps the cipher names the pinned core accepts to their
// canonical form, or returns "" when the method is unsupported. Stream ciphers
// and "none" are deliberately absent.
func normalizeSSMethod(m string) string {
	switch m {
	case "aes-128-gcm":
		return m
	case "aes-256-gcm":
		return m
	case "chacha20-poly1305", "chacha20-ietf-poly1305":
		return "chacha20-poly1305"
	case "xchacha20-poly1305", "xchacha20-ietf-poly1305":
		return "xchacha20-poly1305"
	case "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305":
		return m
	}
	return ""
}

// validSS2022Key checks the base64 pre-shared key length for 2022 methods.
func validSS2022Key(method, password string) bool {
	raw, err := decodeBase64(password)
	if err != nil {
		return false
	}
	want := 32
	if method == "2022-blake3-aes-128-gcm" {
		want = 16
	}
	return len(raw) == want
}

type vmessLink struct {
	Version       string `json:"v"`
	Address       string `json:"add"`
	Port          any    `json:"port"`
	ID            string `json:"id"`
	AlterID       any    `json:"aid"`
	Security      string `json:"scy"`
	Network       string `json:"net"`
	HeaderType    string `json:"type"`
	Host          string `json:"host"`
	Path          string `json:"path"`
	TLS           string `json:"tls"`
	SNI           string `json:"sni"`
	ALPN          string `json:"alpn"`
	Fingerprint   string `json:"fp"`
	AllowInsecure any    `json:"allowInsecure"`
}

func parseVMess(link string) (*Profile, error) {
	raw, err := decodeBase64(link[len("vmess://"):])
	if err != nil {
		return nil, errInvalidLink
	}
	if len(raw) > maxLinkLen {
		return nil, errLinkTooLong
	}
	var v vmessLink
	if err := json.Unmarshal(raw, &v); err != nil {
		return nil, errInvalidLink
	}

	if v.Version != "" && v.Version != "2" {
		return nil, errUnsupported
	}
	if !validHost(v.Address) {
		return nil, errBadHost
	}
	port, err := toInt(v.Port)
	if err != nil || port < 1 || port > maxPort {
		return nil, errBadPort
	}
	if !aidIsAbsent(v.AlterID) {
		aid, err := toInt(v.AlterID)
		if err != nil || aid != 0 {
			return nil, errBadAID
		}
	}
	if !validUUID(strings.ToLower(v.ID)) {
		return nil, errBadUUID
	}

	netw := strings.ToLower(strings.TrimSpace(v.Network))
	switch netw {
	case "", "tcp":
		netw = "tcp"
	case "ws":
		netw = "ws"
	default:
		return nil, errBadTransport
	}

	header := strings.ToLower(strings.TrimSpace(v.HeaderType))
	if header != "" && header != "none" {
		return nil, errBadHeader
	}

	tlsMode := strings.ToLower(strings.TrimSpace(v.TLS))
	switch tlsMode {
	case "", "none":
		tlsMode = ""
	case "tls":
		tlsMode = "tls"
	default:
		return nil, errReality
	}
	if v.Fingerprint != "" {
		return nil, errReality
	}
	insecure, err := toBool(v.AllowInsecure)
	if err != nil {
		return nil, errInsecure
	}
	if insecure {
		return nil, errInsecure
	}

	security := strings.ToLower(strings.TrimSpace(v.Security))
	switch security {
	case "":
		security = "auto"
	case "auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero":
	default:
		return nil, errBadSecurity
	}

	p := &Profile{
		protocol:  "vmess",
		address:   v.Address,
		port:      port,
		uuid:      strings.ToLower(v.ID),
		security:  security,
		transport: netw,
		tls:       tlsMode == "tls",
		sni:       strings.TrimSpace(v.SNI),
		host:      strings.TrimSpace(v.Host),
		path:      v.Path,
	}
	if p.path == "" {
		p.path = "/"
	}
	if v.ALPN != "" {
		for _, a := range strings.Split(v.ALPN, ",") {
			if a = strings.TrimSpace(a); a != "" {
				p.alpn = append(p.alpn, a)
			}
		}
	}
	if p.sni == "" && p.tls {
		p.sni = p.host
	}
	return p, nil
}

// aidIsAbsent reports whether alterId was omitted or left empty. AEAD-only
// VMess has no meaningful alterId; an explicit non-zero value is still rejected.
func aidIsAbsent(v any) bool {
	if v == nil {
		return true
	}
	s, ok := v.(string)
	return ok && strings.TrimSpace(s) == ""
}

func toInt(v any) (int, error) {
	switch t := v.(type) {
	case nil:
		return 0, errInvalidLink
	case float64:
		if t != float64(int(t)) {
			return 0, errInvalidLink
		}
		return int(t), nil
	case string:
		n, err := strconv.Atoi(strings.TrimSpace(t))
		if err != nil {
			return 0, errInvalidLink
		}
		return n, nil
	}
	return 0, errInvalidLink
}

func toBool(v any) (bool, error) {
	switch t := v.(type) {
	case nil:
		return false, nil
	case bool:
		return t, nil
	case float64:
		return t != 0, nil
	case string:
		switch strings.ToLower(strings.TrimSpace(t)) {
		case "", "0", "false", "no":
			return false, nil
		case "1", "true", "yes":
			return true, nil
		}
	}
	return false, errInvalidLink
}

func validUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, r := range s {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if r != '-' {
				return false
			}
			continue
		}
		if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f') {
			return false
		}
	}
	return true
}

// decodeBase64 accepts standard and URL encodings, padded or raw.
func decodeBase64(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	encs := []*base64.Encoding{
		base64.StdEncoding, base64.RawStdEncoding,
		base64.URLEncoding, base64.RawURLEncoding,
	}
	for _, enc := range encs {
		if b, err := enc.DecodeString(s); err == nil {
			return b, nil
		}
	}
	return nil, errInvalidLink
}
