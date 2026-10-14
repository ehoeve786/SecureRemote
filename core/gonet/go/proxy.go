package srnet

import (
	"crypto/tls"
	"fmt"
	"net/http"
	"net/http/httputil"
	"net/url"
	"time"
)

// newProxyServer serves HTTP already decrypted by its listener, forwarding every request to the
// MCP server's loopback-only remote port, 127.0.0.1:targetPort. Adapted from PocketPilot.
func newProxyServer(targetPort int, logs *ring) *http.Server {
	target := &url.URL{Scheme: "http", Host: fmt.Sprintf("127.0.0.1:%d", targetPort)}
	proxy := &httputil.ReverseProxy{
		Rewrite: func(r *httputil.ProxyRequest) {
			r.SetURL(target)
			// Keep the public host name: the MCP server checks it.
			r.Out.Host = r.In.Host
			r.SetXForwarded()
		},
		// Stream MCP's server-sent events without buffering.
		FlushInterval: -1,
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, err error) {
			http.Error(w, "SecureRemote's MCP server is not running on the phone", http.StatusBadGateway)
		},
	}
	return &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			proxy.ServeHTTP(w, r)
			// Method, path and time only: tokens and bodies are not logged.
			logs.logf("srnet: %s %s %s in %v", r.Method, r.Host, r.URL.Path, time.Since(start).Round(time.Millisecond))
		}),
		ReadHeaderTimeout: 30 * time.Second,
		// TLS is already terminated by the listener.
		TLSNextProto: map[string]func(*http.Server, *tls.Conn, http.Handler){},
	}
}
