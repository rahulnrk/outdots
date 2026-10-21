package msgruntime

import (
	"context"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types/events"
	"go.mau.fi/whatsmeow/util/keys"
	waLog "go.mau.fi/whatsmeow/util/log"
)

const (
	miniPendingIdentitySQL = `
CREATE TABLE IF NOT EXISTS mini_pending_identity (
	id INTEGER PRIMARY KEY CHECK (id = 1),
	noise_priv BLOB NOT NULL,
	identity_priv BLOB NOT NULL,
	signed_prekey_priv BLOB NOT NULL,
	signed_prekey_id INTEGER NOT NULL,
	signed_prekey_sig BLOB NOT NULL,
	registration_id INTEGER NOT NULL,
	adv_secret BLOB NOT NULL,
	facebook_uuid TEXT NOT NULL DEFAULT ''
);`
	miniInboundSeenSQL = `
CREATE TABLE IF NOT EXISTS mini_inbound_seen (
	id_hash TEXT PRIMARY KEY NOT NULL,
	seen_at INTEGER NOT NULL
);`
)

func parseDeviceKeyHex(keyHex string) ([]byte, string) {
	raw, err := hex.DecodeString(strings.TrimSpace(keyHex))
	if err != nil || len(raw) != sealedKeySize {
		return nil, "invalid_device_key"
	}
	return raw, ""
}

func sealedPathFor(path string) string {
	clean := filepath.Clean(path)
	lower := strings.ToLower(clean)
	switch {
	case strings.HasSuffix(lower, ".sealed"):
		return clean
	case strings.HasSuffix(lower, ".db"):
		return clean[:len(clean)-len(".db")] + ".sealed"
	default:
		return clean + ".sealed"
	}
}

func legacyPlaintextPath(sealed string) string {
	if strings.HasSuffix(strings.ToLower(sealed), ".sealed") {
		return sealed[:len(sealed)-len(".sealed")] + ".db"
	}
	return sealed + ".db"
}

// OpenEncryptedDeviceStore opens the account-scoped Messenger E2EE device in
// memory and loads durable state from a sealed file at path. keyHex is 32
// random bytes hex-encoded. accountKey is bound as GCM AAD (facebook|<id>).
//
// Existing sealed state is restored. A legacy plaintext sqlite file is migrated
// once into the sealed file and then wiped. Missing state creates a new
// unregistered device. A present but unreadable sealed file is never replaced
// with a newly generated identity.
func (r *Runtime) OpenEncryptedDeviceStore(path, accountKey, keyHex string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.openEncryptedDeviceStoreLocked(path, accountKey, keyHex)
}

func (r *Runtime) openEncryptedDeviceStoreLocked(path, accountKey, keyHex string) string {
	path = strings.TrimSpace(path)
	accountKey = strings.TrimSpace(accountKey)
	if path == "" {
		return "missing_device_path"
	}
	if strings.HasPrefix(path, "file:") {
		return "device_path_must_be_filesystem"
	}
	if accountKey == "" {
		return "missing_account_key"
	}
	key, code := parseDeviceKeyHex(keyHex)
	if code != "" {
		return code
	}
	sealed := sealedPathFor(path)
	r.closeDeviceLocked()

	db, uri, err := openMemoryDB()
	if err != nil {
		return "device_store_open_failed"
	}

	ctx := context.Background()
	var generation uint64
	var dump []byte
	restored := false
	if blob, err := os.ReadFile(sealed); err == nil {
		gen, image, err := openDeviceState(key, accountKey, blob)
		if err != nil {
			db.Close()
			return classifySealedOpenError(err)
		}
		generation = gen
		dump = image
		restored = true
	} else if !os.IsNotExist(err) {
		db.Close()
		return "device_state_read_failed"
	} else {
		legacy := legacyPlaintextPath(sealed)
		if info, statErr := os.Stat(legacy); statErr == nil && !info.IsDir() && isPlaintextSQLite(legacy) {
			image, err := readPlaintextLegacyImage(legacy)
			if err != nil {
				db.Close()
				return "legacy_device_read_failed"
			}
			r.deviceStore = db
			r.memoryURI = uri
			r.deviceKey = append([]byte(nil), key...)
			r.accountKey = accountKey
			r.devicePath = sealed
			r.generation = 0
			r.restoreDump = image
			if code := r.finishOpenLocked(ctx, true); code != "" {
				r.closeDeviceLocked()
				return code
			}
			if code := r.persistLocked(); code != "" {
				// Keep the plaintext original if the first sealed write fails.
				r.closeDeviceLocked()
				return code
			}
			_ = removePlaintextLegacy(legacy)
			return ""
		}
	}

	r.deviceStore = db
	r.memoryURI = uri
	r.deviceKey = append([]byte(nil), key...)
	r.accountKey = accountKey
	r.devicePath = sealed
	r.generation = generation
	r.restoreDump = dump
	if code := r.finishOpenLocked(ctx, restored); code != "" {
		r.closeDeviceLocked()
		return code
	}
	return ""
}

func classifySealedOpenError(err error) string {
	switch err {
	case errSealedAuth:
		return "device_state_auth_failed"
	case errSealedMagic, errSealedTooShort:
		return "device_state_corrupt"
	case errSealedFormat:
		return "device_state_unsupported"
	default:
		return "device_state_open_failed"
	}
}

func (r *Runtime) finishOpenLocked(ctx context.Context, restored bool) string {
	container := sqlstore.NewWithDB(r.deviceStore, "sqlite3", waLog.Noop)
	if err := container.Upgrade(ctx); err != nil {
		return "device_store_upgrade_failed"
	}
	if _, err := r.deviceStore.ExecContext(ctx, miniPendingIdentitySQL); err != nil {
		_ = container.Close()
		return "device_store_upgrade_failed"
	}
	if _, err := r.deviceStore.ExecContext(ctx, miniInboundSeenSQL); err != nil {
		_ = container.Close()
		return "device_store_upgrade_failed"
	}
	if len(r.restoreDump) > 0 {
		if err := restoreSQL(r.deviceStore, r.restoreDump); err != nil {
			_ = container.Close()
			return "device_state_restore_failed"
		}
		r.restoreDump = nil
	}
	device, err := loadDevice(ctx, container, r.deviceStore)
	if err != nil {
		_ = container.Close()
		return "device_store_device_failed"
	}
	r.deviceContainer = container
	r.device = device
	if r.client != nil {
		r.client.SetDevice(device)
	}
	if restored && device.ID == nil {
		if err := savePendingIdentity(ctx, r.deviceStore, device); err != nil {
			return "device_identity_persist_failed"
		}
	}
	if !restored {
		if err := savePendingIdentity(ctx, r.deviceStore, device); err != nil {
			return "device_identity_persist_failed"
		}
		if code := r.persistLocked(); code != "" {
			return code
		}
	}
	return ""
}

func loadDevice(ctx context.Context, container *sqlstore.Container, db *sql.DB) (*store.Device, error) {
	devices, err := container.GetAllDevices(ctx)
	if err != nil {
		return nil, err
	}
	if len(devices) > 0 {
		return devices[0], nil
	}
	pending, err := loadPendingIdentity(ctx, db)
	if err != nil {
		return nil, err
	}
	if pending != nil {
		pending.Log = waLog.Noop
		pending.Container = container
		return pending, nil
	}
	return container.NewDevice(), nil
}

func savePendingIdentity(ctx context.Context, db *sql.DB, device *store.Device) error {
	if device == nil || device.NoiseKey == nil || device.IdentityKey == nil || device.SignedPreKey == nil {
		return nil
	}
	fb := ""
	if device.FacebookUUID != uuid.Nil {
		fb = device.FacebookUUID.String()
	}
	_, err := db.ExecContext(ctx, `
INSERT INTO mini_pending_identity (
	id, noise_priv, identity_priv, signed_prekey_priv, signed_prekey_id,
	signed_prekey_sig, registration_id, adv_secret, facebook_uuid
) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(id) DO UPDATE SET
	noise_priv=excluded.noise_priv,
	identity_priv=excluded.identity_priv,
	signed_prekey_priv=excluded.signed_prekey_priv,
	signed_prekey_id=excluded.signed_prekey_id,
	signed_prekey_sig=excluded.signed_prekey_sig,
	registration_id=excluded.registration_id,
	adv_secret=excluded.adv_secret,
	facebook_uuid=excluded.facebook_uuid
`,
		device.NoiseKey.Priv[:],
		device.IdentityKey.Priv[:],
		device.SignedPreKey.Priv[:],
		int64(device.SignedPreKey.KeyID),
		device.SignedPreKey.Signature[:],
		int64(device.RegistrationID),
		device.AdvSecretKey,
		fb,
	)
	return err
}

func loadPendingIdentity(ctx context.Context, db *sql.DB) (*store.Device, error) {
	var noise, ident, spk, sig, adv []byte
	var spkID, regID int64
	var fb string
	err := db.QueryRowContext(ctx, `
SELECT noise_priv, identity_priv, signed_prekey_priv, signed_prekey_id,
       signed_prekey_sig, registration_id, adv_secret, facebook_uuid
FROM mini_pending_identity WHERE id = 1`).Scan(
		&noise, &ident, &spk, &spkID, &sig, &regID, &adv, &fb,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "no such table") {
			return nil, nil
		}
		return nil, err
	}
	var sigArr [64]byte
	copy(sigArr[:], sig)
	device := &store.Device{
		NoiseKey:       keyPairFromPriv(noise),
		IdentityKey:    keyPairFromPriv(ident),
		RegistrationID: uint32(regID),
		AdvSecretKey:   append([]byte(nil), adv...),
	}
	device.SignedPreKey = &keys.PreKey{
		KeyPair:   *keyPairFromPriv(spk),
		KeyID:     uint32(spkID),
		Signature: &sigArr,
	}
	if fb != "" {
		if parsed, err := uuid.Parse(fb); err == nil {
			device.FacebookUUID = parsed
		}
	}
	return device, nil
}

func keyPairFromPriv(priv []byte) *keys.KeyPair {
	var arr [32]byte
	copy(arr[:], priv)
	return keys.NewKeyPairFromPrivateKey(arr)
}

// PersistEncryptedDevice writes the in-memory device store as a sealed file.
func (r *Runtime) PersistEncryptedDevice() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.persistLocked()
}

func (r *Runtime) persistLocked() string {
	if r.deviceStore == nil || len(r.deviceKey) != sealedKeySize || r.devicePath == "" {
		return "encrypted_device_missing"
	}
	ctx := context.Background()
	if r.device != nil && r.device.ID == nil {
		if err := savePendingIdentity(ctx, r.deviceStore, r.device); err != nil {
			return "device_identity_persist_failed"
		}
	}
	image, err := dumpSQL(r.deviceStore)
	if err != nil {
		return "device_state_serialize_failed"
	}
	next := r.generation + 1
	blob, err := sealDeviceState(r.deviceKey, r.accountKey, next, image)
	if err != nil {
		return "device_state_seal_failed"
	}
	if err := writeFileAtomic(r.devicePath, blob); err != nil {
		return "device_state_write_failed"
	}
	r.generation = next
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
	e2ee.EnableDecryptedEventBuffer = true
	e2ee.SynchronousAck = true
	e2ee.EnableAutoReconnect = false
	r.e2ee = e2ee
	r.e2eeHandlerID = 0
	return ""
}

// RegisterEncryptedDevice registers the local device for Messenger E2EE after a
// verified cookie session. Already-registered identities are kept and persisted.
// Performs network I/O only when the device has no Messenger JID. No message send.
func (r *Runtime) RegisterEncryptedDevice() string {
	if code := r.PrepareEncryptedClient(); code != "" {
		return code
	}
	r.mu.Lock()
	client := r.client
	userID := r.verifiedUserID
	device := r.device
	already := device != nil && device.ID != nil
	r.mu.Unlock()
	if client == nil || device == nil {
		return "encrypted_device_missing"
	}
	if already {
		r.mu.Lock()
		defer r.mu.Unlock()
		return r.persistLocked()
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
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.persistLocked()
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

func (r *Runtime) SealedDevicePresent() bool {
	r.mu.Lock()
	path := r.devicePath
	r.mu.Unlock()
	if path == "" {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && info.Size() > 0 && !isPlaintextSQLite(path)
}

func (r *Runtime) LastPersistGeneration() int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return int64(r.generation)
}

// DeviceIdentityJSON is content-free: fingerprints only, never private keys.
func (r *Runtime) DeviceIdentityJSON() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := map[string]any{
		"registered": false,
		"generation": r.generation,
		"identity":   "",
		"noise":      "",
		"jid_user":   "",
		"jid_device": 0,
	}
	if r.device != nil {
		if r.device.IdentityKey != nil {
			out["identity"] = identityFingerprint(r.device.IdentityKey.Pub)
		}
		if r.device.NoiseKey != nil {
			out["noise"] = identityFingerprint(r.device.NoiseKey.Pub)
		}
		if r.device.ID != nil {
			out["registered"] = true
			out["jid_user"] = r.device.ID.User
			out["jid_device"] = r.device.ID.Device
		}
	}
	raw, _ := json.Marshal(out)
	return string(raw)
}

func (r *Runtime) PlaintextDeviceArtifacts() string {
	r.mu.Lock()
	path := r.devicePath
	r.mu.Unlock()
	if path == "" {
		return ""
	}
	return PlaintextDeviceArtifacts(filepath.Dir(path))
}

// Close releases E2EE client and device-store handles. Safe to call repeatedly.
func (r *Runtime) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.closeDeviceLocked()
}

func (r *Runtime) closeDeviceLocked() {
	if r.e2ee != nil {
		if r.e2eeHandlerID != 0 {
			r.e2ee.RemoveEventHandler(r.e2eeHandlerID)
			r.e2eeHandlerID = 0
		}
		r.e2ee.Disconnect()
		r.e2ee = nil
	}
	if r.deviceContainer != nil {
		_ = r.deviceContainer.Close()
		r.deviceContainer = nil
	} else if r.deviceStore != nil {
		_ = r.deviceStore.Close()
	}
	r.deviceStore = nil
	r.device = nil
	r.devicePath = ""
	r.accountKey = ""
	r.memoryURI = ""
	r.generation = 0
	r.restoreDump = nil
	if len(r.deviceKey) > 0 {
		clear(r.deviceKey)
		r.deviceKey = nil
	}
}

func (r *Runtime) handleEncryptedEvent(evt any) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.e2eeEvents++
	switch v := evt.(type) {
	case *events.Connected:
		r.e2eeConnected = true
		if r.e2eeConnectedCh != nil {
			select {
			case r.e2eeConnectedCh <- struct{}{}:
			default:
			}
		}
		_ = r.persistLocked()
	case *events.FBMessage:
		r.noteInboundLocked(v.Info.ID, v.Info.Timestamp)
	case *events.LoggedOut:
		r.e2eeLoggedOut = true
	}
	return true
}

func (r *Runtime) noteInboundLocked(id string, ts time.Time) {
	r.e2eeFBMessages++
	if id == "" || r.deviceStore == nil {
		return
	}
	hash := hashMessageID(id)
	var exists int
	_ = r.deviceStore.QueryRow(`SELECT COUNT(1) FROM mini_inbound_seen WHERE id_hash = ?`, hash).Scan(&exists)
	if exists > 0 {
		r.e2eeDuplicates++
		return
	}
	seenAt := time.Now().UnixMilli()
	if !ts.IsZero() {
		seenAt = ts.UnixMilli()
	}
	if _, err := r.deviceStore.Exec(
		`INSERT INTO mini_inbound_seen (id_hash, seen_at) VALUES (?, ?)`,
		hash, seenAt,
	); err != nil {
		return
	}
	r.e2eeUnique++
	_ = r.persistLocked()
}

func (r *Runtime) seenTotalLocked() int {
	if r.deviceStore == nil {
		return 0
	}
	var n int
	_ = r.deviceStore.QueryRow(`SELECT COUNT(1) FROM mini_inbound_seen`).Scan(&n)
	return n
}

func encodeListenResult(r *Runtime, registered bool, code string) string {
	raw, _ := json.Marshal(map[string]any{
		"registered":  registered,
		"connected":   r.e2eeConnected,
		"events":      r.e2eeEvents,
		"fb_messages": r.e2eeFBMessages,
		"unique":      r.e2eeUnique,
		"duplicates":  r.e2eeDuplicates,
		"seen_total":  r.seenTotalLocked(),
		"logged_out":  r.e2eeLoggedOut,
		"generation":  r.generation,
		"code":        code,
	})
	return string(raw)
}