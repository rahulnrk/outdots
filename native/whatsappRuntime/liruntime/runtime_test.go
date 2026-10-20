package liruntime

import (
	"encoding/json"
	"testing"
)

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
	if r.IsAuthenticated() || r.HasSessionCookies() {
		t.Fatal("unexpected authenticated/cookie state")
	}
	if !GlobalLoggingDisabled() {
		t.Fatal("global logging guard not active")
	}
	if RequiredCookieNamesCSV() != "JSESSIONID,li_at" {
		t.Fatalf("RequiredCookieNamesCSV=%q", RequiredCookieNamesCSV())
	}
	if BrowserUserAgent() == "" || LoginStartURL() == "" || LoginSuccessURLPattern() == "" {
		t.Fatal("missing login constants")
	}
}

func TestJsessionAloneIsNotAccepted(t *testing.T) {
	r := NewRuntime()
	code := r.ImportOwnerSession(
		"JSESSIONID=ajax:only",
		"urn:li:page:messaging_thread;abc",
		`{"clientVersion":"1.13.40953","mpVersion":"1.13.40953","mpName":"voyager-web"}`,
	)
	if code != "missing_required_cookies" {
		t.Fatalf("code=%q want missing_required_cookies", code)
	}
	if r.HasSessionCookies() || r.IsAuthenticated() {
		t.Fatal("JSESSIONID-only must not authenticate")
	}
}

func TestOwnerSessionRoundTripRequiresBrowserIdentity(t *testing.T) {
	r := NewRuntime()
	page := "urn:li:page:d_flagship3_feed;abc"
	track := `{"clientVersion":"1.13.40953","mpVersion":"1.13.40953","osName":"web","mpName":"voyager-web"}`
	if code := r.ImportOwnerSession("JSESSIONID=ajax:probe; li_at=token", "", track); code != "missing_page_instance" {
		t.Fatalf("page code=%q", code)
	}
	if code := r.ImportOwnerSession("JSESSIONID=ajax:probe; li_at=token", page, ""); code != "missing_x_li_track" {
		t.Fatalf("track code=%q", code)
	}
	if code := r.ImportOwnerSession("JSESSIONID=ajax:probe; li_at=token", page, track); code != "" {
		t.Fatalf("import code=%q", code)
	}
	if !r.HasSessionCookies() {
		t.Fatal("expected required cookies")
	}
	if r.IsAuthenticated() {
		t.Fatal("cookies alone must not set IsAuthenticated")
	}
	exported := r.ExportSessionJSON()
	if exported == "" {
		t.Fatal("ExportSessionJSON empty")
	}
	var snap map[string]any
	if err := json.Unmarshal([]byte(exported), &snap); err != nil {
		t.Fatal(err)
	}
	r2 := NewRuntime()
	if code := r2.ImportSessionJSON(exported); code != "" {
		t.Fatalf("restore code=%q", code)
	}
	if !r2.HasSessionCookies() {
		t.Fatal("expected cookies after restore")
	}
	r2.ClearSession()
	if r2.HasSessionCookies() || r2.IsAuthenticated() {
		t.Fatal("ClearSession left state behind")
	}
}
