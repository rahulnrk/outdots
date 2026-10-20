package igruntime

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
	"maunium.net/go/mautrix/bridgev2"
)

func TestGlobalLoggingIsContentFree(t *testing.T) {
	if !GlobalLoggingDisabled() {
		t.Fatalf("global zerolog must be disabled")
	}
	var buf bytes.Buffer
	l := zerolog.New(&buf)
	l.Warn().Str("block_data", "secret message").Msg("decode failed")
	if buf.Len() != 0 {
		t.Fatalf("zerolog emitted %d bytes", buf.Len())
	}
}

func TestRuntimeIsRealAndLoginAvailable(t *testing.T) {
	r := NewRuntime()
	if got := r.Platform(); got != "instagram" {
		t.Fatalf("platform = %q", got)
	}
	if r.State() != StateUnavailable {
		t.Fatalf("fresh runtime must be unavailable, got %q", r.State())
	}
	if !r.LoginAvailable() {
		t.Fatalf("real login flow must be available")
	}
	if r.IsAuthenticated() {
		t.Fatalf("fresh runtime must not be authenticated")
	}
}

func testInstagramEnvelope() *InstagramSessionEnvelope {
	return &InstagramSessionEnvelope{
		SchemaVersion: SessionEnvelopeSchemaVersion,
		AccountID:     "instagram|owner-a",
		Cookies: map[string]string{
			string(cookies.IGCookieSessionID): "session-a",
			string(cookies.IGCookieCSRFToken): "csrf-a",
			string(cookies.IGCookieDSUserID):  "12345",
		},
		NativeSession: &types.InstagramNativeSession{
			Authorization: "Bearer test-authorization",
			UserID:        "12345",
			Username:      "owner_a",
			Device: types.InstagramLoginDevice{
				PhoneID:         "phone-a",
				DeviceID:        "device-a",
				USDIDPrivateKey: "private-key-a",
			},
		},
		SeqID:   42,
		SeqIDTS: time.Unix(1700000000, 0).UTC(),
	}
}

func TestSessionEnvelopeRoundTripAndCorruption(t *testing.T) {
	encoded, err := EncodeSessionEnvelope(testInstagramEnvelope())
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := DecodeSessionEnvelope(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if decoded.AccountID != "instagram|owner-a" || decoded.NativeSession.Device.USDIDPrivateKey != "private-key-a" {
		t.Fatalf("decoded session lost account or device identity: %+v", decoded)
	}
	corrupt := append([]byte(nil), encoded...)
	corrupt[len(corrupt)-1] ^= 1
	if _, err := DecodeSessionEnvelope(corrupt); err == nil {
		t.Fatal("corrupt session must be rejected")
	}
	if _, err := EncodeSessionEnvelope(&InstagramSessionEnvelope{
		SchemaVersion: SessionEnvelopeSchemaVersion,
		AccountID:     "instagram|owner-a",
		NativeSession: testInstagramEnvelope().NativeSession,
		CachedState:   []byte(`{"challenge_url":"https://example.invalid"}`),
	}); err == nil {
		t.Fatal("challenge URL must not be persisted in cached state")
	}
}

func TestRuntimeSessionRestoreIsolationAndLogout(t *testing.T) {
	encoded, err := EncodeSessionEnvelope(testInstagramEnvelope())
	if err != nil {
		t.Fatal(err)
	}
	r := NewRuntime()
	if err := r.RestoreSessionEnvelope("instagram|owner-a", encoded); err != nil {
		t.Fatal(err)
	}
	if !r.IsAuthenticated() || r.State() != StateAuthenticated {
		t.Fatalf("restored native session must be authenticated in memory, state=%q", r.State())
	}
	if _, err := r.ExportSessionEnvelope("instagram|owner-b"); err == nil {
		t.Fatal("runtime must reject export for another account")
	}
	reopened, err := r.ExportSessionEnvelope("instagram|owner-a")
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := DecodeSessionEnvelope(reopened)
	if err != nil {
		t.Fatalf("reopened session was not decodable: %v", err)
	}
	// The stored account is bound to the upstream-verified user ID, never to a
	// screen-supplied username or account key.
	if decoded.NativeSession.UserID != "12345" {
		t.Fatalf("restored session lost verified user ID: %q", decoded.NativeSession.UserID)
	}
	r.Logout()
	if r.IsAuthenticated() {
		t.Fatal("logout must clear the in-memory session")
	}
}

func TestRestoreRejectsCrossAccountAndCorruptFixtures(t *testing.T) {
	encoded, err := EncodeSessionEnvelope(testInstagramEnvelope())
	if err != nil {
		t.Fatal(err)
	}
	r := NewRuntime()
	if err := r.RestoreSessionEnvelope("instagram|someone-else", encoded); err == nil {
		t.Fatal("restore must reject an account-ID mismatch")
	}
	if r.IsAuthenticated() {
		t.Fatal("a rejected restore must not leave an authenticated account")
	}
	truncated := encoded[:len(encoded)-8]
	if err := r.RestoreSessionEnvelope("instagram|owner-a", truncated); err == nil {
		t.Fatal("restore must reject a truncated fixture")
	}
	if r.IsAuthenticated() {
		t.Fatal("a rejected restore must not leave an authenticated account")
	}
}

func TestLoginMachineStatesAndSecretBoundaries(t *testing.T) {
	machine := NewLoginMachine()
	if machine.State() != LoginStateCredentials {
		t.Fatalf("initial state = %q", machine.State())
	}
	if err := machine.ApplyUpstreamStep(LoginStateTwoFactor, LoginStepWebTwoFactor, "Enter the code", []string{"verification_code"}, "", ""); err != nil {
		t.Fatal(err)
	}
	if err := machine.Apply(UpstreamStep{
		State:            LoginStateWebChallenge,
		StepID:           LoginStepWebChallenge,
		Instructions:     "Continue in browser",
		WebViewURL:       "https://www.instagram.com/challenge/",
		WebViewUserAgent: "Instagram test UA",
	}); err != nil {
		t.Fatal(err)
	}
	webURL := machine.WebViewURL()
	userAgent := machine.WebViewUserAgent()
	waitPattern := machine.WebViewWaitPattern()
	if !machine.WebViewPending() || webURL != "https://www.instagram.com/challenge/" || userAgent == "" || waitPattern == "" {
		t.Fatalf("missing WebView request: %q %q %q %v", webURL, userAgent, waitPattern, machine.WebViewPending())
	}
	snapshot := string(machine.SnapshotJSON())
	if strings.Contains(snapshot, webURL) || strings.Contains(snapshot, userAgent) {
		t.Fatalf("ordinary UI snapshot leaked WebView data: %s", snapshot)
	}
	if err := machine.Apply(UpstreamStep{
		State:            LoginStateCaptcha,
		StepID:           LoginStepWebCaptcha,
		Instructions:     "Complete CAPTCHA",
		WebViewURL:       "https://www.instagram.com/accounts/login/",
		WebViewExtractJS: "(function(){return {captcha_token: 'x'}})()",
	}); err != nil {
		t.Fatal(err)
	}
	if got := string(machine.SnapshotJSON()); strings.Contains(got, "captcha_token") || strings.Contains(got, "function") {
		t.Fatalf("CAPTCHA token/script leaked to ordinary UI state: %s", got)
	}
	if machine.WebViewExtractJS() == "" {
		t.Fatal("WebView controller must receive the extraction script")
	}

	machine = NewLoginMachine()
	if err := machine.Apply(UpstreamStep{State: LoginStateWebChallenge, StepID: LoginStepWebChallenge, WebViewURL: "https://evil.example/challenge"}); err == nil {
		t.Fatal("non-Instagram WebView origin must be rejected")
	}
	if machine.State() != LoginStateFailed {
		t.Fatalf("a rejected origin must fail the machine, got %q", machine.State())
	}

	machine = NewLoginMachine()
	machine.Cancel()
	if machine.State() != LoginStateCancelled {
		t.Fatalf("cancel state = %q", machine.State())
	}
	if err := machine.Apply(UpstreamStep{State: LoginStateAuthenticated, StepID: LoginStepCredentials}); err == nil {
		t.Fatal("a cancelled machine must reject further upstream steps")
	}
}

// TestControllerStepTranslation exercises the real bridgev2.LoginStep mapping
// without network I/O: a cookies CAPTCHA step, a web-challenge cookie step, and
// a 2FA user-input step must map to the correct sanitized states and keep
// secrets out of the ordinary-UI snapshot.
func TestControllerStepTranslation(t *testing.T) {
	lc := NewLoginController("instagram|owner-a")
	t.Cleanup(lc.Close)

	captcha := &bridgev2.LoginStep{
		Type:         bridgev2.LoginStepTypeCookies,
		StepID:       LoginStepWebCaptcha,
		Instructions: "Complete CAPTCHA",
		CookiesParams: &bridgev2.LoginCookiesParams{
			URL:       "https://www.instagram.com/accounts/login/",
			UserAgent: "Instagram UA",
			ExtractJS: "(function(){return {captcha_token:'secret'}})()",
			Fields: []bridgev2.LoginCookieField{{
				ID:      "captcha_token",
				Sources: []bridgev2.LoginCookieFieldSource{{Type: bridgev2.LoginCookieTypeSpecial, Name: "captcha_token"}},
			}},
		},
	}
	if err := lc.applyStep(captcha); err != nil {
		t.Fatalf("captcha step: %v", err)
	}
	if lc.State() != LoginStateCaptcha {
		t.Fatalf("captcha step mapped to %q", lc.State())
	}
	if snap := string(lc.Snapshot()); strings.Contains(snap, "captcha_token") || strings.Contains(snap, "secret") || strings.Contains(snap, "instagram.com") {
		t.Fatalf("snapshot leaked WebView/captcha material: %s", snap)
	}
	req := lc.WebViewRequestJSON()
	if req == nil {
		t.Fatal("WebView controller must get a request for a CAPTCHA step")
	}
	var parsed map[string]any
	if err := json.Unmarshal(req, &parsed); err != nil {
		t.Fatal(err)
	}
	if parsed["extract_js"] == "" || parsed["url"] == "" {
		t.Fatalf("WebView request missing url/extract_js: %s", req)
	}

	// A stale WebView result (wrong attempt) must be rejected.
	if err := lc.SubmitWebViewResult(lc.AttemptID()-1, []byte(`{"captcha_token":"x"}`)); err == nil {
		t.Fatal("stale attempt WebView result must be rejected")
	}
	// A result with an undeclared field must be rejected.
	if err := lc.SubmitWebViewResult(lc.AttemptID(), []byte(`{"unexpected":"x"}`)); err == nil {
		t.Fatal("undeclared WebView result field must be rejected")
	}

	// Web challenge: cookie-extraction step with a wait pattern -> web_challenge.
	lc2 := NewLoginController("instagram|owner-a")
	t.Cleanup(lc2.Close)
	challenge := &bridgev2.LoginStep{
		Type:   bridgev2.LoginStepTypeCookies,
		StepID: LoginStepWebChallenge,
		CookiesParams: &bridgev2.LoginCookiesParams{
			URL:               "https://www.instagram.com/challenge/",
			WaitForURLPattern: loginWaitForInstagramURL,
			Fields: []bridgev2.LoginCookieField{
				{ID: "sessionid", Required: true, Sources: []bridgev2.LoginCookieFieldSource{{Type: bridgev2.LoginCookieTypeCookie, Name: "sessionid", CookieDomain: "instagram.com"}}},
			},
		},
	}
	if err := lc2.applyStep(challenge); err != nil {
		t.Fatalf("challenge step: %v", err)
	}
	if lc2.State() != LoginStateWebChallenge {
		t.Fatalf("challenge step mapped to %q", lc2.State())
	}

	// 2FA user input.
	lc3 := NewLoginController("instagram|owner-a")
	t.Cleanup(lc3.Close)
	twoFactor := &bridgev2.LoginStep{
		Type:   bridgev2.LoginStepTypeUserInput,
		StepID: LoginStepWebTwoFactor,
		UserInputParams: &bridgev2.LoginUserInputParams{
			Fields: []bridgev2.LoginInputDataField{{Type: bridgev2.LoginInputFieldType2FACode, ID: "verification_code", Name: "Code"}},
		},
	}
	if err := lc3.applyStep(twoFactor); err != nil {
		t.Fatalf("2fa step: %v", err)
	}
	if lc3.State() != LoginStateTwoFactor {
		t.Fatalf("2fa step mapped to %q", lc3.State())
	}
}

// TestControllerCancellationIsClean verifies cancellation leaves no verified
// session and rejects both stale WebView callbacks and further submissions.
func TestControllerCancellationIsClean(t *testing.T) {
	lc := NewLoginController("instagram|owner-a")
	challenge := &bridgev2.LoginStep{
		Type:   bridgev2.LoginStepTypeCookies,
		StepID: LoginStepWebChallenge,
		CookiesParams: &bridgev2.LoginCookiesParams{
			URL:               "https://www.instagram.com/challenge/",
			WaitForURLPattern: loginWaitForInstagramURL,
			Fields: []bridgev2.LoginCookieField{
				{ID: "sessionid", Sources: []bridgev2.LoginCookieFieldSource{{Type: bridgev2.LoginCookieTypeCookie, Name: "sessionid", CookieDomain: "instagram.com"}}},
			},
		},
	}
	if err := lc.applyStep(challenge); err != nil {
		t.Fatal(err)
	}
	attempt := lc.AttemptID()
	lc.Cancel()
	if lc.State() != LoginStateCancelled {
		t.Fatalf("cancel state = %q", lc.State())
	}
	if _, err := lc.ExportVerifiedSession(); err == nil {
		t.Fatal("cancelled login must not export a verified session")
	}
	if lc.VerifiedUserID() != "" {
		t.Fatal("cancelled login must not retain a verified identity")
	}
	// A WebView result that arrives after cancellation is a stale callback.
	if err := lc.SubmitWebViewResult(attempt, []byte(`{"sessionid":"x"}`)); err == nil {
		t.Fatal("WebView callback after cancellation must be rejected as stale")
	}
	if err := lc.SubmitCredentials("owner", "password-not-retained"); err == nil {
		t.Fatal("a cancelled login must reject new credentials")
	}
	if strings.Contains(string(lc.Snapshot()), "password-not-retained") {
		t.Fatal("password leaked into login snapshot")
	}
}

// TestClassifyUpstreamError confirms upstream error codes map to fixed,
// secret-free failure codes and that the raw error text is never surfaced.
func TestClassifyUpstreamError(t *testing.T) {
	cases := map[string]string{
		"FI.MAU.META_RATE_LIMITED":           LoginFailureRateLimited,
		"FI.MAU.META_ACCOUNT_SUSPENDED":      LoginFailureAccountSuspended,
		"FI.MAU.META_WEB_CHECKPOINT_CAPTCHA": LoginFailureCheckpoint,
		"FI.MAU.META_UNSUPPORTED_CAA_STEP":   LoginFailureCheckpoint,
		"FI.MAU.META_MATRIX_ID":              LoginFailureCredentialsRejected,
		"M_UNKNOWN":                          LoginFailureUpstream,
	}
	for code, want := range cases {
		got := classifyUpstreamError(bridgev2.RespError{ErrCode: code, Err: "https://secret.example/token=abc"})
		if got != want {
			t.Fatalf("code %q => %q, want %q", code, got, want)
		}
		if strings.Contains(got, "secret") || strings.Contains(got, "http") {
			t.Fatalf("failure code leaked raw error: %q", got)
		}
	}
}
