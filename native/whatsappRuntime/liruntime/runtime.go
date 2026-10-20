// Package liruntime is the phone-local LinkedIn messaging protocol facade inside
// the shared gomobile runtime. It wraps go.mau.fi/mautrix-linkedin/pkg/linkedingo.
//
// Probe scope: owner-operated cookie login support (import/export + identity
// verification). No Accounts UI, no send, no remote bridge.
//
// Content-free logging: global zerolog is silenced. Methods never log cookies,
// headers, names, or response bodies.
//
// License: mautrix-linkedin is AGPL-3.0-or-later. See ../NOTICE.
package liruntime

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	zlog "github.com/rs/zerolog/log"

	"go.mau.fi/mautrix-linkedin/pkg/linkedingo"
)

const (
	// PinnedMautrixLinkedIn is the upstream git commit of tag v0.2609.0.
	PinnedMautrixLinkedIn = "788444401499e504b81d50480ddb686cdb976281"
	// PinnedMautrixLinkedInVersion is the Go module version in go.mod.
	PinnedMautrixLinkedInVersion = "v0.2609.0"

	StateUnavailable   = "unavailable"
	StateAuthenticated = "authenticated"

	// UnavailableReason is shown when Accounts asks why LinkedIn is hidden.
	UnavailableReason = "LinkedIn on-device cookie login is only available in the isolated probe"

	CookieJSESSIONID = "JSESSIONID"
	CookieLiAt       = "li_at"

	LoginURL         = "https://www.linkedin.com/login"
	FeedURLPattern   = `^https://www\.linkedin\.com/feed.*$`
)

// Runtime holds one LinkedIn client. One Runtime per account attempt.
type Runtime struct {
	mu             sync.Mutex
	client         *linkedingo.Client
	jar            *linkedingo.StringCookieJar
	pageInstance   string
	xLiTrack       string
	verifiedUserID string
}

type sessionSnapshot struct {
	CookiesJSON  string `json:"cookies_json"`
	PageInstance string `json:"x_li_page_instance"`
	XLITrack     string `json:"x_li_track"`
	UserID       string `json:"user_id,omitempty"`
}

type verifyResult struct {
	OK     bool   `json:"ok"`
	Code   string `json:"code,omitempty"`
	UserID string `json:"user_id,omitempty"`
}

func init() {
	zlog.Logger = zerolog.Nop()
	zerolog.SetGlobalLevel(zerolog.Disabled)
}

// GlobalLoggingDisabled reports whether the content-free logging guard holds.
func GlobalLoggingDisabled() bool {
	return zerolog.GlobalLevel() == zerolog.Disabled && zlog.Logger.GetLevel() == zerolog.Disabled
}

// NewRuntime constructs the real protocol client with an empty cookie jar.
// It performs no network I/O.
func NewRuntime() *Runtime {
	jar := linkedingo.NewEmptyStringCookieJar()
	return &Runtime{client: newClient("", "", jar), jar: jar}
}

func newClient(pageInstance, xLiTrack string, jar *linkedingo.StringCookieJar) *linkedingo.Client {
	return linkedingo.NewClient(
		context.Background(),
		linkedingo.NewURN(""),
		jar,
		pageInstance,
		xLiTrack,
		"",
		linkedingo.Handlers{},
	)
}

// Platform reports the upstream platform identifier ("linkedin").
func (r *Runtime) Platform() string { return "linkedin" }

// State reports authenticated once identity has been verified upstream.
func (r *Runtime) State() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.verifiedUserID != "" {
		return StateAuthenticated
	}
	return StateUnavailable
}

// LoginAvailable stays false for normal Accounts. The probe launches its own UI.
func (r *Runtime) LoginAvailable() bool { return false }

// IsAuthenticated is true only after a successful VerifyIdentity call.
func (r *Runtime) IsAuthenticated() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.verifiedUserID != ""
}

// HasSessionCookies is true when both JSESSIONID and li_at are present.
// This is necessary but not sufficient proof of authentication.
func (r *Runtime) HasSessionCookies() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return jarHasRequiredCookies(r.jar)
}

// UnavailableDetail returns the fixed Accounts-hidden reason.
func (r *Runtime) UnavailableDetail() string { return UnavailableReason }

// RequiredCookieNamesCSV lists cookies that must be present before verify.
func RequiredCookieNamesCSV() string { return CookieJSESSIONID + "," + CookieLiAt }

// BrowserUserAgent is the upstream desktop UA LinkedIn cookies must match.
func BrowserUserAgent() string { return linkedingo.UserAgent }

// LoginStartURL is the owner sign-in page.
func LoginStartURL() string { return LoginURL }

// LoginSuccessURLPattern matches the post-login feed URL.
func LoginSuccessURLPattern() string { return FeedURLPattern }

// PinnedCommit exposes the pinned upstream commit for device tests.
func PinnedCommit() string { return PinnedMautrixLinkedIn }

// PinnedVersion exposes the pinned module version for device tests.
func PinnedVersion() string { return PinnedMautrixLinkedInVersion }

// VerifiedUserID returns the provider identity after VerifyIdentity, else empty.
func (r *Runtime) VerifiedUserID() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.verifiedUserID
}

func jarHasRequiredCookies(jar *linkedingo.StringCookieJar) bool {
	if jar == nil {
		return false
	}
	jsession := jar.GetCookie(CookieJSESSIONID)
	liAt := jar.GetCookie(CookieLiAt)
	return jsession != "" && liAt != "" && liAt != "delete me"
}

// ImportOwnerSession installs cookies + browser identity headers from the WebView probe.
// Returns a content-free code: empty on success.
func (r *Runtime) ImportOwnerSession(cookieHeader, pageInstance, xLiTrack string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.verifiedUserID = ""
	if strings.TrimSpace(cookieHeader) == "" {
		return "missing_cookie_header"
	}
	if strings.TrimSpace(pageInstance) == "" || !strings.Contains(pageInstance, "urn:li:page") {
		return "missing_page_instance"
	}
	if strings.TrimSpace(xLiTrack) == "" || !strings.Contains(xLiTrack, "clientVersion") {
		return "missing_x_li_track"
	}
	jar, err := linkedingo.NewJarFromCookieHeader(cookieHeader)
	if err != nil {
		return "cookie_parse_failed"
	}
	if !jarHasRequiredCookies(jar) {
		return "missing_required_cookies"
	}
	r.jar = jar
	r.pageInstance = pageInstance
	r.xLiTrack = xLiTrack
	r.client = newClient(pageInstance, xLiTrack, jar)
	return ""
}

// VerifyIdentity performs GetCurrentUserProfile. Success proves authentication.
// Returns JSON: {"ok":true,"user_id":"..."} or {"ok":false,"code":"..."}.
// Never includes names, cookies, or tokens.
func (r *Runtime) VerifyIdentity() string {
	r.mu.Lock()
	client := r.client
	jar := r.jar
	r.mu.Unlock()
	if client == nil || !jarHasRequiredCookies(jar) {
		return encodeVerify(verifyResult{OK: false, Code: "session_incomplete"})
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	profile, err := client.GetCurrentUserProfile(ctx)
	if err != nil {
		return encodeVerify(verifyResult{OK: false, Code: classifyVerifyError(err)})
	}
	if profile == nil {
		return encodeVerify(verifyResult{OK: false, Code: "empty_profile"})
	}
	userID := profile.MiniProfile.EntityURN.ID()
	if userID == "" {
		userID = profile.MiniProfile.DashEntityURN.ID()
	}
	if userID == "" {
		return encodeVerify(verifyResult{OK: false, Code: "empty_verified_identity"})
	}
	r.mu.Lock()
	r.verifiedUserID = userID
	r.mu.Unlock()
	return encodeVerify(verifyResult{OK: true, UserID: userID})
}

func classifyVerifyError(err error) string {
	msg := strings.ToLower(err.Error())
	switch {
	case strings.Contains(msg, "access token is no longer valid"), strings.Contains(msg, "li_at"):
		return "session_expired"
	case strings.Contains(msg, "status code 401"), strings.Contains(msg, "status code 403"):
		return "unauthorized"
	case strings.Contains(msg, "status code"):
		return "upstream_http_status"
	case strings.Contains(msg, "timeout"), strings.Contains(msg, "deadline"):
		return "network_error"
	default:
		return "verify_failed"
	}
}

func encodeVerify(v verifyResult) string {
	raw, err := json.Marshal(v)
	if err != nil {
		return `{"ok":false,"code":"encode_failed"}`
	}
	return string(raw)
}

// ExportSessionJSON serializes cookies + browser identity for encrypted storage.
func (r *Runtime) ExportSessionJSON() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.jar == nil || r.pageInstance == "" || r.xLiTrack == "" {
		return ""
	}
	rawCookies, err := r.jar.MarshalJSON()
	if err != nil || len(rawCookies) == 0 {
		return ""
	}
	snap := sessionSnapshot{
		CookiesJSON:  string(rawCookies),
		PageInstance: r.pageInstance,
		XLITrack:     r.xLiTrack,
		UserID:       r.verifiedUserID,
	}
	out, err := json.Marshal(snap)
	if err != nil {
		return ""
	}
	return string(out)
}

// ImportSessionJSON restores a snapshot previously produced by ExportSessionJSON.
// Does not network-verify; call VerifyIdentity after restore.
func (r *Runtime) ImportSessionJSON(sessionJSON string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.verifiedUserID = ""
	var snap sessionSnapshot
	if err := json.Unmarshal([]byte(sessionJSON), &snap); err != nil {
		return "session_parse_failed"
	}
	if snap.CookiesJSON == "" || snap.PageInstance == "" || snap.XLITrack == "" {
		return "session_incomplete"
	}
	jar := linkedingo.NewEmptyStringCookieJar()
	if err := jar.UnmarshalJSON([]byte(snap.CookiesJSON)); err != nil {
		return "cookie_parse_failed"
	}
	if !jarHasRequiredCookies(jar) {
		return "missing_required_cookies"
	}
	r.jar = jar
	r.pageInstance = snap.PageInstance
	r.xLiTrack = snap.XLITrack
	r.client = newClient(snap.PageInstance, snap.XLITrack, jar)
	if snap.UserID != "" {
		// Soft restore of last known id; VerifyIdentity must still succeed for IsAuthenticated.
		r.verifiedUserID = ""
	}
	return ""
}

// ClearSession drops in-memory cookies and identity.
func (r *Runtime) ClearSession() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.jar = linkedingo.NewEmptyStringCookieJar()
	r.pageInstance = ""
	r.xLiTrack = ""
	r.verifiedUserID = ""
	r.client = newClient("", "", r.jar)
}
