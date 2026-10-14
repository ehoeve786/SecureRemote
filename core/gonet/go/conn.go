package srnet

import (
	"context"
	"io"
	"net"
	"strconv"
	"time"
)

// Conn is a TCP connection through a tunnel, read and written by the Kotlin side (as in Tidegate's
// tsbridge). gomobile can only pass byte slices, so Read returns a fresh slice.
type Conn struct{ c net.Conn }

func dialConn(dial func(context.Context, string, string) (net.Conn, error), host string, port, timeoutMs int) (*Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	c, err := dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	return &Conn{c}, nil
}

// Read returns up to size bytes, blocking until some arrive; it fails with io.EOF at the end.
func (c *Conn) Read(size int) ([]byte, error) {
	buf := make([]byte, size)
	n, err := c.c.Read(buf)
	if n > 0 {
		return buf[:n], nil
	}
	if err == nil {
		err = io.EOF
	}
	return nil, err
}

func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

func (c *Conn) Close() error { return c.c.Close() }
