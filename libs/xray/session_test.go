package xraybridge

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
)

// startTestServer runs a local Xray server for the given inbound/outbound pair
// and returns its port. It is the in-process equivalent of a remote peer.
func startTestServer(t *testing.T, cfg map[string]any) int {
	t.Helper()
	port := freePort(t)
	cfg["log"] = map[string]any{"loglevel": "none"}
	cfg["inbounds"] = []any{mergeMap(cfg["inbounds"], map[string]any{"listen": "127.0.0.1", "port": port})}
	cfg["outbounds"] = []any{cfg["outbounds"]}
	raw, err := json.Marshal(cfg)
	if err != nil {
		t.Fatalf("marshal server config: %v", err)
	}
	inst, err := core.StartInstance("json", raw)
	if err != nil {
		t.Fatalf("start server: %v", err)
	}
	t.Cleanup(func() { _ = inst.Close() })
	return port
}

func mergeMap(v any, extra map[string]any) map[string]any {
	m, _ := v.(map[string]any)
	if m == nil {
		m = map[string]any{}
	}
	for k, val := range extra {
		m[k] = val
	}
	return m
}

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("free port: %v", err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

// echoServer accepts connections and echoes everything back.
func echoServer(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("echo listen: %v", err)
	}
	t.Cleanup(func() { _ = l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}(c)
		}
	}()
	return l.Addr().(*net.TCPAddr).Port
}

func socksRoundTrip(t *testing.T, sess *Session, targetPort int) string {
	t.Helper()
	addr := fmt.Sprintf("127.0.0.1:%d", sess.Port())
	deadline := time.Now().Add(10 * time.Second)

	var conn net.Conn
	var err error
	for time.Now().Before(deadline) {
		conn, err = net.DialTimeout("tcp", addr, time.Second)
		if err == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("dial socks: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(deadline)

	if _, err := conn.Write([]byte{0x05, 0x02, 0x00, 0x02}); err != nil {
		t.Fatalf("greeting: %v", err)
	}
	resp := make([]byte, 2)
	if _, err := io.ReadFull(conn, resp); err != nil {
		t.Fatalf("greeting resp: %v", err)
	}
	if resp[0] != 0x05 || resp[1] != 0x02 {
		t.Fatalf("server must require user/pass auth: %v", resp)
	}
	u, p := sess.Username(), sess.Password()
	auth := []byte{0x01, byte(len(u))}
	auth = append(auth, u...)
	auth = append(auth, byte(len(p)))
	auth = append(auth, p...)
	if _, err := conn.Write(auth); err != nil {
		t.Fatalf("auth: %v", err)
	}
	if _, err := io.ReadFull(conn, resp); err != nil {
		t.Fatalf("auth resp: %v", err)
	}
	if resp[1] != 0x00 {
		t.Fatalf("auth rejected: %v", resp)
	}

	req := []byte{0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, byte(targetPort >> 8), byte(targetPort)}
	if _, err := conn.Write(req); err != nil {
		t.Fatalf("connect: %v", err)
	}
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil {
		t.Fatalf("connect resp: %v", err)
	}
	t.Logf("socks connect head=%v", head)
	if head[1] != 0x00 {
		t.Fatalf("socks connect failed: %v", head)
	}
	// drain bound address
	switch head[3] {
	case 0x01:
		_, _ = io.ReadFull(conn, make([]byte, 4+2))
	case 0x04:
		_, _ = io.ReadFull(conn, make([]byte, 16+2))
	default:
		n := make([]byte, 1)
		_, _ = io.ReadFull(conn, n)
		_, _ = io.ReadFull(conn, make([]byte, int(n[0])+2))
	}

	payload := []byte("hello-through-xray")
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("write: %v", err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("echo mismatch: %q", got)
	}
	return string(got)
}

func TestSessionShadowsocks(t *testing.T) {
	echo := echoServer(t)
	serverPort := startTestServer(t, map[string]any{
		"inbounds": map[string]any{
			"protocol": "shadowsocks",
			"settings": map[string]any{"method": "aes-256-gcm", "password": "server-pass"},
		},
		"outbounds": freedomOutbound(),
	})
	_ = serverPort

	link := fmt.Sprintf("ss://%s@127.0.0.1:%d",
		base64.RawURLEncoding.EncodeToString([]byte("aes-256-gcm:server-pass")), serverPort)
	sess, err := Start(link)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	defer sess.Close()
	socksRoundTrip(t, sess, echo)
}

func TestSessionVMessWS(t *testing.T) {
	echo := echoServer(t)
	serverPort := startTestServer(t, map[string]any{
		"inbounds": map[string]any{
			"protocol": "vmess",
			"settings": map[string]any{"clients": []any{map[string]any{
				"id": "b831381d-6324-4d53-ad4f-8cda48b30811", "alterId": 0,
			}}},
			"streamSettings": map[string]any{
				"network":    "ws",
				"wsSettings": map[string]any{"path": "/ray"},
			},
		},
		"outbounds": freedomOutbound(),
	})

	vm := map[string]any{
		"v": "2", "add": "127.0.0.1", "port": serverPort,
		"id": "b831381d-6324-4d53-ad4f-8cda48b30811", "aid": 0, "scy": "auto",
		"net": "ws", "path": "/ray", "host": "", "tls": "",
	}
	raw, _ := json.Marshal(vm)
	sess, err := Start("vmess://" + base64.StdEncoding.EncodeToString(raw))
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	defer sess.Close()
	socksRoundTrip(t, sess, echo)
}

func TestSessionRequiresAuth(t *testing.T) {
	echo := echoServer(t)
	serverPort := startTestServer(t, map[string]any{
		"inbounds": map[string]any{
			"protocol": "shadowsocks",
			"settings": map[string]any{"method": "aes-256-gcm", "password": "server-pass"},
		},
		"outbounds": freedomOutbound(),
	})
	link := fmt.Sprintf("ss://%s@127.0.0.1:%d",
		base64.RawURLEncoding.EncodeToString([]byte("aes-256-gcm:server-pass")), serverPort)
	sess, err := Start(link)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	defer sess.Close()

	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", sess.Port()), 5*time.Second)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	// offer no-auth only; server must not accept it
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatalf("greeting: %v", err)
	}
	resp := make([]byte, 2)
	if _, err := io.ReadFull(conn, resp); err != nil {
		t.Fatalf("resp: %v", err)
	}
	if resp[1] != 0xFF {
		t.Fatalf("expected no acceptable methods, got %v", resp)
	}
	_ = echo
}

func TestTwoSessionsAndClose(t *testing.T) {
	echo := echoServer(t)
	serverPort := startTestServer(t, map[string]any{
		"inbounds": map[string]any{
			"protocol": "shadowsocks",
			"settings": map[string]any{"method": "aes-256-gcm", "password": "server-pass"},
		},
		"outbounds": freedomOutbound(),
	})
	link := fmt.Sprintf("ss://%s@127.0.0.1:%d",
		base64.RawURLEncoding.EncodeToString([]byte("aes-256-gcm:server-pass")), serverPort)

	a, err := Start(link)
	if err != nil {
		t.Fatalf("start a: %v", err)
	}
	b, err := Start(link)
	if err != nil {
		_ = a.Close()
		t.Fatalf("start b: %v", err)
	}
	if a.Port() == b.Port() {
		t.Fatalf("sessions must use distinct ports")
	}
	if a.Username() == b.Username() {
		t.Fatalf("sessions must use distinct credentials")
	}
	socksRoundTrip(t, a, echo)
	socksRoundTrip(t, b, echo)

	if err := a.Close(); err != nil {
		t.Fatalf("close a: %v", err)
	}
	if err := a.Close(); err != nil {
		t.Fatalf("close a twice must be a no-op: %v", err)
	}
	if a.Port() != 0 {
		t.Fatalf("closed session must report port 0")
	}
	if _, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", b.Port()), 2*time.Second); err != nil {
		t.Fatalf("sibling session must stay up: %v", err)
	}
	socksRoundTrip(t, b, echo)
	_ = b.Close()
}

func TestVersion(t *testing.T) {
	v := Version()
	if !strings.HasPrefix(v, "2") && !strings.HasPrefix(v, "1") {
		t.Fatalf("unexpected version: %s", v)
	}
}

// freedomOutbound is a plain direct outbound that may reach loopback targets.
// Core blocks private addresses by default for vmess/shadowsocks inbounds;
// the test peer must allow 127.0.0.1 explicitly.
func freedomOutbound() map[string]any {
	return map[string]any{
		"protocol": "freedom",
		"settings": map[string]any{
			"finalRules": []any{map[string]any{"action": "allow", "network": "tcp", "ip": []any{"127.0.0.1/32"}}},
		},
	}
}

func TestStartFailureReturnsStaticErrorWithoutLeak(t *testing.T) {
	// Occupy a fixed loopback port, then force the core to bind that exact port:
	// the start must fail, return a static error, and leave nothing listening.
	link := fmt.Sprintf("ss://%s@127.0.0.1:%d",
		base64.RawURLEncoding.EncodeToString([]byte("aes-256-gcm:server-pass")), 8388)
	p, err := Parse(link)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}

	blocker, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("blocker: %v", err)
	}
	defer blocker.Close()
	busyPort := blocker.Addr().(*net.TCPAddr).Port

	cfg, err := buildConfig(p, busyPort, "user-secret", "pass-secret")
	if err != nil {
		t.Fatalf("config: %v", err)
	}
	_, err = startWithConfig(cfg)
	if err == nil {
		t.Fatal("expected start failure on busy port")
	}
	msg := err.Error()
	for _, leak := range []string{"server-pass", "user-secret", "pass-secret", "127.0.0.1", "aes-256-gcm"} {
		if strings.Contains(msg, leak) {
			t.Fatalf("start error leaks %q: %s", leak, msg)
		}
	}
	if msg != errStartFailed.Error() {
		t.Fatalf("start error must be the static category error, got: %s", msg)
	}

	// Releasing the blocker must leave the port bindable: a half-started core
	// would still hold it or leave its own listener behind.
	if err := blocker.Close(); err != nil {
		t.Fatalf("blocker close: %v", err)
	}
	rebound, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", busyPort))
	if err != nil {
		t.Fatalf("port still held after failed start: %v", err)
	}
	_ = rebound.Close()
}

func TestClosedSessionPortIsUnreachable(t *testing.T) {
	echo := echoServer(t)
	serverPort := startTestServer(t, map[string]any{
		"inbounds": map[string]any{
			"protocol": "shadowsocks",
			"settings": map[string]any{"method": "aes-256-gcm", "password": "server-pass"},
		},
		"outbounds": freedomOutbound(),
	})
	link := fmt.Sprintf("ss://%s@127.0.0.1:%d",
		base64.RawURLEncoding.EncodeToString([]byte("aes-256-gcm:server-pass")), serverPort)
	sess, err := Start(link)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	port := sess.Port()
	socksRoundTrip(t, sess, echo)

	if err := sess.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	if _, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 500*time.Millisecond); err == nil {
		t.Fatal("closed session port must not accept connections")
	}
	if sess.Port() != 0 {
		t.Fatalf("port must be 0 after close")
	}
}

// The chacha20-ietf-poly1305 alias must work end to end, not just parse.
func TestSessionShadowsocksChachaAlias(t *testing.T) {
	echo := echoServer(t)
	serverPort := startTestServer(t, map[string]any{
		"inbounds": map[string]any{
			"protocol": "shadowsocks",
			"settings": map[string]any{"method": "chacha20-ietf-poly1305", "password": "server-pass"},
		},
		"outbounds": freedomOutbound(),
	})
	link := fmt.Sprintf("ss://%s@127.0.0.1:%d",
		base64.RawURLEncoding.EncodeToString([]byte("chacha20-ietf-poly1305:server-pass")), serverPort)
	sess, err := Start(link)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	defer sess.Close()
	socksRoundTrip(t, sess, echo)
}
