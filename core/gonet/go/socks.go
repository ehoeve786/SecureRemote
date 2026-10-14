package srnet

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net"

	"tailscale.com/net/socks5"
)

// dialFunc dials addr through a tunnel.
type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

// socksProxy is a loopback SOCKS5 server the app's SSH client connects through. It needs a password
// so other apps on the phone cannot use it to reach the private network.
type socksProxy struct {
	ln   net.Listener
	Port int    `json:"socksPort"`
	User string `json:"socksUser"`
	Pass string `json:"socksPass"`
}

func startSocks(dial dialFunc, logs *ring) (*socksProxy, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	p := &socksProxy{ln: ln, Port: ln.Addr().(*net.TCPAddr).Port, User: "secrm", Pass: hex.EncodeToString(b)}
	srv := &socks5.Server{Logf: logs.logf, Dialer: dial, Username: p.User, Password: p.Pass}
	go srv.Serve(ln)
	return p, nil
}

func (p *socksProxy) close() {
	if p != nil {
		_ = p.ln.Close()
	}
}
