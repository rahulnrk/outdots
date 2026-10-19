package igruntime

import (
	"bytes"
	"testing"

	"github.com/rs/zerolog"
)

func TestGlobalLoggingIsContentFree(t *testing.T) {
	if !GlobalLoggingDisabled() {
		t.Fatalf("global zerolog must be disabled")
	}
	// Any zerolog logger in-process, including upstream's badGlobalLog sites,
	// must emit nothing even when given a real writer.
	var buf bytes.Buffer
	l := zerolog.New(&buf)
	l.Warn().Str("block_data", "secret message").Msg("decode failed")
	if buf.Len() != 0 {
		t.Fatalf("zerolog emitted %d bytes", buf.Len())
	}
}

func TestRuntimeIsRealAndUnavailable(t *testing.T) {
	r := NewRuntime()
	if got := r.Platform(); got != "instagram" {
		t.Fatalf("platform = %q", got)
	}
	if r.State() != StateUnavailable || r.LoginAvailable() {
		t.Fatalf("instagram route must stay unavailable")
	}
	if r.IsAuthenticated() {
		t.Fatalf("fresh runtime must not be authenticated")
	}
}
