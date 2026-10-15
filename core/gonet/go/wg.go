package srnet

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"

	"github.com/tailscale/wireguard-go/conn"
	"github.com/tailscale/wireguard-go/device"
	"golang.org/x/crypto/curve25519"
	"secrm/srnet/internal/wgnetstack"
)

// Tunnel is a userspace WireGuard interface on its own network stack inside the app: no VpnService,
// and the rest of the phone's traffic is untouched. The app's SSH client reaches the peers'
// AllowedIPs through Dial. Adapted from PocketPilot's ppnet.Tunnel.
type Tunnel struct {
	logs *ring

	mu        sync.Mutex
	dev       *device.Device
	tnet      *wgnetstack.Net
	addresses []netip.Prefix
	lastError string
}

func NewTunnel() *Tunnel { return &Tunnel{logs: newRing(500)} }

// Start brings the tunnel up from a wg-quick config. A running tunnel is replaced.
func (t *Tunnel) Start(config string) error {
	cfg, err := parseWgQuick(config)
	if err != nil {
		t.setError(err.Error())
		return err
	}
	uapi, err := cfg.uapi(resolveUDP)
	if err != nil {
		t.setError(err.Error())
		return err
	}
	// One lock for the whole replace, so two Starts cannot each open a device and orphan one.
	t.mu.Lock()
	defer t.mu.Unlock()
	t.stopLocked()
	t.lastError = ""

	addrs := make([]netip.Addr, 0, len(cfg.Addresses))
	for _, p := range cfg.Addresses {
		addrs = append(addrs, p.Addr())
	}
	tunDev, tnet, err := wgnetstack.CreateNetTUN(addrs, cfg.DNS, cfg.MTU)
	if err != nil {
		t.lastError = err.Error()
		return err
	}
	logger := &device.Logger{
		Verbosef: func(format string, args ...any) { t.logs.logf("wg: "+format, args...) },
		Errorf:   func(format string, args ...any) { t.logs.logf("wg error: "+format, args...) },
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), logger)
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		t.lastError = fmt.Sprintf("WireGuard rejected the config: %v", err)
		return errors.New(t.lastError)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		t.lastError = err.Error()
		return err
	}
	t.dev, t.tnet, t.addresses = dev, tnet, cfg.Addresses
	t.logs.logf("srnet: WireGuard up as %s", addrs[0])
	return nil
}

// Dial opens a TCP connection through the tunnel; names resolve with the config's DNS servers.
func (t *Tunnel) Dial(host string, port, timeoutMs int) (*Conn, error) {
	t.mu.Lock()
	tn := t.tnet
	t.mu.Unlock()
	if tn == nil {
		return nil, errors.New("WireGuard is not running")
	}
	return dialConn(tn.DialContext, host, port, timeoutMs)
}

// Stop takes the tunnel down.
func (t *Tunnel) Stop() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.stopLocked()
}

// stopLocked takes the device down. The caller holds t.mu.
func (t *Tunnel) stopLocked() {
	if t.dev != nil {
		t.dev.Close()
	}
	t.dev, t.tnet, t.addresses = nil, nil, nil
}

type wgStatus struct {
	Running bool     `json:"running"`
	Address []string `json:"addresses,omitempty"`
	// LastHandshake is the newest handshake with any peer, in Unix milliseconds, or 0 if none yet.
	LastHandshake int64  `json:"lastHandshake"`
	Error         string `json:"error,omitempty"`
}

// Status reports the tunnel's state as JSON.
func (t *Tunnel) Status() string {
	t.mu.Lock()
	st := wgStatus{Running: t.dev != nil, Error: t.lastError}
	for _, a := range t.addresses {
		st.Address = append(st.Address, a.Addr().String())
	}
	dev := t.dev
	t.mu.Unlock()
	if dev != nil {
		if get, err := dev.IpcGet(); err == nil {
			st.LastHandshake = lastHandshake(get)
		}
	}
	b, _ := json.Marshal(st)
	return string(b)
}

// Logs returns the tunnel's recent log lines, key lines first.
func (t *Tunnel) Logs() string { return t.logs.keyFirst() }

func (t *Tunnel) setError(msg string) {
	t.mu.Lock()
	t.lastError = msg
	t.mu.Unlock()
	t.logs.logf("srnet: WireGuard error: %s", msg)
}

// lastHandshake finds the newest peer handshake in an IpcGet dump, in Unix milliseconds.
func lastHandshake(uapi string) (newest int64) {
	var sec int64
	sc := bufio.NewScanner(strings.NewReader(uapi))
	for sc.Scan() {
		key, value, _ := strings.Cut(sc.Text(), "=")
		n, _ := strconv.ParseInt(value, 10, 64)
		switch key {
		case "last_handshake_time_sec":
			sec = n
		case "last_handshake_time_nsec":
			if ms := sec*1000 + n/1_000_000; ms > newest {
				newest = ms
			}
		}
	}
	return newest
}

func resolveUDP(hostport string) (netip.AddrPort, error) {
	addr, err := net.ResolveUDPAddr("udp", hostport)
	if err != nil {
		return netip.AddrPort{}, err
	}
	// ResolveUDPAddr gives IPv4 as an IPv4-mapped IPv6 address, which WireGuard would send over IPv6.
	ap := addr.AddrPort()
	return netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port()), nil
}

// PublicKeyOfConfig returns the public key, base64, for the PrivateKey in a wg-quick config: the
// value that goes in the server's [Peer] for this phone. It also validates the config.
func PublicKeyOfConfig(config string) (string, error) {
	cfg, err := parseWgQuick(config)
	if err != nil {
		return "", err
	}
	pub, err := curve25519.X25519(cfg.PrivateKey, curve25519.Basepoint)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(pub), nil
}
