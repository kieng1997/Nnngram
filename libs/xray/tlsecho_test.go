package xraybridge

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	crand "crypto/rand"
	gotls "crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"io"
	"math/big"
	"net"
	"testing"
	"time"
)

// vlessTestKeys generates a throwaway X25519 key pair formatted the way the
// core expects REALITY keys (base64url of the raw 32 bytes).
func vlessTestKeys(t *testing.T) (privB64, pubB64 string) {
	t.Helper()
	priv, err := ecdh.X25519().GenerateKey(crand.Reader)
	if err != nil {
		t.Fatalf("x25519 keygen: %v", err)
	}
	return base64.RawURLEncoding.EncodeToString(priv.Bytes()),
		base64.RawURLEncoding.EncodeToString(priv.PublicKey().Bytes())
}

// generateTLSCert creates a throwaway self-signed certificate for host.
func generateTLSCert(t *testing.T, host string) gotls.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), crand.Reader)
	if err != nil {
		t.Fatalf("keygen: %v", err)
	}
	tmpl := x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: host},
		DNSNames:              []string{host},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IsCA:                  true,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(crand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("cert: %v", err)
	}
	return gotls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

// tlsEchoServer is a minimal TLS 1.3 server with a self-signed certificate
// for www.example.com, suitable as a REALITY camouflage target. REALITY
// forwards unauthenticated probes (and initially every client hello) to this
// target, so the target must speak TLS 1.3 for the handshake to complete.
func tlsEchoServer(t *testing.T) int {
	t.Helper()
	cert := generateTLSCert(t, "www.example.com")
	l, err := gotls.Listen("tcp", "127.0.0.1:0", &gotls.Config{
		Certificates: []gotls.Certificate{cert},
		NextProtos:   []string{"h2", "http/1.1"},
		MinVersion:   gotls.VersionTLS13,
	})
	if err != nil {
		t.Fatalf("tls listen: %v", err)
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
