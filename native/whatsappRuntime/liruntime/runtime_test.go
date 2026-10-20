package liruntime

import "testing"

func TestNewRuntimeConstructsWithoutNetwork(t *testing.T) {
	r := NewRuntime()
	if r == nil {
		t.Fatal("NewRuntime returned nil")
	}
	if got := r.Platform(); got != "linkedin" {
		t.Fatalf("Platform=%q", got)
	}
	if got := r.State(); got != StateUnavailable {
		t.Fatalf("State=%q", got)
	}
	if r.LoginAvailable() {
		t.Fatal("LoginAvailable unexpectedly true")
	}
	if r.IsAuthenticated() {
		t.Fatal("IsAuthenticated unexpectedly true")
	}
	if !GlobalLoggingDisabled() {
		t.Fatal("global logging guard not active")
	}
	if PinnedVersion() != PinnedMautrixLinkedInVersion {
		t.Fatalf("PinnedVersion=%q", PinnedVersion())
	}
	if RequiredCookieName() != "JSESSIONID" {
		t.Fatalf("RequiredCookieName=%q", RequiredCookieName())
	}
	if BrowserUserAgent() == "" {
		t.Fatal("BrowserUserAgent empty")
	}
}

func TestCookieHeaderRoundTrip(t *testing.T) {
	r := NewRuntime()
	if r.ImportCookieHeader("foo=bar") {
		t.Fatal("accepted cookie header without JSESSIONID")
	}
	if !r.ImportCookieHeader("JSESSIONID=ajax:probe; li_at=token") {
		t.Fatal("rejected valid cookie header")
	}
	if !r.IsAuthenticated() {
		t.Fatal("expected authenticated after import")
	}
	if got := r.State(); got != StateAuthenticated {
		t.Fatalf("State=%q", got)
	}
	exported := r.ExportCookiesJSON()
	if exported == "" {
		t.Fatal("ExportCookiesJSON empty")
	}
	r2 := NewRuntime()
	if !r2.ImportCookiesJSON(exported) {
		t.Fatal("ImportCookiesJSON failed")
	}
	if !r2.IsAuthenticated() {
		t.Fatal("expected authenticated after JSON restore")
	}
}
