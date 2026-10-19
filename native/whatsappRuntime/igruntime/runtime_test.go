package igruntime

import (
	"bytes"
	"strings"
	"testing"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
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
	if !r.IsAuthenticated() {
		t.Fatal("restored native session must be authenticated in memory")
	}
	if _, err := r.ExportSessionEnvelope("instagram|owner-b"); err == nil {
		t.Fatal("runtime must reject export for another account")
	}
	reopened, err := r.ExportSessionEnvelope("instagram|owner-a")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := DecodeSessionEnvelope(reopened); err != nil {
		t.Fatalf("reopened session was not decodable: %v", err)
	}
	r.Logout()
	if r.IsAuthenticated() {
		t.Fatal("logout must clear the in-memory session")
	}
}

func TestLoginMachineStatesAndSecretBoundaries(t *testing.T) {
	machine := NewLoginMachine()
	if machine.State() != LoginStateCredentials {
		t.Fatalf("initial state = %q", machine.State())
	}
	if err := machine.ApplyUpstreamStep(LoginStateTwoFactor, LoginStepWebTwoFactor, "Enter the code", []string{"two_factor_code"}, "", ""); err != nil {
		t.Fatal(err)
	}
	if err := machine.ApplyUpstreamStep(LoginStateWebChallenge, LoginStepWebChallenge, "Continue in browser", nil, "https://www.instagram.com/challenge/", "Instagram test UA"); err != nil {
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
	if err := machine.ApplyUpstreamStep(LoginStateCaptcha, "captcha", "Complete CAPTCHA", []string{"captcha_token"}, "https://www.instagram.com/accounts/login/", ""); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(machine.SnapshotJSON()), "captcha_token") {
		t.Fatal("CAPTCHA token field leaked to ordinary UI state")
	}
	if err := machine.SubmitCaptchaResult(true); err == nil || machine.State() != LoginStateFailed {
		t.Fatalf("unavailable login transport must fail without authenticating: %v / %q", err, machine.State())
	}

	machine = NewLoginMachine()
	if err := machine.ApplyUpstreamStep(LoginStateWebChallenge, LoginStepWebChallenge, "", nil, "https://evil.example/challenge", ""); err == nil {
		t.Fatal("non-Instagram WebView origin must be rejected")
	}
	machine = NewLoginMachine()
	if err := machine.SubmitCredentials("owner", "password-not-retained"); err == nil || machine.State() != LoginStateFailed {
		t.Fatal("credentials must not imply network authentication")
	}
	if strings.Contains(string(machine.SnapshotJSON()), "password-not-retained") {
		t.Fatal("password leaked into login snapshot")
	}
	machine = NewLoginMachine()
	machine.Cancel()
	if machine.State() != LoginStateCancelled {
		t.Fatalf("cancel state = %q", machine.State())
	}
}
