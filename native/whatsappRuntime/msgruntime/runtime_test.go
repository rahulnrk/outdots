package msgruntime

import (
	"encoding/json"
	"testing"
)

func TestNewRuntimeConstructsWithoutNetwork(t *testing.T) {
	r := NewRuntime()
	if r == nil {
		t.Fatal("NewRuntime returned nil")
	}
	if got := r.Platform(); got != "messenger" {
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
	if RequiredCookieNamesCSV() != "xs,c_user,datr" {
		t.Fatalf("RequiredCookieNamesCSV=%q", RequiredCookieNamesCSV())
	}
	if BrowserUserAgent() == "" || LoginStartURL() == "" || LoginSuccessURLPattern() == "" {
		t.Fatal("missing login constants")
	}
	if PinnedWhatsmeow() != "c386243a72ba" {
		t.Fatalf("PinnedWhatsmeow=%q", PinnedWhatsmeow())
	}
}

func TestXSAloneIsNotAccepted(t *testing.T) {
	r := NewRuntime()
	code := r.ImportOwnerSession("xs=only")
	if code != "missing_required_cookies" {
		t.Fatalf("code=%q want missing_required_cookies", code)
	}
	if r.HasSessionCookies() || r.IsAuthenticated() {
		t.Fatal("xs-only must not authenticate")
	}
}

func TestOwnerSessionRoundTripAndEncryptedDeviceStore(t *testing.T) {
	r := NewRuntime()
	t.Cleanup(r.Close)
	header := "xs=probe-xs; c_user=12345; datr=probe-datr; sb=optional"
	if code := r.ImportOwnerSession(header); code != "" {
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
	if snap["platform"] != "facebook" {
		t.Fatalf("platform=%v", snap["platform"])
	}

	devicePath := "file:msgruntime-e2ee?mode=memory&cache=shared&_pragma=foreign_keys(1)"
	if code := r.OpenEncryptedDeviceStore(devicePath); code != "" {
		t.Fatalf("OpenEncryptedDeviceStore=%q", code)
	}
	if !r.HasEncryptedDevice() || r.EncryptedDevicePath() == "" {
		t.Fatal("expected encrypted device after open")
	}
	if code := r.PrepareEncryptedClient(); code != "" {
		t.Fatalf("PrepareEncryptedClient=%q", code)
	}
	if !r.HasPreparedEncryptedClient() {
		t.Fatal("expected prepared encrypted client")
	}
	if code := r.RegisterEncryptedDevice(); code != "not_authenticated" {
		t.Fatalf("Register without verify code=%q", code)
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

func TestVerifyIdentityRequiresCookies(t *testing.T) {
	r := NewRuntime()
	raw := r.VerifyIdentity()
	var result map[string]any
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		t.Fatal(err)
	}
	if result["ok"] != false || result["code"] != "session_incomplete" {
		t.Fatalf("unexpected verify result: %v", result)
	}
}
