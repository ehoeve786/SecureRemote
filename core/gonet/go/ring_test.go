package srnet

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRingKeepsOnlyTheNewestLines(t *testing.T) {
	r := newRing(3)
	for i := 1; i <= 5; i++ {
		r.logf("line %d", i)
	}
	lines := strings.Split(r.String(), "\n")
	if len(lines) != 3 || !strings.HasSuffix(lines[0], "line 3") || !strings.HasSuffix(lines[2], "line 5") {
		t.Errorf("ring kept %q", lines)
	}
}

func TestKeyFirstPutsCertAndErrorLinesOnTop(t *testing.T) {
	r := newRing(10)
	r.logf("noise one")
	r.logf("TLS cert issued")
	r.logf("noise two")
	r.logf("dial error: boom")
	out := r.keyFirst()
	head, _, found := strings.Cut(out, "--- Full log ---")
	if !found || !strings.HasPrefix(out, "--- Key lines ---") {
		t.Fatalf("missing sections:\n%s", out)
	}
	if !strings.Contains(head, "TLS cert issued") || !strings.Contains(head, "dial error") {
		t.Errorf("key section lacks key lines:\n%s", head)
	}
	if strings.Contains(head, "noise") {
		t.Errorf("key section includes noise:\n%s", head)
	}
}

func TestKeyFirstWithoutKeyLinesIsTheFullLog(t *testing.T) {
	r := newRing(10)
	r.logf("nothing special")
	if r.keyFirst() != r.String() {
		t.Errorf("keyFirst = %q", r.keyFirst())
	}
}

func TestWriteToMirrorsLinesToFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "log.txt")
	r := newRing(5)
	r.writeTo(path)
	r.logf("hello one")
	r.logf("hello two")
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(b), "hello one") || !strings.Contains(string(b), "hello two") {
		t.Errorf("file = %q", b)
	}
}

func TestFileCopyIsBounded(t *testing.T) {
	path := filepath.Join(t.TempDir(), "node.log")
	r := newRing(10)
	r.writeTo(path)
	defer r.file.Close()
	for i := 0; i < 200_000; i++ {
		r.logf("line %d with some padding to make the file grow", i)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() > maxFileBytes+1024 {
		t.Errorf("log file is %d bytes, want at most about %d", info.Size(), maxFileBytes)
	}
	// The newest line is still in the file.
	b, _ := os.ReadFile(path)
	if !strings.Contains(string(b), "line 199999") {
		t.Errorf("newest line missing from the bounded file")
	}
}
