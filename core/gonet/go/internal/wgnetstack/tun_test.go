package wgnetstack

import (
	"net/netip"
	"os"
	"testing"
	"time"

	"github.com/tailscale/wireguard-go/tun"
)

func newTestTUN(t *testing.T) tun.Device {
	t.Helper()
	dev, _, err := CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.0.0.2")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	return dev
}

// Read must return instead of blocking forever once the device is closed.
func TestReadAfterCloseReturnsErrClosed(t *testing.T) {
	dev := newTestTUN(t)
	if err := dev.Close(); err != nil {
		t.Fatal(err)
	}
	errc := make(chan error, 1)
	go func() {
		_, err := dev.Read(make([]byte, 2048), make([]tun.ReadPacket, 1))
		errc <- err
	}()
	select {
	case err := <-errc:
		if err != os.ErrClosed {
			t.Fatalf("Read after Close: got %v, want os.ErrClosed", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Read blocked after Close")
	}
}

// Close must be idempotent: a second call must not panic on closed channels.
func TestCloseIsIdempotent(t *testing.T) {
	dev := newTestTUN(t)
	if err := dev.Close(); err != nil {
		t.Fatal(err)
	}
	if err := dev.Close(); err != nil {
		t.Fatal(err)
	}
}
