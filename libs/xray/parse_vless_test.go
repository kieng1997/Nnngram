package xraybridge

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

const (
	vlessPlain = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?encryption=none&type=tcp"
	vlessTLS   = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=tcp&security=tls&sni=sni.example.com&alpn=h2,http/1.1"
	// X25519 public key + short id from a throwaway key pair; not a secret.
	realityPBK  = "kMV1S5k8d3d2bW3q0V1Oc0S2Z8Qw7nN4mJ6hH5gL9Uo"
	realityLink = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=tcp&security=reality&sni=www.example.com&fp=chrome&pbk=" + realityPBK + "&sid=6ba85179e30d4fc2&spx=%2F&flow=xtls-rprx-vision"
)

func TestParseVLESSPlainTCP(t *testing.T) {
	p, err := Parse(vlessPlain)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if p.Protocol() != "vless" || p.Address() != "example.com" || p.Port() != 443 {
		t.Fatalf("profile mismatch: %s %s %d", p.Protocol(), p.Address(), p.Port())
	}
	if p.Transport() != "tcp" || p.Security() != "none" {
		t.Fatalf("stream mismatch: %s %s", p.Transport(), p.Security())
	}
}

func TestParseVLESSRawAlias(t *testing.T) {
	p, err := Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=raw")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if p.Transport() != "tcp" {
		t.Fatalf("raw must alias tcp: %s", p.Transport())
	}
}

func TestParseVLESSWS(t *testing.T) {
	p, err := Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=ws&host=cdn.example.com&path=%2Fray&security=tls")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if p.Transport() != "ws" || !p.TLS() || p.sni != "cdn.example.com" {
		t.Fatalf("ws tls mismatch: %s %v %q", p.Transport(), p.TLS(), p.sni)
	}
	if p.path != "/ray" || p.host != "cdn.example.com" {
		t.Fatalf("ws settings mismatch: %q %q", p.path, p.host)
	}
}

func TestParseVLESSTLS(t *testing.T) {
	p, err := Parse(vlessTLS)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if p.Security() != "tls" || p.sni != "sni.example.com" {
		t.Fatalf("tls mismatch: %s %q", p.Security(), p.sni)
	}
	if fmt.Sprint(p.alpn) != "[h2 http/1.1]" {
		t.Fatalf("alpn mismatch: %v", p.alpn)
	}
}

func TestParseVLESSREALITY(t *testing.T) {
	p, err := Parse(realityLink)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if p.Security() != "reality" || p.TLS() {
		t.Fatalf("security mismatch: %s %v", p.Security(), p.TLS())
	}
	if p.flow != "xtls-rprx-vision" || p.fingerprint != "chrome" {
		t.Fatalf("flow/fp mismatch: %q %q", p.flow, p.fingerprint)
	}
	if p.publicKey != realityPBK || p.shortID != "6ba85179e30d4fc2" || p.spiderX != "/" {
		t.Fatalf("reality fields mismatch: %q %q %q", p.publicKey, p.shortID, p.spiderX)
	}

	// Minimal link: empty sid valid, fingerprint defaults to chrome.
	p2, err := Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK)
	if err != nil {
		t.Fatalf("minimal reality: %v", err)
	}
	if p2.shortID != "" || p2.fingerprint != "chrome" || p2.spiderX != "" {
		t.Fatalf("minimal reality defaults mismatch: %+v", p2)
	}
}

func TestParseVLESSRejects(t *testing.T) {
	cases := map[string]string{
		"bad uuid":              "vless://not-a-uuid@example.com:443",
		"uuid with password":    "vless://b831381d-6324-4d53-ad4f-8cda48b30811:pw@example.com:443",
		"bad port":              "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:0",
		"grpc":                  "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=grpc",
		"kcp":                   "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=kcp",
		"http header":           "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=tcp&headerType=http",
		"bogus security":        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=xtls",
		"tls fp bogus":          "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=tls&fp=nosuch",
		"pbk without reality":   "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?pbk=" + realityPBK,
		"reality ws":            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=ws&security=reality&pbk=" + realityPBK,
		"bad pbk":               "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=short",
		"bad sid":               "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&sid=zzz",
		"long sid":              "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&sid=6ba85179e30d4fc2aa",
		"bad spx":               "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&spx=http%3A%2F%2Fx",
		"bad fp":                "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&fp=nosuch",
		"fp unsafe":             "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&fp=unsafe",
		"fp hellogolang":        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&fp=hellogolang",
		"reality with alpn":     "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&alpn=h2",
		"bad flow":              "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&flow=xtls-rprx-splice",
		"flow without security": "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?flow=xtls-rprx-vision",
		"flow over ws":          "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=ws&security=tls&flow=xtls-rprx-vision",
		"encryption set":        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?encryption=auto",
		"insecure":              "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=tls&allowInsecure=1",
		"bad insecure value":    "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=tls&allowInsecure=bogus",
		"duplicate security":    "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=tls&security=reality",
		"duplicate pbk":         "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&pbk=" + realityPBK,
		"empty host":            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@:443",
		"ws bad host":           "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=ws&host=bad+host",
	}
	for name, link := range cases {
		if _, err := Parse(link); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestParseVLESSEmptySIDValid(t *testing.T) {
	// An explicitly empty sid is accepted (server may not use one).
	p, err := Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&sid=&sni=www.example.com")
	if err != nil {
		t.Fatalf("empty sid must be valid: %v", err)
	}
	if p.shortID != "" || p.sni != "www.example.com" {
		t.Fatalf("empty sid mismatch: %+v", p)
	}
}

func TestVLESSErrorsDoNotLeakSecrets(t *testing.T) {
	link := "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=badkey&flow=xtls-rprx-vision"
	_, err := Parse(link)
	if err == nil {
		t.Fatal("expected error")
	}
	msg := err.Error()
	for _, secret := range []string{"b831381d-6324-4d53-ad4f-8cda48b30811", "example.com", "badkey", link[:20]} {
		if strings.Contains(msg, secret) {
			t.Fatalf("error leaks %q: %s", secret, msg)
		}
	}
}

func TestBuildConfigVLESS(t *testing.T) {
	for _, link := range []string{vlessPlain, vlessTLS, realityLink} {
		p, err := Parse(link)
		if err != nil {
			t.Fatalf("parse: %v", err)
		}
		raw, err := buildConfig(p, 1081, "u", "p")
		if err != nil {
			t.Fatalf("config: %v", err)
		}
		var cfg struct {
			Outbounds []struct {
				Protocol       string `json:"protocol"`
				StreamSettings struct {
					Security string `json:"security"`
					Network  string `json:"network"`
				} `json:"streamSettings"`
			} `json:"outbounds"`
		}
		if err := json.Unmarshal(raw, &cfg); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		if len(cfg.Outbounds) != 1 || cfg.Outbounds[0].Protocol != "vless" {
			t.Fatalf("outbound mismatch: %+v", cfg.Outbounds)
		}
		want := "none"
		if p.reality {
			want = "reality"
		} else if p.tls {
			want = "tls"
		}
		if cfg.Outbounds[0].StreamSettings.Security != want {
			t.Fatalf("security mismatch: got %q want %q", cfg.Outbounds[0].StreamSettings.Security, want)
		}
	}
}

func TestParseVLESSExtras(t *testing.T) {
	// WS without host override is valid; sni falls back to nothing.
	p, err := Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=ws&path=%2Fray&security=tls")
	if err != nil {
		t.Fatalf("ws without host must be valid: %v", err)
	}
	if p.host != "" || p.path != "/ray" {
		t.Fatalf("ws without host mismatch: %q %q", p.host, p.path)
	}

	// serverName is an accepted alias of sni.
	p, err = Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=tls&serverName=sni.example.com")
	if err != nil {
		t.Fatalf("serverName alias: %v", err)
	}
	if p.sni != "sni.example.com" {
		t.Fatalf("serverName alias lost: %q", p.sni)
	}
	if _, err := Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=tls&sni=a.com&serverName=b.com"); err == nil {
		t.Error("sni+serverName must be rejected")
	}

	// Non-root URI path is rejected, not ignored.
	if _, err := Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443/ray?type=tcp"); err == nil {
		t.Error("non-root path must be rejected")
	}
	if _, err := Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443/"); err != nil {
		t.Fatalf("trailing root path must be accepted: %v", err)
	}

	// REALITY + explicit alpn: the pinned core ignores it, so reject.
	if _, err := Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&pbk=" + realityPBK + "&alpn=h2"); err == nil {
		t.Error("reality with alpn must be rejected")
	}

	// TLS fingerprint is stored and emitted.
	p, err = Parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=tls&fp=safari")
	if err != nil {
		t.Fatalf("tls fp: %v", err)
	}
	if p.fingerprint != "safari" {
		t.Fatalf("tls fp lost: %q", p.fingerprint)
	}
	raw, err := buildConfig(p, 1081, "u", "p")
	if err != nil {
		t.Fatalf("config: %v", err)
	}
	var cfg struct {
		Outbounds []struct {
			StreamSettings struct {
				TLSSettings struct {
					Fingerprint string `json:"fingerprint"`
				} `json:"tlsSettings"`
			} `json:"streamSettings"`
		} `json:"outbounds"`
	}
	if err := json.Unmarshal(raw, &cfg); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if cfg.Outbounds[0].StreamSettings.TLSSettings.Fingerprint != "safari" {
		t.Fatalf("tls fp not emitted: %+v", cfg.Outbounds[0].StreamSettings)
	}
}
