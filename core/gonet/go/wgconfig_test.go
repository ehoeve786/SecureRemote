package srnet

import (
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net/netip"
	"strings"
	"testing"
)

// RFC 7748 §6.1, Alice: the private scalar and its X25519 public key.
const (
	alicePrivHex = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
	alicePubHex  = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
	pskHex       = "1111111111111111111111111111111111111111111111111111111111111111"
)

func b64(h string) string {
	b, _ := hex.DecodeString(h)
	return base64.StdEncoding.EncodeToString(b)
}

func sampleConfig(extraPeer string) string {
	return fmt.Sprintf(`[Interface]
PrivateKey = %s   # the phone's key
Address = 10.8.0.2/32, fd00::2
DNS = 10.8.0.1, corp.local
MTU = 1380

[Peer]
PublicKey = %s
PresharedKey = %s
Endpoint = vpn.example.com:51820
AllowedIPs = 10.8.0.0/24, 192.168.1.5
PersistentKeepalive = 25
%s`, b64(alicePrivHex), b64(alicePubHex), b64(pskHex), extraPeer)
}

func TestParseReadsEveryField(t *testing.T) {
	cfg, err := parseWgQuick(sampleConfig(""))
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(cfg.PrivateKey) != alicePrivHex {
		t.Errorf("private key not read")
	}
	if got := fmt.Sprint(cfg.Addresses); got != "[10.8.0.2/32 fd00::2/128]" {
		t.Errorf("addresses = %s", got)
	}
	if len(cfg.DNS) != 1 || cfg.DNS[0] != netip.MustParseAddr("10.8.0.1") {
		t.Errorf("DNS = %v; search domains should be dropped", cfg.DNS)
	}
	if cfg.MTU != 1380 {
		t.Errorf("MTU = %d", cfg.MTU)
	}
	p := cfg.Peers[0]
	if p.Endpoint != "vpn.example.com:51820" || p.Keepalive != 25 {
		t.Errorf("endpoint/keepalive = %q/%d", p.Endpoint, p.Keepalive)
	}
	if fmt.Sprint(p.AllowedIPs) != "[10.8.0.0/24 192.168.1.5/32]" {
		t.Errorf("allowed IPs = %v", p.AllowedIPs)
	}
	if len(p.PresharedKey) != 32 {
		t.Errorf("preshared key not read")
	}
}

func TestParseDefaultsAndKeepaliveOff(t *testing.T) {
	text := fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = 10.0.0.2\n[Peer]\nPublicKey = %s\nPersistentKeepalive = off\n",
		b64(alicePrivHex), b64(alicePubHex))
	cfg, err := parseWgQuick(text)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.MTU != 1280 {
		t.Errorf("default MTU = %d, want 1280", cfg.MTU)
	}
	if cfg.Peers[0].Keepalive != 0 {
		t.Errorf("keepalive off should be 0")
	}
	if cfg.Addresses[0].String() != "10.0.0.2/32" {
		t.Errorf("bare address should become a host prefix, got %s", cfg.Addresses[0])
	}
}

func TestParseRejectsBadConfigs(t *testing.T) {
	peer := fmt.Sprintf("[Peer]\nPublicKey = %s\n", b64(alicePubHex))
	iface := fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = 10.0.0.2/32\n", b64(alicePrivHex))
	cases := map[string]struct{ text, want string }{
		"no private key":      {"[Interface]\nAddress = 10.0.0.2/32\n" + peer, "PrivateKey"},
		"short key":           {"[Interface]\nPrivateKey = c2hvcnQ=\nAddress = 10.0.0.2/32\n" + peer, "not a WireGuard key"},
		"unknown section":     {iface + "[Routes]\n" + peer, "unknown section"},
		"no equals sign":      {iface + "garbage\n" + peer, "expected key = value"},
		"key outside section": {"PrivateKey = " + b64(alicePrivHex) + "\n", "outside a section"},
		"no address":          {fmt.Sprintf("[Interface]\nPrivateKey = %s\n", b64(alicePrivHex)) + peer, "Address"},
		"no peer":             {iface, "[Peer]"},
		"peer without key":    {iface + "[Peer]\nAllowedIPs = 10.0.0.0/8\n", "needs a PublicKey"},
		"endpoint no port":    {iface + peer + "Endpoint = vpn.example.com\n", "host and port"},
		"bad address":         {"[Interface]\nPrivateKey = " + b64(alicePrivHex) + "\nAddress = 10.0.0.999/32\n" + peer, "is not an address"},
	}
	for name, c := range cases {
		_, err := parseWgQuick(c.text)
		if err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: err = %v, want it to mention %q", name, err, c.want)
		}
	}
}

func TestUAPIRendersKeysAndResolvesEndpoint(t *testing.T) {
	cfg, err := parseWgQuick(sampleConfig(""))
	if err != nil {
		t.Fatal(err)
	}
	resolve := func(hostport string) (netip.AddrPort, error) {
		if hostport != "vpn.example.com:51820" {
			t.Errorf("resolved %q", hostport)
		}
		return netip.MustParseAddrPort("203.0.113.7:51820"), nil
	}
	out, err := cfg.uapi(resolve)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"private_key=" + alicePrivHex,
		"public_key=" + alicePubHex,
		"preshared_key=" + pskHex,
		"endpoint=203.0.113.7:51820",
		"persistent_keepalive_interval=25",
		"allowed_ip=10.8.0.0/24",
		"allowed_ip=192.168.1.5/32",
		"replace_peers=true",
	} {
		if !strings.Contains(out, want+"\n") {
			t.Errorf("uapi missing %q in:\n%s", want, out)
		}
	}
}

func TestUAPIReportsUnresolvableEndpoint(t *testing.T) {
	cfg, _ := parseWgQuick(sampleConfig(""))
	_, err := cfg.uapi(func(string) (netip.AddrPort, error) { return netip.AddrPort{}, fmt.Errorf("no such host") })
	if err == nil || !strings.Contains(err.Error(), "could not look up the server") {
		t.Errorf("err = %v", err)
	}
}

func TestPublicKeyOfConfigMatchesRFC7748(t *testing.T) {
	pub, err := PublicKeyOfConfig(sampleConfig(""))
	if err != nil {
		t.Fatal(err)
	}
	if pub != b64(alicePubHex) {
		t.Errorf("public key = %s, want %s", pub, b64(alicePubHex))
	}
	if _, err := PublicKeyOfConfig("[Interface]\n"); err == nil {
		t.Error("an invalid config should fail")
	}
}
