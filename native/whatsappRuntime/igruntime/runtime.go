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
	"sync"

	"github.com/rs/zerolog"
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

	// UnavailableReason is shown verbatim by any caller that asks why the
	// Instagram on-device route cannot be connected yet.
	UnavailableReason = "Instagram on-device login and encrypted session storage are not implemented"
)

// Runtime holds one unauthenticated instameow client. One Runtime per account.
type Runtime struct {
	mu     sync.Mutex
	client *instameow.Client
}

// NewRuntime constructs the real protocol client with empty Instagram cookies.
// It performs no network I/O.
func NewRuntime() *Runtime {
	c := &cookies.Cookies{Platform: types.Instagram}
	c.UpdateValues(nil)
	return &Runtime{client: instameow.NewClient(instameow.ClientParams{
		Cookies:       c,
		Log:           zerolog.Nop(),
		Settings:      exhttp.SensibleClientSettings,
		DisableTyping: true,
	})}
}

// Platform reports the upstream platform identifier ("instagram").
func (r *Runtime) Platform() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.client.GetPlatform().String()
}

// State is always StateUnavailable in this slice.
func (r *Runtime) State() string { return StateUnavailable }

// LoginAvailable is false until a real Android login flow exists.
func (r *Runtime) LoginAvailable() bool { return false }

// IsAuthenticated reports the client's own view. Always false here because no
// credential can be supplied through this facade.
func (r *Runtime) IsAuthenticated() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.client.IsAuthenticated() || r.client.GetInstagramNativeSession() != nil
}
