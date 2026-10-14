// Package srnet is SecureRemote's Go networking: an embedded Tailscale node (tsnet) and a userspace
// WireGuard tunnel, adapted from PocketPilot's ppnet. Each gives the app's SSH client a loopback
// SOCKS5 proxy into its private network; the Tailscale node also forwards HTTPS from the tailnet (or
// Funnel) to the MCP server's remote port.
//
// It is built into an AAR with gomobile, so its exported API sticks to strings, ints, bools and
// errors. Status is returned as JSON for the Kotlin side to parse.
package srnet

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// Node is one embedded Tailscale node. Create it with NewNode; all methods are safe to call from any
// thread.
type Node struct {
	stateDir   string
	hostname   string
	targetPort int

	mu        sync.Mutex
	srv       *tsnet.Server
	socks     *socksProxy
	listener  net.Listener
	httpSrv   *http.Server
	public    bool
	lastError string
	logs      *ring
}

// NewNode prepares a node that keeps its state in stateDir, asks for hostname on the tailnet and
// forwards HTTPS to 127.0.0.1:targetPort. Nothing starts until Start.
func NewNode(stateDir, hostname string, targetPort int) *Node {
	return &Node{stateDir: stateDir, hostname: hostname, targetPort: targetPort, logs: newRing(500)}
}

// Start brings the node up in the background. authKey may be empty, in which case Status reports a
// login URL for the owner to open. Calling Start on a running node does nothing.
func (n *Node) Start(authKey string) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.srv != nil {
		return nil
	}
	registerInterfaceGetter()
	n.recordCrashes()
	n.setDirsLocked()
	n.lastError = ""
	srv := &tsnet.Server{
		Dir:      n.stateDir,
		Hostname: n.hostname,
		AuthKey:  authKey,
		Logf:     n.logs.logf,
		UserLogf: n.logs.logf,
	}
	if err := srv.Start(); err != nil {
		n.lastError = err.Error()
		return err
	}
	socks, err := startSocks(n.dial, n.logs)
	if err != nil {
		_ = srv.Close()
		n.lastError = err.Error()
		return err
	}
	n.srv, n.socks = srv, socks
	go n.serveWhenRunning(srv)
	return nil
}

// CrashFile and LogFile, in the state directory, hold the Go runtime's report of a fatal error and
// the node's log lines from the run that hit it. The app reads them after a restart.
const (
	CrashFile = "crash.txt"
	LogFile   = "node.log"
)

// recordCrashes points the Go runtime's fatal-error output, and a copy of the log, at the state
// directory, replacing what an earlier run left there.
func (n *Node) recordCrashes() {
	if err := os.MkdirAll(n.stateDir, 0o700); err != nil {
		return
	}
	n.logs.writeTo(filepath.Join(n.stateDir, LogFile))
	f, err := os.OpenFile(filepath.Join(n.stateDir, CrashFile), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return
	}
	defer f.Close()
	_ = debug.SetCrashOutput(f, debug.CrashOptions{})
}

// setDirsLocked gives Tailscale writable directories. An Android app has no $HOME, cache directory
// or writable temp directory, and Tailscale's log setup panics when it finds none of them.
func (n *Node) setDirsLocked() {
	for env, sub := range map[string]string{"TS_LOGS_DIR": "logs", "HOME": "home", "XDG_CACHE_HOME": "cache", "TMPDIR": "tmp"} {
		if os.Getenv(env) != "" {
			continue
		}
		dir := filepath.Join(n.stateDir, sub)
		if os.MkdirAll(dir, 0o700) == nil {
			_ = os.Setenv(env, dir)
		}
	}
}

// dial sends tailnet destinations (MagicDNS names, Tailscale IPs and subnet routes) through the
// node, and everything else over the phone's own network.
func (n *Node) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	n.mu.Lock()
	srv := n.srv
	n.mu.Unlock()
	if srv != nil {
		if target, ok := tailnetTarget(ctx, srv, addr); ok {
			return srv.Dial(ctx, network, target)
		}
	}
	return direct.DialContext(ctx, network, addr)
}

func tailnetTarget(ctx context.Context, srv *tsnet.Server, addr string) (string, bool) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", false
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "", false
	}
	st, err := lc.Status(ctx)
	if err != nil {
		return "", false
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		name := strings.ToLower(strings.TrimSuffix(host, "."))
		for _, p := range st.Peer {
			dns := strings.ToLower(strings.TrimSuffix(p.DNSName, "."))
			if dns != "" && len(p.TailscaleIPs) > 0 && (name == dns || name == strings.SplitN(dns, ".", 2)[0]) {
				return net.JoinHostPort(p.TailscaleIPs[0].String(), port), true
			}
		}
		ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
		if err != nil || len(ips) == 0 {
			return "", false
		}
		ip = ips[0].Unmap()
	}
	for _, p := range st.Peer {
		if p.AllowedIPs == nil {
			continue
		}
		for _, r := range p.AllowedIPs.All() {
			// Skip exit-node default routes: only the phone's own network reaches the internet.
			if r.Bits() > 0 && r.Contains(ip) {
				return net.JoinHostPort(ip.String(), port), true
			}
		}
	}
	return "", false
}

// serveWhenRunning waits until the node is logged in, then opens the HTTPS listener.
func (n *Node) serveWhenRunning(srv *tsnet.Server) {
	if _, err := srv.Up(context.Background()); err != nil {
		n.setError(fmt.Sprintf("Tailscale did not come up: %v", err))
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.srv != srv {
		return
	}
	if err := n.listenLocked(); err != nil {
		n.lastError = err.Error()
	}
}

// SetPublic turns Funnel on or off. With Funnel on, the node accepts connections from the public
// internet as well as the tailnet on port 443; with it off, only tailnet devices can connect.
func (n *Node) SetPublic(on bool) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.public = on
	if n.srv == nil || n.httpSrv == nil {
		// Applied when the node comes up.
		return nil
	}
	n.closeListenerLocked()
	err := n.listenLocked()
	if err != nil {
		n.lastError = err.Error()
	}
	return err
}

// Stop shuts the node down but keeps its login, so the next Start reconnects without signing in.
func (n *Node) Stop() {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.closeListenerLocked()
	n.socks.close()
	n.socks = nil
	if n.srv != nil {
		_ = n.srv.Close()
		n.srv = nil
	}
}

// Logout signs the node out of the tailnet and stops it; the next Start needs a new login.
func (n *Node) Logout() error {
	n.mu.Lock()
	srv := n.srv
	n.mu.Unlock()
	if srv != nil {
		if lc, err := srv.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			_ = lc.Logout(ctx)
		}
	}
	n.Stop()
	return nil
}

type nodeStatus struct {
	Running bool `json:"running"`
	// BackendState is Tailscale's own: NoState, NeedsLogin, NeedsMachineAuth, Starting, Running, Stopped.
	BackendState string   `json:"backendState"`
	AuthURL      string   `json:"authUrl,omitempty"`
	DNSName      string   `json:"dnsName,omitempty"`
	IPs          []string `json:"ips,omitempty"`
	Serving      bool     `json:"serving"`
	Public       bool     `json:"public"`
	// FunnelProblem names the tailnet setting Funnel still needs, or is empty when Funnel is allowed.
	FunnelProblem string `json:"funnelProblem,omitempty"`
	Error         string `json:"error,omitempty"`
	*socksProxy
}

// Status reports the node's state as JSON.
func (n *Node) Status() string {
	n.mu.Lock()
	srv := n.srv
	st := nodeStatus{Running: srv != nil, Serving: n.httpSrv != nil, Public: n.public, Error: n.lastError, socksProxy: n.socks}
	n.mu.Unlock()
	if srv != nil {
		if lc, err := srv.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if s, err := lc.StatusWithoutPeers(ctx); err == nil {
				st.BackendState = s.BackendState
				st.AuthURL = s.AuthURL
				if s.Self != nil {
					st.DNSName = strings.TrimSuffix(s.Self.DNSName, ".")
					if err := ipn.CheckFunnelAccess(443, s.Self); err != nil {
						st.FunnelProblem = err.Error()
					}
				}
				if len(s.CertDomains) == 0 && s.BackendState == ipn.Running.String() {
					st.FunnelProblem = "HTTPS certificates are off for this tailnet. Turn them on under DNS in the Tailscale admin console."
				}
				for _, ip := range s.TailscaleIPs {
					st.IPs = append(st.IPs, ip.String())
				}
			}
		}
	}
	b, _ := json.Marshal(st)
	return string(b)
}

// Logs returns the node's recent log lines, key lines first.
func (n *Node) Logs() string { return n.logs.keyFirst() }

func (n *Node) setError(msg string) {
	n.mu.Lock()
	n.lastError = msg
	n.mu.Unlock()
}

// listenLocked opens :443 on the node, through Funnel when public, and serves the proxy on it.
func (n *Node) listenLocked() error {
	var (
		ln  net.Listener
		err error
	)
	if n.public {
		ln, err = n.srv.ListenFunnel("tcp", ":443")
	} else {
		ln, err = n.srv.ListenTLS("tcp", ":443")
	}
	if err != nil {
		if n.public {
			// Stay reachable on the tailnet while Funnel is blocked.
			if fallback, ferr := n.srv.ListenTLS("tcp", ":443"); ferr == nil {
				n.serveLocked(fallback)
			}
		}
		return err
	}
	n.serveLocked(ln)
	n.lastError = ""
	go n.warmCert(n.srv)
	return nil
}

// warmCert fetches the node's HTTPS certificate now, so the first client does not time out while
// it is issued.
func (n *Node) warmCert(srv *tsnet.Server) {
	lc, err := srv.LocalClient()
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil || len(st.CertDomains) == 0 {
		return
	}
	if _, _, err := lc.CertPair(ctx, st.CertDomains[0]); err != nil {
		n.logs.logf("srnet: certificate for %s not ready: %v", st.CertDomains[0], err)
		return
	}
	n.logs.logf("srnet: certificate for %s ready", st.CertDomains[0])
}

func (n *Node) serveLocked(ln net.Listener) {
	httpSrv := newProxyServer(n.targetPort, n.logs)
	n.listener = ln
	n.httpSrv = httpSrv
	go func() {
		if err := httpSrv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			n.setError(fmt.Sprintf("HTTPS listener stopped: %v", err))
		}
	}()
}

func (n *Node) closeListenerLocked() {
	if n.httpSrv != nil {
		_ = n.httpSrv.Close()
		n.httpSrv = nil
	}
	if n.listener != nil {
		_ = n.listener.Close()
		n.listener = nil
	}
}
