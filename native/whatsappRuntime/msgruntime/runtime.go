// Package msgruntime is the phone-local Facebook Messenger protocol facade inside
// the shared gomobile runtime. It wraps go.mau.fi/mautrix-meta/pkg/messagix.
//
// Probe scope: owner-operated cookie login + encrypted E2EE device-store handling.
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
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	zlog "github.com/rs/zerolog/log"
	_ "modernc.org/sqlite"

	"go.mau.fi/mautrix-meta/pkg/messagix"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
	"go.mau.fi/mautrix-meta/pkg/messagix/useragent"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	waLog "go.mau.fi/whatsmeow/util/log"
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

	LoginURL           = "https://www.messenger.com/login"
	LoginSuccessRegexp = `^https://www\.messenger\.com/(?:t/[0-9]+/?)?(?:\?.*)?$`
)

// Runtime holds one Messenger client. One Runtime per account attempt.
type Runtime struct {
	mu             sync.Mutex
	client         *messagix.Client
	jar            *cookies.Cookies
	verifiedUserID string
	deviceStore    *sql.DB
	deviceContainer *sqlstore.Container
	devicePath     string
	device         *store.Device
	e2ee           *whatsmeow.Client
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
	jar := &cookies.Cookies{Platform: types.Messenger}
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
	jar := &cookies.Cookies{Platform: types.Messenger}
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
	userInfo, _, err := client.LoadMessagesPage(ctx)
	if err != nil {
		return encodeVerify(verifyResult{OK: false, Code: classifyVerifyError(err)})
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
	r.mu.Unlock()
	return encodeVerify(verifyResult{OK: true, UserID: userID})
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
		Platform:      types.Messenger.String(),
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
	jar := &cookies.Cookies{Platform: types.Messenger}
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
	jar := &cookies.Cookies{Platform: types.Messenger}
	jar.UpdateValues(map[cookies.MetaCookieName]string{})
	r.jar = jar
	r.verifiedUserID = ""
	r.client = messagix.NewClient(jar, zerolog.Nop(), &messagix.Config{})
	if r.device != nil {
		r.client.SetDevice(r.device)
	}
}

// OpenEncryptedDeviceStore opens or creates a whatsmeow SQL device DB for Messenger
// Armadillo E2EE. dbPath must be an app-private file path. No network I/O.
func (r *Runtime) OpenEncryptedDeviceStore(dbPath string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	path := strings.TrimSpace(dbPath)
	if path == "" {
		return "missing_device_path"
	}
	r.closeDeviceLocked()
	var dsn string
	var abs string
	if strings.HasPrefix(path, "file:") {
		dsn = path
		abs = path
	} else {
		abs = filepath.Clean(path)
		dsn = "file:" + filepath.ToSlash(abs) + "?_pragma=foreign_keys(1)"
	}
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return "device_store_open_failed"
	}
	db.SetMaxOpenConns(1)
	container := sqlstore.NewWithDB(db, "sqlite3", waLog.Noop)
	if err := container.Upgrade(context.Background()); err != nil {
		_ = db.Close()
		return "device_store_upgrade_failed"
	}
	device, err := container.GetFirstDevice(context.Background())
	if err != nil {
		_ = container.Close()
		return "device_store_device_failed"
	}
	r.deviceStore = db
	r.deviceContainer = container
	r.devicePath = abs
	r.device = device
	if r.client != nil {
		r.client.SetDevice(device)
	}
	return ""
}

// PrepareEncryptedClient wires messagix PrepareE2EEClient against the open device.
// Requires OpenEncryptedDeviceStore first. No network I/O.
func (r *Runtime) PrepareEncryptedClient() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.client == nil || r.device == nil {
		return "encrypted_device_missing"
	}
	r.client.SetDevice(r.device)
	e2ee, err := r.client.PrepareE2EEClient()
	if err != nil {
		return "prepare_e2ee_failed"
	}
	r.e2ee = e2ee
	return ""
}

// RegisterEncryptedDevice registers the local device for Messenger E2EE after a
// verified cookie session. Performs network I/O. No message send.
func (r *Runtime) RegisterEncryptedDevice() string {
	if code := r.PrepareEncryptedClient(); code != "" {
		return code
	}
	r.mu.Lock()
	client := r.client
	userID := r.verifiedUserID
	device := r.device
	r.mu.Unlock()
	if client == nil || device == nil {
		return "encrypted_device_missing"
	}
	if userID == "" {
		return "not_authenticated"
	}
	fbid, err := strconv.ParseInt(userID, 10, 64)
	if err != nil || fbid == 0 {
		return "invalid_verified_identity"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := client.RegisterE2EE(ctx, fbid); err != nil {
		return classifyVerifyError(err)
	}
	if device.ID != nil {
		if err := device.Save(ctx); err != nil {
			return "device_save_failed"
		}
	}
	return ""
}

func (r *Runtime) HasEncryptedDevice() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.device != nil
}

func (r *Runtime) EncryptedDevicePath() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.devicePath
}

func (r *Runtime) HasPreparedEncryptedClient() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.e2ee != nil
}

// Close releases E2EE client and device-store handles. Safe to call repeatedly.
func (r *Runtime) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.closeDeviceLocked()
}

func (r *Runtime) closeDeviceLocked() {
	if r.e2ee != nil {
		r.e2ee.Disconnect()
		r.e2ee = nil
	}
	if r.deviceContainer != nil {
		_ = r.deviceContainer.Close()
		r.deviceContainer = nil
	}
	// container.Close already closes the underlying *sql.DB.
	r.deviceStore = nil
	r.device = nil
	r.devicePath = ""
}
