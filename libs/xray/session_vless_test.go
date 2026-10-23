package xraybridge

import (
	"bytes"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"testing"
	"time"
)

const vlessTestUUID = "b831381d-6324-4d53-ad4f-8cda48b30811"

func TestSessionVLESSPlainTCP(t *testing.T) {
	echo := echoServer(t)
	serverPort := startTestServer(t, map[string]any{
		"inbounds": map[string]any{
			"protocol":       "vless",
			"settings":       map[string]any{"clients": []any{map[string]any{"id": vlessTestUUID}}, "decryption": "none"},
			"streamSettings": map[string]any{"network": "tcp", "security": "none"},
		},
		"outbounds": freedomOutbound(),
	})

	link := fmt.Sprintf("vless://%s@127.0.0.1:%d", vlessTestUUID, serverPort)
	sess, err := Start(link)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	defer sess.Close()
	socksRoundTrip(t, sess, echo)
}

func TestSessionVLESSWS(t *testing.T) {
	echo := echoServer(t)
	serverPort := startTestServer(t, map[string]any{
		"inbounds": map[string]any{
			"protocol": "vless",
			"settings": map[string]any{"clients": []any{map[string]any{"id": vlessTestUUID}}, "decryption": "none"},
			"streamSettings": map[string]any{
				"network":    "ws",
				"wsSettings": map[string]any{"path": "/ray"},
			},
		},
		"outbounds": freedomOutbound(),
	})

	link := fmt.Sprintf("vless://%s@127.0.0.1:%d?type=ws&path=/ray", vlessTestUUID, serverPort)
	sess, err := Start(link)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	defer sess.Close()
	socksRoundTrip(t, sess, echo)
}

// TestSessionVLESSREALITYVision drives a full REALITY handshake (with a
// self-signed TLS 1.3 camouflage target) plus a VLESS xtls-rprx-vision
// request, then pushes bytes through the session's SOCKS inbound. The echoed
// payload only comes back if the REALITY handshake authenticated, the vision
// flow was accepted and the server relayed to the echo target — real data
// exchange, not just core startup.
func TestSessionVLESSREALITYVision(t *testing.T) {
	echo := echoServer(t)
	camouflage := tlsEchoServer(t) // REALITY fallback target for probes
	priv, pub := vlessTestKeys(t)
	sid := "6ba85179e30d4fc2"
	if _, err := hex.DecodeString(sid); err != nil {
		t.Fatalf("bad sid: %v", err)
	}

	serverPort := startTestServer(t, map[string]any{
		"inbounds": map[string]any{
			"protocol": "vless",
			"settings": map[string]any{"clients": []any{map[string]any{"id": vlessTestUUID, "flow": "xtls-rprx-vision"}}, "decryption": "none"},
			"streamSettings": map[string]any{
				"network":  "tcp",
				"security": "reality",
				"realitySettings": map[string]any{
					"target":      fmt.Sprintf("127.0.0.1:%d", camouflage),
					"serverNames": []any{"www.example.com"},
					"privateKey":  priv,
					"shortIds":    []any{sid},
				},
			},
		},
		"outbounds": freedomOutbound(),
	})

	link := fmt.Sprintf("vless://%s@127.0.0.1:%d?security=reality&sni=www.example.com&pbk=%s&sid=%s&flow=xtls-rprx-vision",
		vlessTestUUID, serverPort, pub, sid)
	sess, err := Start(link)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	defer sess.Close()
	socksRoundTrip(t, sess, echo)
}

// TestSessionVLESSREALITYWrongKeyIsFailClosed proves that a REALITY client
// with a wrong public key never reaches the proxy target: the server forwards
// the connection to the camouflage target and the SOCKS round trip fails
// rather than echoing.
func TestSessionVLESSREALITYWrongKeyIsFailClosed(t *testing.T) {
	echo := echoServer(t)
	camouflage := tlsEchoServer(t)
	priv, _ := vlessTestKeys(t)
	_, wrongPub := vlessTestKeys(t)
	sid := "6ba85179e30d4fc2"

	serverPort := startTestServer(t, map[string]any{
		"inbounds": map[string]any{
			"protocol": "vless",
			"settings": map[string]any{"clients": []any{map[string]any{"id": vlessTestUUID, "flow": "xtls-rprx-vision"}}, "decryption": "none"},
			"streamSettings": map[string]any{
				"network":  "tcp",
				"security": "reality",
				"realitySettings": map[string]any{
					"target":      fmt.Sprintf("127.0.0.1:%d", camouflage),
					"serverNames": []any{"www.example.com"},
					"privateKey":  priv,
					"shortIds":    []any{sid},
				},
			},
		},
		"outbounds": freedomOutbound(),
	})

	// Start succeeds (config is valid); the data path must fail closed:
	// the wrong key authenticates as a benign probe and is forwarded to the
	// camouflage target, so the round trip must never echo the payload.
	link := fmt.Sprintf("vless://%s@127.0.0.1:%d?security=reality&sni=www.example.com&pbk=%s&sid=%s&flow=xtls-rprx-vision",
		vlessTestUUID, serverPort, wrongPub, sid)
	sess, err := Start(link)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	defer sess.Close()

	port := sess.Port()
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	deadline := time.Now().Add(10 * time.Second)
	var conn net.Conn
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

	// Complete the SOCKS auth like socksRoundTrip does.
	if _, err := conn.Write([]byte{0x05, 0x02, 0x00, 0x02}); err != nil {
		t.Fatalf("greeting: %v", err)
	}
	resp := make([]byte, 2)
	if _, err := io.ReadFull(conn, resp); err != nil {
		t.Fatalf("greeting resp: %v", err)
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

	req := []byte{0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, byte(echo >> 8), byte(echo)}
	if _, err := conn.Write(req); err != nil {
		t.Fatalf("connect: %v", err)
	}
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil {
		t.Fatalf("connect resp: %v", err)
	}
	if head[1] != 0x00 {
		t.Fatalf("socks connect failed: %v", head)
	}
	switch head[3] {
	case 0x01:
		_, _ = io.ReadFull(conn, make([]byte, 4+2))
	case 0x04:
		_, _ = io.ReadFull(conn, make([]byte, 16+2))
	}

	// The wrong key must not reach the echo server: either the write or the
	// read fails, or the bytes never come back as the payload.
	payload := []byte("hello-through-xray")
	if _, err := conn.Write(payload); err != nil {
		return // connection torn down: fail-closed as expected
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err == nil && bytes.Equal(got, payload) {
		t.Fatal("wrong REALITY key must not allow data exchange")
	}
}
