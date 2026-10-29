// Package msgruntime is the phone-local Facebook Messenger protocol facade inside
// the shared gomobile runtime. It wraps go.mau.fi/mautrix-meta/pkg/messagix.
//
// Probe scope: owner-operated cookie login + sealed in-memory E2EE device state.
// No Accounts UI, no send, no remote bridge, no qualification-gate changes.
//
// Content-free logging: global zerolog is silenced. Methods never log cookies,
// names, message bodies, or response payloads.
//
// License: mautrix-meta is AGPL-3.0-or-later. See ../NOTICE.
package msgruntime

import (
	"context"
	"database/sql"
	"encoding/json"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	zlog "github.com/rs/zerolog/log"
	_ "modernc.org/sqlite"

	"go.mau.fi/mautrix-meta/pkg/messagix"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/table"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
	"go.mau.fi/mautrix-meta/pkg/messagix/useragent"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	waTypes "go.mau.fi/whatsmeow/types"
)

const (
	// PinnedMautrixMeta is the upstream git commit of tag v0.2610.0.
	PinnedMautrixMeta = "mautrix-meta@v0.2610.0"
	// PinnedMautrixMetaVersion is the Go module version in go.mod.
	PinnedMautrixMetaVersion = "v0.2610.0"
	// PinnedWhatsmeowReplace is the production shared-runtime whatsmeow pin.
	PinnedWhatsmeowReplace = "c386243a72ba"

	StateUnavailable   = "unavailable"
	StateAuthenticated = "authenticated"

	// UnavailableReason is shown when Accounts asks why Messenger is hidden.
	UnavailableReason = "Messenger on-device cookie login is only available in the isolated probe"

	CookieXS    = string(cookies.FBCookieXS)
	CookieCUser = string(cookies.FBCookieCUser)
	CookieDatr  = string(cookies.MetaCookieDatr)

	// messenger.com has no mobile web login and desktop UA is blocked in WebView,
	// so owner login runs on m.facebook.com and messagix uses Facebook platform
	// (same xs/c_user/datr cookies, Messenger inbox via facebook.com endpoints).
	LoginURL           = "https://m.facebook.com/login/"
	LoginSuccessRegexp = `^https://(?:m|www)\.facebook\.com/(?:home\.php|messages(?:/.*)?)?/?(?:\?.*)?$`
)

// Runtime holds one Messenger client. One Runtime per account attempt.
type Runtime struct {
	mu                sync.Mutex
	client            *messagix.Client
	jar               *cookies.Cookies
	verifiedUserID    string
	deviceStore       *sql.DB
	deviceContainer   *sqlstore.Container
	devicePath        string
	deviceKey         []byte
	accountKey        string
	memoryURI         string
	generation        uint64
	restoreDump       []byte
	device            *store.Device
	e2ee              *whatsmeow.Client
	e2eeHandlerID     uint32
	e2eeConnectedCh   chan struct{}
	threadCount       int
	readDone          bool
	e2eeConnected     bool
	e2eeEvents        int
	e2eeFBMessages    int
	e2eeUnique        int
	e2eeDuplicates    int
	e2eeLoggedOut     bool
	e2eeUndecryptable int
	e2eeEventKinds    map[string]int
	lsInserts         int
	lsMQTTConnected   bool
	e2eeThreads       int
	inboxActive       int
}

type sessionSnapshot struct {
	SchemaVersion int               `json:"schema_version"`
	Platform      string            `json:"platform"`
	Cookies       map[string]string `json:"cookies"`
	UserID        string            `json:"user_id,omitempty"`
	CachedState   json.RawMessage   `json:"cached_state,omitempty"`
	DevicePath    string            `json:"device_path,omitempty"`
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

// NewRuntime constructs an unauthenticated Messenger client. No network I/O.
func NewRuntime() *Runtime {
	jar := &cookies.Cookies{Platform: types.Facebook}
	jar.UpdateValues(map[cookies.MetaCookieName]string{})
	return &Runtime{
		client: messagix.NewClient(jar, zerolog.Nop(), &messagix.Config{}),
		jar:    jar,
	}
}

func (r *Runtime) Platform() string { return "messenger" }

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

func (r *Runtime) IsAuthenticated() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.verifiedUserID != ""
}

func (r *Runtime) HasSessionCookies() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return jarHasRequiredCookies(r.jar)
}

func (r *Runtime) UnavailableDetail() string { return UnavailableReason }

func RequiredCookieNamesCSV() string {
	return CookieXS + "," + CookieCUser + "," + CookieDatr
}

func BrowserUserAgent() string { return useragent.UserAgent }

func LoginStartURL() string { return LoginURL }

func LoginSuccessURLPattern() string { return LoginSuccessRegexp }

func PinnedCommit() string { return PinnedMautrixMeta }

func PinnedVersion() string { return PinnedMautrixMetaVersion }

func PinnedWhatsmeow() string { return PinnedWhatsmeowReplace }

func (r *Runtime) VerifiedUserID() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.verifiedUserID
}

func jarHasRequiredCookies(jar *cookies.Cookies) bool {
	if jar == nil {
		return false
	}
	return len(jar.GetMissingCookieNames()) == 0 && jar.IsLoggedIn()
}

func parseCookieHeader(header string) (*cookies.Cookies, string) {
	if strings.TrimSpace(header) == "" {
		return nil, "missing_cookie_header"
	}
	values := map[cookies.MetaCookieName]string{}
	for _, part := range strings.Split(header, ";") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		name, value, ok := strings.Cut(part, "=")
		if !ok {
			continue
		}
		name = strings.TrimSpace(name)
		value = strings.TrimSpace(value)
		if name == "" || value == "" {
			continue
		}
		values[cookies.MetaCookieName(name)] = value
	}
	jar := &cookies.Cookies{Platform: types.Facebook}
	jar.UpdateValues(values)
	if !jarHasRequiredCookies(jar) {
		return nil, "missing_required_cookies"
	}
	return jar, ""
}

// ImportOwnerSession installs cookies harvested from the owner-operated WebView.
// Returns a content-free code: empty on success.
func (r *Runtime) ImportOwnerSession(cookieHeader string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.verifiedUserID = ""
	jar, code := parseCookieHeader(cookieHeader)
	if code != "" {
		return code
	}
	r.jar = jar
	r.client = messagix.NewClient(jar, zerolog.Nop(), &messagix.Config{})
	if r.device != nil {
		r.client.SetDevice(r.device)
	}
	return ""
}

// VerifyIdentity loads the Messenger messages page. Success proves authentication.
// Returns JSON: {"ok":true,"user_id":"..."} or {"ok":false,"code":"..."}.
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
	userInfo, table, err := client.LoadMessagesPage(ctx)
	if err != nil {
		return encodeVerify(verifyResult{OK: false, Code: classifyVerifyError(err)})
	}
	threads := 0
	if table != nil {
		threads = len(table.LSDeleteThenInsertThread)
	}
	userID := ""
	if userInfo != nil {
		if id := userInfo.GetFBID(); id != 0 {
			userID = strconv.FormatInt(id, 10)
		}
	}
	if userID == "" && jar != nil {
		if id := jar.GetUserID(); id != 0 {
			userID = strconv.FormatInt(id, 10)
		}
	}
	if userID == "" {
		return encodeVerify(verifyResult{OK: false, Code: "empty_verified_identity"})
	}
	r.mu.Lock()
	r.verifiedUserID = userID
	r.threadCount = threads
	r.readDone = true
	r.mu.Unlock()
	return encodeVerify(verifyResult{OK: true, UserID: userID})
}

// ConversationReadJSON reports the content-free authenticated inbox read from the
// last VerifyIdentity: {"read":true,"threads":N}. No names, snippets, or IDs.
func (r *Runtime) ConversationReadJSON() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	raw, _ := json.Marshal(map[string]any{"read": r.readDone, "threads": r.threadCount})
	return string(raw)
}

// EncryptedDeviceRegistered is true once the E2EE device has a Messenger JID.
func (r *Runtime) EncryptedDeviceRegistered() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.device != nil && r.device.ID != nil
}

// ListenEncrypted connects the prepared E2EE client and listens for up to
// seconds. Receive-only: no sends, no read receipts are issued by MINI.
// Unique inbound IDs are persisted in the sealed device state so a later
// listen after force-stop can report duplicates without re-counting.
func (r *Runtime) ListenEncrypted(seconds int) string {
	r.mu.Lock()
	e2ee := r.e2ee
	registered := r.device != nil && r.device.ID != nil
	r.e2eeConnected, r.e2eeEvents, r.e2eeFBMessages = false, 0, 0
	r.e2eeUnique, r.e2eeDuplicates, r.e2eeLoggedOut = 0, 0, false
	r.e2eeUndecryptable, r.lsInserts, r.e2eeThreads, r.inboxActive = 0, 0, 0, 0
	r.lsMQTTConnected = false
	r.e2eeEventKinds = map[string]int{}
	r.e2eeConnectedCh = make(chan struct{}, 1)
	connectedCh := r.e2eeConnectedCh
	client := r.client
	if e2ee != nil {
		if r.e2eeHandlerID != 0 {
			e2ee.RemoveEventHandler(r.e2eeHandlerID)
		}
		r.e2eeHandlerID = e2ee.AddEventHandlerWithSuccessStatus(r.handleEncryptedEvent)
	}
	r.mu.Unlock()
	out := func(code string) string {
		r.mu.Lock()
		defer r.mu.Unlock()
		_ = r.persistLocked()
		return encodeListenResult(r, registered, code)
	}
	if e2ee == nil {
		return out("encrypted_client_missing")
	}
	if !registered {
		return out("encrypted_device_unregistered")
	}
	listenStart := time.Now().UnixMilli()
	mqttCtx, mqttCancel := context.WithCancel(context.Background())
	defer mqttCancel()
	if client != nil {
		client.SetEventHandler(r.handleMetaEvent)
		go func() { _ = client.Connect(mqttCtx) }()
	}
	if err := e2ee.Connect(); err != nil {
		return out("connect_failed")
	}
	if seconds < 1 {
		seconds = 1
	}
	deadline := time.After(time.Duration(seconds) * time.Second)
	select {
	case <-connectedCh:
		_ = e2ee.SendPresence(context.Background(), waTypes.PresenceAvailable)
		<-deadline
	case <-deadline:
	}
	e2ee.Disconnect()
	mqttCancel()
	if client != nil {
		client.Disconnect()
		r.snapshotInbox(listenStart)
	}
	return out("")
}

func (r *Runtime) handleMetaEvent(ctx context.Context, evt any) {
	switch v := evt.(type) {
	case *messagix.ConnectedEvent, *messagix.ReconnectedEvent:
		r.mu.Lock()
		r.lsMQTTConnected = true
		r.mu.Unlock()
	case *table.LSTable:
		n := len(v.LSInsertMessage) + len(v.LSDeleteThenInsertMessage) + len(v.LSUpsertMessage)
		if n == 0 {
			return
		}
		r.mu.Lock()
		r.lsInserts += n
		r.mu.Unlock()
	}
}

func (r *Runtime) snapshotInbox(listenStart int64) {
	r.mu.Lock()
	client := r.client
	r.mu.Unlock()
	if client == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	_, tbl, err := client.LoadMessagesPage(ctx)
	if err != nil || tbl == nil {
		return
	}
	e2eeThreads, active := 0, 0
	for _, th := range tbl.LSDeleteThenInsertThread {
		if th == nil {
			continue
		}
		if th.ThreadType.IsWhatsApp() {
			e2eeThreads++
		}
		if th.LastActivityTimestampMs >= listenStart {
			active++
		}
	}
	r.mu.Lock()
	r.e2eeThreads = e2eeThreads
	r.inboxActive = active
	r.threadCount = len(tbl.LSDeleteThenInsertThread)
	r.mu.Unlock()
}
func classifyVerifyError(err error) string {
	msg := strings.ToLower(err.Error())
	switch {
	case strings.Contains(msg, "token"), strings.Contains(msg, "cookie"), strings.Contains(msg, "invalidated"):
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

// ExportSessionJSON serializes cookies for encrypted storage.
func (r *Runtime) ExportSessionJSON() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.jar == nil || !jarHasRequiredCookies(r.jar) {
		return ""
	}
	all := r.jar.GetAll()
	cookieMap := make(map[string]string, len(all))
	for k, v := range all {
		cookieMap[string(k)] = v
	}
	var cached json.RawMessage
	if r.client != nil {
		if raw, err := r.client.DumpState(); err == nil && len(raw) > 0 {
			cached = raw
		}
	}
	snap := sessionSnapshot{
		SchemaVersion: 1,
		Platform:      types.Facebook.String(),
		Cookies:       cookieMap,
		UserID:        r.verifiedUserID,
		CachedState:   cached,
		DevicePath:    r.devicePath,
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
	if snap.SchemaVersion != 0 && snap.SchemaVersion != 1 {
		return "unsupported_session_schema"
	}
	if len(snap.Cookies) == 0 {
		return "session_incomplete"
	}
	values := map[cookies.MetaCookieName]string{}
	for k, v := range snap.Cookies {
		values[cookies.MetaCookieName(k)] = v
	}
	jar := &cookies.Cookies{Platform: types.Facebook}
	jar.UpdateValues(values)
	if !jarHasRequiredCookies(jar) {
		return "missing_required_cookies"
	}
	r.jar = jar
	r.client = messagix.NewClient(jar, zerolog.Nop(), &messagix.Config{})
	if len(snap.CachedState) > 0 {
		_ = r.client.LoadState(snap.CachedState)
	}
	if r.device != nil {
		r.client.SetDevice(r.device)
	}
	if snap.DevicePath != "" {
		r.devicePath = snap.DevicePath
	}
	return ""
}

// ClearSession drops in-memory cookies, identity, and E2EE client handles.
// The on-disk encrypted device DB path is left untouched.
func (r *Runtime) ClearSession() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.e2ee != nil {
		r.e2ee.Disconnect()
		r.e2ee = nil
	}
	jar := &cookies.Cookies{Platform: types.Facebook}
	jar.UpdateValues(map[cookies.MetaCookieName]string{})
	r.jar = jar
	r.verifiedUserID = ""
	r.client = messagix.NewClient(jar, zerolog.Nop(), &messagix.Config{})
	if r.device != nil {
		r.client.SetDevice(r.device)
	}
}
