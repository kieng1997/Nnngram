package xraybridge

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

const (
	ssAEADLink = "ss://YWVzLTI1Ni1nY206c2VjcmV0LXBhc3M@example.com:8388#node"
	ssPlain    = "ss://aes-256-gcm:p%40ss%3Aword@example.com:8388"
	ssLegacy   = "ss://" + "YWVzLTEyOC1nY206cGFzc3dvcmQxMjM@1.2.3.4:8389"
	ss2022Key  = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
	ss2022Link = "ss://2022-blake3-aes-256-gcm:" + ss2022Key + "@example.com:8388"
	vmessTpl   = `{"v":"2","ps":"n","add":"example.com","port":"443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ray","tls":"tls","sni":"sni.example.com","alpn":"h2,http/1.1"}`
)

func makeVmessLink(mutate func(map[string]any)) string {
	var m map[string]any
	if err := json.Unmarshal([]byte(vmessTpl), &m); err != nil {
		panic(err)
	}
	if mutate != nil {
		mutate(m)
	}
	raw, _ := json.Marshal(m)
	return "vmess://" + base64.StdEncoding.EncodeToString(raw)
}

func TestParseSIP002(t *testing.T) {
	p, err := Parse(ssAEADLink)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if p.Protocol() != "shadowsocks" || p.Address() != "example.com" || p.Port() != 8388 {
		t.Fatalf("unexpected profile: %s %s %d", p.Protocol(), p.Address(), p.Port())
	}
	if p.method != "aes-256-gcm" || p.password != "secret-pass" {
		t.Fatalf("unexpected credentials: %q %q", p.method, p.password)
	}
}

func TestParseSIP002EscapedPassword(t *testing.T) {
	p, err := Parse(ssPlain)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if p.password != "p@ss:word" {
		t.Fatalf("escaped password lost: %q", p.password)
	}
}

func TestParseLegacyBase64(t *testing.T) {
	p, err := Parse(ssLegacy)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if p.method != "aes-128-gcm" || p.password != "password123" || p.Address() != "1.2.3.4" || p.Port() != 8389 {
		t.Fatalf("legacy parse mismatch: %+v", p)
	}
}

func TestParseIPv6(t *testing.T) {
	p, err := Parse("ss://YWVzLTI1Ni1nY206cGFzcw@[2001:db8::1]:8388")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if p.Address() != "2001:db8::1" || p.Port() != 8388 {
		t.Fatalf("ipv6 mismatch: %q %d", p.Address(), p.Port())
	}
}

func TestParseSS2022(t *testing.T) {
	p, err := Parse(ss2022Link)
	if err != nil {
		t.Fatalf("2022 parse: %v", err)
	}
	if p.method != "2022-blake3-aes-256-gcm" {
		t.Fatalf("method mismatch: %s", p.method)
	}
}

func TestParseRejects(t *testing.T) {
	cases := map[string]string{
		"empty":            "",
		"other scheme":     "http://example.com",
		"ss no host":       "ss://YWVzLTI1Ni1nY206cGFzcw@:8388",
		"ss port 0":        "ss://YWVzLTI1Ni1nY206cGFzcw@example.com:0",
		"ss port high":     "ss://YWVzLTI1Ni1nY206cGFzcw@example.com:70000",
		"ss none cipher":   "ss://bm9uZTpwYXNz@example.com:8388",
		"ss rc4":           "ss://cmM0LW1kNTpwYXNz@example.com:8388",
		"ss plugin":        "ss://YWVzLTI1Ni1nY206cGFzcw@example.com:8388?plugin=obfs-local",
		"ss uot":           "ss://YWVzLTI1Ni1nY206cGFzcw@example.com:8388?uot=1",
		"ss 2022 bad key":  "ss://2022-blake3-aes-256-gcm:c2hvcnQ=@example.com:8388",
		"vmess bad base64": "vmess://!!!!",
		"vmess bad json":   "vmess://" + base64.StdEncoding.EncodeToString([]byte("{")),
		"vmess aid 1":      makeVmessLink(func(m map[string]any) { m["aid"] = "1" }),
		"vmess grpc":       makeVmessLink(func(m map[string]any) { m["net"] = "grpc" }),
		"vmess http hdr":   makeVmessLink(func(m map[string]any) { m["type"] = "http" }),
		"vmess insecure":   makeVmessLink(func(m map[string]any) { m["allowInsecure"] = "1" }),
		"vmess fp":         makeVmessLink(func(m map[string]any) { m["fp"] = "chrome" }),
		"vmess tls bogus":  makeVmessLink(func(m map[string]any) { m["tls"] = "reality" }),
		"vmess bad scy":    makeVmessLink(func(m map[string]any) { m["scy"] = "rc4" }),
		"vmess bad uuid":   makeVmessLink(func(m map[string]any) { m["id"] = "not-a-uuid" }),
		"too long":         "ss://" + strings.Repeat("A", maxLinkLen),
	}
	for name, link := range cases {
		if _, err := Parse(link); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestErrorsDoNotLeakSecrets(t *testing.T) {
	link := makeVmessLink(func(m map[string]any) {
		m["aid"] = "4"
	})
	_, err := Parse(link)
	if err == nil {
		t.Fatal("expected error")
	}
	msg := err.Error()
	for _, secret := range []string{"b831381d-6324-4d53-ad4f-8cda48b30811", "example.com", link[:20]} {
		if strings.Contains(msg, secret) {
			t.Fatalf("error leaks %q: %s", secret, msg)
		}
	}
}

func TestParseVMess(t *testing.T) {
	p, err := Parse(makeVmessLink(nil))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if p.Protocol() != "vmess" || p.Address() != "example.com" || p.Port() != 443 {
		t.Fatalf("profile mismatch: %+v", p)
	}
	if p.Transport() != "ws" || !p.TLS() || p.security != "auto" {
		t.Fatalf("stream mismatch: %+v", p)
	}
	if p.sni != "sni.example.com" || p.path != "/ray" || p.host != "cdn.example.com" {
		t.Fatalf("tls/ws mismatch: %+v", p)
	}
	if fmt.Sprint(p.alpn) != "[h2 http/1.1]" {
		t.Fatalf("alpn mismatch: %v", p.alpn)
	}
}

func TestBuildConfigShapes(t *testing.T) {
	for _, link := range []string{ssAEADLink, makeVmessLink(nil)} {
		p, err := Parse(link)
		if err != nil {
			t.Fatalf("parse: %v", err)
		}
		raw, err := buildConfig(p, 1081, "u", "p")
		if err != nil {
			t.Fatalf("config: %v", err)
		}
		var cfg struct {
			Log struct{ Loglevel string } `json:"loglevel"`
			In  []map[string]any          `json:"inbounds"`
			Out []map[string]any          `json:"outbounds"`
		}
		if err := json.Unmarshal(raw, &cfg); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		if len(cfg.In) != 1 || len(cfg.Out) != 1 {
			t.Fatalf("unexpected inbound/outbound count")
		}
		if cfg.In[0]["listen"] != "127.0.0.1" || cfg.In[0]["protocol"] != "socks" {
			t.Fatalf("inbound mismatch: %v", cfg.In[0])
		}
		settings := cfg.In[0]["settings"].(map[string]any)
		if settings["udp"] != false {
			t.Fatalf("udp must be off")
		}
		stream := cfg.Out[0]["streamSettings"].(map[string]any)
		if stream["security"] == "tls" {
			tls := stream["tlsSettings"].(map[string]any)
			if v, ok := tls["allowInsecure"]; ok && v != false {
				t.Fatalf("allowInsecure must never be set true")
			}
		}
	}
}

func TestSSRejectsNone(t *testing.T) {
	for _, m := range []string{"none", "plain", "rc4-md5", "aes-256-cfb", "", "garbage"} {
		if normalizeSSMethod(m) != "" {
			t.Fatalf("%s must be rejected", m)
		}
	}
}

func TestParseSSPathAndAliases(t *testing.T) {
	// A single trailing root path is legal in the wild.
	p, err := Parse("ss://YWVzLTI1Ni1nY206cGFzcw@example.com:8388/#name")
	if err != nil {
		t.Fatalf("trailing slash: %v", err)
	}
	if p.Port() != 8388 || p.Address() != "example.com" {
		t.Fatalf("trailing slash mismatch: %+v", p)
	}

	// Any other path is not supported.
	if _, err := Parse("ss://YWVzLTI1Ni1nY206cGFzcw@example.com:8388/foo"); err == nil {
		t.Error("expected error for non-root path")
	}
	if _, err := Parse("ss://YWVzLTI1Ni1nY206cGFzcw@example.com:8388/a/b"); err == nil {
		t.Error("expected error for nested path")
	}

	// Legacy/alias cipher names supported by the pinned core are normalized.
	for alias, want := range map[string]string{
		"chacha20-ietf-poly1305":  "chacha20-poly1305",
		"xchacha20-ietf-poly1305": "xchacha20-poly1305",
	} {
		link := fmt.Sprintf("ss://%s:pass@example.com:8388", alias)
		p, err := Parse(link)
		if err != nil {
			t.Fatalf("%s: %v", alias, err)
		}
		if p.method != want {
			t.Fatalf("%s not normalized: %s", alias, p.method)
		}
	}
}

func TestParseVMessDefaultsAndBadTypes(t *testing.T) {
	// Modern links omit aid; AEAD-only means 0.
	link := makeVmessLink(func(m map[string]any) { delete(m, "aid") })
	p, err := Parse(link)
	if err != nil {
		t.Fatalf("missing aid must default to 0: %v", err)
	}
	if p.Protocol() != "vmess" {
		t.Fatalf("unexpected protocol: %s", p.Protocol())
	}
	// Empty string is also treated as 0.
	if _, err := Parse(makeVmessLink(func(m map[string]any) { m["aid"] = "" })); err != nil {
		t.Fatalf("empty aid must default to 0: %v", err)
	}
	// Non-zero still rejected.
	if _, err := Parse(makeVmessLink(func(m map[string]any) { m["aid"] = 2 })); err == nil {
		t.Error("non-zero aid must be rejected")
	}
	// Unparseable allowInsecure must not be silently ignored.
	if _, err := Parse(makeVmessLink(func(m map[string]any) { m["allowInsecure"] = map[string]any{"x": 1} })); err == nil {
		t.Error("unparseable allowInsecure must be rejected")
	}
	// Explicit false is fine.
	if _, err := Parse(makeVmessLink(func(m map[string]any) { m["allowInsecure"] = false })); err != nil {
		t.Fatalf("allowInsecure=false must be accepted: %v", err)
	}
}
