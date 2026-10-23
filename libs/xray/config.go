package xraybridge

import (
	"encoding/json"
	"errors"
)

var errConfig = errors.New("xraybridge: failed to build config")

// buildConfig renders the Xray JSON config: a loopback SOCKS inbound with
// credentials plus one outbound for the parsed profile. UDP stays off.
func buildConfig(p *Profile, port int, user, pass string) ([]byte, error) {
	outbound, err := buildOutbound(p)
	if err != nil {
		return nil, err
	}

	cfg := map[string]any{
		// "none" keeps the core from writing connection details to logcat.
		"log": map[string]any{"loglevel": "none"},
		"inbounds": []any{
			map[string]any{
				"listen":   "127.0.0.1",
				"port":     port,
				"protocol": "socks",
				"settings": map[string]any{
					"auth":     "password",
					"accounts": []any{map[string]any{"user": user, "pass": pass}},
					"udp":      false,
				},
				"tag": "nnn-socks-in",
			},
		},
		"outbounds": []any{outbound},
	}
	return json.Marshal(cfg)
}

func buildOutbound(p *Profile) (map[string]any, error) {
	switch p.protocol {
	case "shadowsocks":
		return map[string]any{
			"protocol": "shadowsocks",
			"settings": map[string]any{
				"servers": []any{map[string]any{
					"address":  p.address,
					"port":     p.port,
					"method":   p.method,
					"password": p.password,
				}},
			},
			"streamSettings": map[string]any{"network": "tcp"},
			"tag":            "nnn-proxy",
		}, nil
	case "vmess":
		stream := map[string]any{"network": p.transport}
		if p.transport == "ws" {
			ws := map[string]any{"path": p.path}
			if p.host != "" {
				ws["headers"] = map[string]any{"Host": p.host}
			}
			stream["wsSettings"] = ws
		}
		if p.tls {
			tls := map[string]any{}
			if p.sni != "" {
				tls["serverName"] = p.sni
			}
			if len(p.alpn) > 0 {
				tls["alpn"] = p.alpn
			}
			stream["security"] = "tls"
			stream["tlsSettings"] = tls
		} else {
			stream["security"] = "none"
		}
		return map[string]any{
			"protocol": "vmess",
			"settings": map[string]any{
				"vnext": []any{map[string]any{
					"address": p.address,
					"port":    p.port,
					"users": []any{map[string]any{
						"id":       p.uuid,
						"alterId":  0,
						"security": p.security,
					}},
				}},
			},
			"streamSettings": stream,
			"tag":            "nnn-proxy",
		}, nil
	}
	return nil, errConfig
}
