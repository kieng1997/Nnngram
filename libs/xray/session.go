package xraybridge

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"net"
	"sync"

	"github.com/xtls/xray-core/core"
)

var (
	errNoPort      = errors.New("xraybridge: no free loopback port")
	errStartFailed = errors.New("xraybridge: core failed to start")
)

// Session is a running Xray core with a loopback SOCKS5 inbound.
type Session struct {
	mu       sync.Mutex
	port     int
	user     string
	pass     string
	instance *core.Instance
}

// Port reports the loopback SOCKS5 port, or 0 once closed.
func (s *Session) Port() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.port
}

// Username is the generated SOCKS5 user name.
func (s *Session) Username() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.user
}

// Password is the generated SOCKS5 password.
func (s *Session) Password() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.pass
}

// Close stops the core. It is idempotent.
func (s *Session) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.instance == nil {
		return nil
	}
	err := s.instance.Close()
	s.instance = nil
	s.port = 0
	s.user = ""
	s.pass = ""
	return err
}

// Version reports the pinned Xray-core version.
func Version() string { return core.Version() }

// Start parses the link and runs a core instance. Several sessions may run
// concurrently. On failure nothing is left running.
func Start(link string) (*Session, error) {
	p, err := Parse(link)
	if err != nil {
		return nil, err
	}
	return startProfile(p)
}

// startProfile runs a core for an already parsed profile.
func startProfile(p *Profile) (*Session, error) {
	if p == nil {
		return nil, errInvalidLink
	}
	user, err := randomToken(12)
	if err != nil {
		return nil, err
	}
	pass, err := randomToken(18)
	if err != nil {
		return nil, err
	}

	port, err := reserveLoopbackPort()
	if err != nil {
		return nil, err
	}
	// ponytail: reserve-then-release TOCTOU window; a lost race fails to start
	// instead of silently binding another app's port. Xray has no way to report
	// the bound port back, so this is unavoidable without patching core.
	cfg, err := buildConfig(p, port, user, pass)
	if err != nil {
		return nil, err
	}

	instance, err := startWithConfig(cfg)
	if err != nil {
		return nil, err
	}
	return &Session{
		port:     port,
		user:     user,
		pass:     pass,
		instance: instance,
	}, nil
}

// startWithConfig builds and starts a core instance. Nothing produced here
// survives a failure, and every error is a static category value: core errors
// may echo the config, which carries credentials.
func startWithConfig(cfg []byte) (instance *core.Instance, err error) {
	config, err := core.LoadConfig("json", bytes.NewReader(cfg))
	if err != nil {
		return nil, errStartFailed
	}
	instance, err = core.New(config)
	if err != nil {
		return nil, errStartFailed
	}
	if err := instance.Start(); err != nil {
		// Some features may already have started; release them before returning.
		_ = instance.Close()
		return nil, errStartFailed
	}
	return instance, nil
}

func reserveLoopbackPort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, errNoPort
	}
	port := l.Addr().(*net.TCPAddr).Port
	if err := l.Close(); err != nil {
		return 0, errNoPort
	}
	if port < 1 || port > maxPort {
		return 0, errNoPort
	}
	return port, nil
}

// randomToken returns a URL-safe random string carrying n bytes of entropy.
func randomToken(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", errors.New("xraybridge: random source failed")
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
