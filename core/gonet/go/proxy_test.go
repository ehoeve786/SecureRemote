package srnet

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestProxyForwardsToLoopbackKeepingThePublicHost(t *testing.T) {
	var gotHost, gotPath string
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotHost, gotPath = r.Host, r.URL.Path
		w.Write([]byte("ok"))
	}))
	defer backend.Close()
	port := backend.Listener.Addr().(*net.TCPAddr).Port

	logs := newRing(10)
	srv := newProxyServer(port, logs)
	rec := httptest.NewRecorder()
	srv.Handler.ServeHTTP(rec, httptest.NewRequest("POST", "https://box.tail1.ts.net/mcp?token=secret", nil))

	if rec.Code != http.StatusOK || rec.Body.String() != "ok" {
		t.Fatalf("status %d body %q", rec.Code, rec.Body.String())
	}
	if gotHost != "box.tail1.ts.net" || gotPath != "/mcp" {
		t.Errorf("backend saw host %q path %q", gotHost, gotPath)
	}
	if !strings.Contains(logs.String(), "srnet: POST box.tail1.ts.net /mcp") {
		t.Errorf("log line missing: %q", logs.String())
	}
	if strings.Contains(logs.String(), "secret") {
		t.Errorf("query string leaked into the log: %q", logs.String())
	}
}

func TestProxyReportsWhenMCPIsDown(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()

	srv := newProxyServer(port, newRing(10))
	rec := httptest.NewRecorder()
	srv.Handler.ServeHTTP(rec, httptest.NewRequest("GET", "https://box.tail1.ts.net/mcp", nil))
	if rec.Code != http.StatusBadGateway || !strings.Contains(rec.Body.String(), "not running") {
		t.Errorf("status %d body %q", rec.Code, rec.Body.String())
	}
}
