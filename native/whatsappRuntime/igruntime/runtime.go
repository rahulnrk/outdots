// Package igruntime is the phone-local Instagram DM protocol facade inside the
// shared gomobile runtime. It wraps go.mau.fi/mautrix-meta/pkg/instameow.
//
// Scope of this slice: the real protocol client is linked and constructible.
// No login, cookie import, session restore, network connection or message
// access is exposed. Those require an Android authentication design and a
// SQLCipher/Keystore session snapshot path equivalent to waruntime, and they
// stay unavailable until that exists.
//
// Content-free logging: the client is built with zerolog.Nop. Nothing here
// writes to disk.
//
// License: mautrix-meta is AGPL-3.0-or-later. Its LICENSE.exceptions grant
// rights only to Beeper and Element. Linking this package makes the combined
// runtime AGPL. See ../NOTICE before any distribution.
package igruntime

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/rs/zerolog"
	zlog "github.com/rs/zerolog/log"
	"go.mau.fi/util/exhttp"

	"go.mau.fi/mautrix-meta/pkg/instameow"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
)

const (
	// PinnedMautrixMeta is the upstream git commit of tag v0.2610.0.
	PinnedMautrixMeta = "3c16226686c71af64df294df0803791685064e0b"
	// PinnedMautrixMetaVersion is the Go module version in go.mod.
	PinnedMautrixMetaVersion = "v0.2610.0"

	StateUnavailable = "unavailable"
	// StateAuthenticated is reported once an upstream-verified session is loaded
	// in memory (after login or restore).
	StateAuthenticated = "authenticated"

	// UnavailableReason is shown verbatim by any caller that asks why the
	// Instagram on-device route cannot be connected yet.
	UnavailableReason = "Instagram on-device login and encrypted session storage are not implemented"
)

// Runtime holds one unauthenticated instameow client. One Runtime per account.
type Runtime struct {
	mu        sync.Mutex
	client    *instameow.Client
	accountID string
	seqID     int64
	seqIDTS   time.Time
}

// Upstream messagix/lightspeed and messagix/table log through the zerolog
// global logger (imported as badGlobalLog), bypassing the per-client logger.
// Some of those calls attach decoded payloads (e.g. "block_data"). Silence the
// global logger and disable all levels so nothing content-bearing reaches
// stderr or any other sink.
func init() {
	zlog.Logger = zerolog.Nop()
	zerolog.SetGlobalLevel(zerolog.Disabled)
}

// GlobalLoggingDisabled reports whether the content-free logging guard holds.
func GlobalLoggingDisabled() bool {
	return zerolog.GlobalLevel() == zerolog.Disabled && zlog.Logger.GetLevel() == zerolog.Disabled
}

// NewRuntime constructs the real protocol client with empty Instagram cookies.
// It performs no network I/O.
func NewRuntime() *Runtime {
	return &Runtime{client: newClient(nil, nil)}
}

func newClient(session *types.InstagramNativeSession, cookieValues map[string]string) *instameow.Client {
	c := &cookies.Cookies{Platform: types.Instagram}
	values := make(map[cookies.MetaCookieName]string, len(cookieValues))
	for name, value := range cookieValues {
		values[cookies.MetaCookieName(name)] = value
	}
	c.UpdateValues(values)
	client := instameow.NewClient(instameow.ClientParams{
		Cookies:       c,
		Log:           zerolog.Nop(),
		Settings:      exhttp.SensibleClientSettings,
		DisableTyping: true,
		NativeSession: session,
	})
	return client
}

// Platform reports the upstream platform identifier ("instagram").
func (r *Runtime) Platform() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.client.GetPlatform().String()
}

// State reports authenticated once an upstream-verified native session is in
// memory, otherwise unavailable. The in-memory session comes only from a real
// login or an encrypted-store restore.
func (r *Runtime) State() string {
	if r.IsAuthenticated() {
		return StateAuthenticated
	}
	return StateUnavailable
}

// LoginAvailable reports that the real owner-operated login flow exists. The
// actual sign-in is driven through a LoginController on a dedicated process.
func (r *Runtime) LoginAvailable() bool { return true }

// NewLoginController starts a real upstream login attempt bound to accountID.
func (r *Runtime) NewLoginController(accountID string) *LoginController {
	return NewLoginController(accountID)
}

// IsAuthenticated reports whether a verified native session is loaded in memory.
func (r *Runtime) IsAuthenticated() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.client != nil && (r.client.IsAuthenticated() || r.client.GetInstagramNativeSession() != nil)
}

// VerifyAuthenticatedRead performs the harmless authenticated inbox read against
// the restored session and returns the upstream-verified user ID. It binds the
// in-memory client to that verified identity and fails if the restored session,
// the inbox viewer, and the session cookie disagree. It performs network I/O.
func (r *Runtime) VerifyAuthenticatedRead() (string, error) {
	r.mu.Lock()
	client := r.client
	r.mu.Unlock()
	if client == nil {
		return "", fmt.Errorf("Instagram runtime is not initialized")
	}
	nativeSession := client.GetInstagramNativeSession()
	if nativeSession == nil || nativeSession.UserID == "" {
		return "", fmt.Errorf("Instagram runtime has no authenticated native session")
	}
	viewer, _, err := client.LoadIndex(context.Background())
	if err != nil {
		return "", fmt.Errorf("Instagram authenticated read failed: %w", classifyReadError(err))
	}
	if viewer == nil || viewer.GetUserId() == "" {
		return "", fmt.Errorf("Instagram authenticated read returned no viewer")
	}
	dsUserID := client.GetCookies().Get(cookies.IGCookieDSUserID)
	if nativeSession.UserID != viewer.GetUserId() || nativeSession.UserID != dsUserID {
		return "", fmt.Errorf("Instagram verified identity mismatch")
	}
	return viewer.GetUserId(), nil
}

// ExportSessionEnvelope snapshots only authenticated post-login state. The
// caller must hand the returned opaque bytes directly to the Android
// SQLCipher/Keystore store and must not expose them to ordinary UI state.
func (r *Runtime) ExportSessionEnvelope(accountID string) ([]byte, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.client == nil {
		return nil, fmt.Errorf("Instagram runtime is not initialized")
	}
	nativeSession := r.client.GetInstagramNativeSession()
	if nativeSession == nil {
		return nil, fmt.Errorf("Instagram runtime has no authenticated native session")
	}
	if accountID == "" {
		return nil, fmt.Errorf("Instagram account ID is empty")
	}
	if r.accountID != "" && r.accountID != accountID {
		return nil, fmt.Errorf("Instagram runtime is bound to account %q", r.accountID)
	}
	cookieValues := make(map[string]string)
	for name, value := range r.client.GetCookies().GetAll() {
		if _, ok := allowedInstagramSessionCookies[string(name)]; ok && value != "" {
			cookieValues[string(name)] = value
		}
	}
	cachedState, err := r.client.DumpState()
	if err != nil {
		return nil, fmt.Errorf("dump Instagram cached state: %w", err)
	}
	envelope := &InstagramSessionEnvelope{
		SchemaVersion: SessionEnvelopeSchemaVersion,
		AccountID:     accountID,
		Cookies:       cookieValues,
		NativeSession: nativeSession,
		CachedState:   cachedState,
		SeqID:         r.seqID,
		SeqIDTS:       r.seqIDTS,
	}
	return EncodeSessionEnvelope(envelope)
}

// RestoreSessionEnvelope replaces the in-memory client with a session restored
// from the Android encrypted store. It performs no network I/O.
func (r *Runtime) RestoreSessionEnvelope(accountID string, encoded []byte) error {
	envelope, err := DecodeSessionEnvelope(encoded)
	if err != nil {
		return err
	}
	if envelope.AccountID != accountID {
		return fmt.Errorf("Instagram session account mismatch")
	}
	client := newClient(envelope.NativeSession, envelope.Cookies)
	if len(envelope.CachedState) > 0 {
		if err := loadCachedState(client, envelope.CachedState); err != nil {
			return fmt.Errorf("load Instagram cached state: %w", err)
		}
	}
	client.SetSeqID(envelope.SeqID, envelope.SeqIDTS)
	r.mu.Lock()
	defer r.mu.Unlock()
	r.client = client
	r.accountID = accountID
	r.seqID = envelope.SeqID
	r.seqIDTS = envelope.SeqIDTS
	return nil
}

func loadCachedState(client *instameow.Client, state []byte) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("cached state rejected: %v", recovered)
		}
	}()
	return client.LoadState(state)
}

// SetSequence records the last protocol sequence that may be resumed. The
// sequence is metadata only and is not a login secret.
func (r *Runtime) SetSequence(seqID int64, timestampUnix int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.seqID = seqID
	r.seqIDTS = time.Unix(timestampUnix, 0).UTC()
	if r.client != nil {
		r.client.SetSeqID(r.seqID, r.seqIDTS)
	}
}

// Logout removes the in-memory Instagram session. The Android store must call
// its account-scoped clear method separately so another account is untouched.
func (r *Runtime) Logout() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.client = newClient(nil, nil)
	r.accountID = ""
	r.seqID = 0
	r.seqIDTS = time.Time{}
}
