package igruntime

import "testing"

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
