// Package liruntime is the phone-local LinkedIn messaging protocol facade inside
// the shared gomobile runtime. It wraps go.mau.fi/mautrix-linkedin/pkg/linkedingo.
//
// Scope of this slice: prove the real protocol client compiles into the one
// coordinated libgojni.so and is constructible with an empty cookie jar.
// No login, cookie import, session restore, network connection, message access,
// or Accounts UI is exposed. Those stay unavailable until an isolated probe
// implements the upstream cookie login and encrypted per-account storage.
//
// Content-free logging: construction uses context.Background() without a
// zerolog logger attached; no disk writes.
//
// License: mautrix-linkedin is AGPL-3.0-or-later. Its LICENSE.exceptions grant
// rights only to Beeper and Element. Linking this package makes the combined
// runtime AGPL. See ../NOTICE before any distribution.
package liruntime

import (
	"context"
	"sync"

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

	// UnavailableReason is shown verbatim by any caller that asks why the
	// LinkedIn on-device route cannot be connected yet.
	UnavailableReason = "LinkedIn on-device cookie login and encrypted session storage are not implemented"
)

// Runtime holds one unauthenticated linkedingo client. One Runtime per account.
type Runtime struct {
	mu     sync.Mutex
	client *linkedingo.Client
	jar    *linkedingo.StringCookieJar
}

func init() {
	// Match igruntime: keep global zerolog silent so no content-bearing fields leak.
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
	client := linkedingo.NewClient(
		context.Background(),
		linkedingo.NewURN(""),
		jar,
		"",
		"",
		"",
		linkedingo.Handlers{},
	)
	return &Runtime{client: client, jar: jar}
}

// Platform reports the upstream platform identifier ("linkedin").
func (r *Runtime) Platform() string { return "linkedin" }

// State reports authenticated once a cookie jar with JSESSIONID is loaded.
func (r *Runtime) State() string {
	if r.IsAuthenticated() {
		return StateAuthenticated
	}
	return StateUnavailable
}

// LoginAvailable is false until the isolated cookie-login probe lands.
func (r *Runtime) LoginAvailable() bool { return false }

// IsAuthenticated is false without a restored cookie jar containing JSESSIONID.
func (r *Runtime) IsAuthenticated() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.client != nil && r.client.IsLoggedIn()
}

// UnavailableDetail returns the fixed reason LinkedIn stays hidden from Accounts.
func (r *Runtime) UnavailableDetail() string { return UnavailableReason }

// RequiredCookieName is the LinkedIn session cookie upstream treats as logged-in.
func RequiredCookieName() string { return linkedingo.LinkedInCookieJSESSIONID }

// ImportCookieHeader replaces the in-memory jar from a Cookie request header.
// It performs no network I/O. Returns false when the header cannot be parsed or
// lacks JSESSIONID.
func (r *Runtime) ImportCookieHeader(header string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	jar, err := linkedingo.NewJarFromCookieHeader(header)
	if err != nil || jar.GetCookie(linkedingo.LinkedInCookieJSESSIONID) == "" {
		return false
	}
	r.jar = jar
	r.client = linkedingo.NewClient(
		context.Background(),
		linkedingo.NewURN(""),
		jar,
		"",
		"",
		"",
		linkedingo.Handlers{},
	)
	return true
}

// ExportCookiesJSON serializes the current jar for encrypted storage.
// Empty string means no cookies are present.
func (r *Runtime) ExportCookiesJSON() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.jar == nil {
		return ""
	}
	raw, err := r.jar.MarshalJSON()
	if err != nil {
		return ""
	}
	return string(raw)
}

// ImportCookiesJSON restores a jar previously produced by ExportCookiesJSON.
func (r *Runtime) ImportCookiesJSON(jsonCookies string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	jar := linkedingo.NewEmptyStringCookieJar()
	if err := jar.UnmarshalJSON([]byte(jsonCookies)); err != nil {
		return false
	}
	if jar.GetCookie(linkedingo.LinkedInCookieJSESSIONID) == "" {
		return false
	}
	r.jar = jar
	r.client = linkedingo.NewClient(
		context.Background(),
		linkedingo.NewURN(""),
		jar,
		"",
		"",
		"",
		linkedingo.Handlers{},
	)
	return true
}

// BrowserUserAgent is the upstream desktop UA LinkedIn cookies must match.
func BrowserUserAgent() string { return linkedingo.UserAgent }

// PinnedCommit exposes the pinned upstream commit for device tests.
func PinnedCommit() string { return PinnedMautrixLinkedIn }

// PinnedVersion exposes the pinned module version for device tests.
func PinnedVersion() string { return PinnedMautrixLinkedInVersion }
