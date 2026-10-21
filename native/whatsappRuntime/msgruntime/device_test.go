package msgruntime

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"go.mau.fi/whatsmeow/proto/waAdv"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	waLog "go.mau.fi/whatsmeow/util/log"
)

func testKeyHex(t *testing.T) string {
	t.Helper()
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(raw[:])
}

func openTestDevice(t *testing.T, dir, account, keyHex string) *Runtime {
	t.Helper()
	r := NewRuntime()
	t.Cleanup(r.Close)
	path := filepath.Join(dir, "device.sealed")
	if code := r.OpenEncryptedDeviceStore(path, account, keyHex); code != "" {
		t.Fatalf("OpenEncryptedDeviceStore=%q", code)
	}
	return r
}

func identityMap(t *testing.T, r *Runtime) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal([]byte(r.DeviceIdentityJSON()), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func dummyAccount() *waAdv.ADVSignedDeviceIdentity {
	return &waAdv.ADVSignedDeviceIdentity{
		Details:             []byte{},
		AccountSignatureKey: make([]byte, 32),
		AccountSignature:    make([]byte, 64),
		DeviceSignature:     make([]byte, 64),
	}
}

func adoptRegisteredDevice(t *testing.T, r *Runtime, user string, deviceID uint16) {
	t.Helper()
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.device == nil {
		t.Fatal("no device")
	}
	jid := types.JID{User: user, Device: deviceID, Server: types.MessengerServer}
	r.device.ID = &jid
	r.device.Account = dummyAccount()
	if err := r.device.Save(context.Background()); err != nil {
		t.Fatal(err)
	}
	if code := r.persistLocked(); code != "" {
		t.Fatalf("persist after adopt: %s", code)
	}
}

func TestEncryptedDevicePersistsAndReopens(t *testing.T) {
	dir := t.TempDir()
	key := testKeyHex(t)
	account := "facebook|111"
	r1 := openTestDevice(t, dir, account, key)
	if !r1.HasEncryptedDevice() || !r1.SealedDevicePresent() {
		t.Fatal("expected sealed device after open")
	}
	if r1.LastPersistGeneration() < 1 {
		t.Fatal("expected persist generation")
	}
	id1 := identityMap(t, r1)
	if id1["identity"] == "" || id1["noise"] == "" {
		t.Fatalf("missing fingerprints: %v", id1)
	}
	if id1["registered"] != false {
		t.Fatal("new device must not be registered")
	}
	sessionAddr := "1.msgr"
	sessionBlob := []byte{9, 8, 7, 6, 5}
	adoptRegisteredDevice(t, r1, "111", 7)
	if err := r1.device.Sessions.PutSession(context.Background(), sessionAddr, sessionBlob); err != nil {
		t.Fatal(err)
	}
	if code := r1.PersistEncryptedDevice(); code != "" {
		t.Fatalf("persist=%q", code)
	}
	id1 = identityMap(t, r1)
	path := r1.EncryptedDevicePath()
	r1.Close()

	r2 := NewRuntime()
	t.Cleanup(r2.Close)
	if code := r2.OpenEncryptedDeviceStore(path, account, key); code != "" {
		t.Fatalf("reopen=%q", code)
	}
	id2 := identityMap(t, r2)
	if id2["identity"] != id1["identity"] || id2["noise"] != id1["noise"] {
		t.Fatalf("identity changed on reopen: %v vs %v", id1, id2)
	}
	if id2["registered"] != true || id2["jid_user"] != "111" {
		t.Fatalf("registration lost: %v", id2)
	}
	got, err := r2.device.Sessions.GetSession(context.Background(), sessionAddr)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(sessionBlob) {
		t.Fatalf("session mismatch %v", got)
	}
	if artifacts := r2.PlaintextDeviceArtifacts(); artifacts != "" {
		t.Fatalf("plaintext artifacts: %s", artifacts)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(raw) < 16 || string(raw[:16]) == "SQLite format 3\x00" {
		t.Fatal("sealed file looks like plaintext sqlite")
	}
}

func TestDeviceIdentityNotRegeneratedOnReopen(t *testing.T) {
	dir := t.TempDir()
	key := testKeyHex(t)
	account := "facebook|222"
	r1 := openTestDevice(t, dir, account, key)
	id1 := identityMap(t, r1)
	path := r1.EncryptedDevicePath()
	r1.Close()

	r2 := NewRuntime()
	t.Cleanup(r2.Close)
	if code := r2.OpenEncryptedDeviceStore(path, account, key); code != "" {
		t.Fatalf("reopen=%q", code)
	}
	id2 := identityMap(t, r2)
	if id2["identity"] != id1["identity"] || id2["noise"] != id1["noise"] {
		t.Fatalf("silent regeneration: %v vs %v", id1, id2)
	}
	if r2.EncryptedDeviceRegistered() {
		t.Fatal("unregistered device became registered")
	}
}

func TestPerAccountIsolation(t *testing.T) {
	dir := t.TempDir()
	keyA, keyB := testKeyHex(t), testKeyHex(t)
	accA, accB := "facebook|aaa", "facebook|bbb"
	a := openTestDevice(t, dir, accA, keyA)
	adoptRegisteredDevice(t, a, "aaa", 1)
	idA := identityMap(t, a)
	pathA := a.EncryptedDevicePath()
	a.Close()

	bDir := filepath.Join(dir, "b")
	if err := os.MkdirAll(bDir, 0o700); err != nil {
		t.Fatal(err)
	}
	b := openTestDevice(t, bDir, accB, keyB)
	idB := identityMap(t, b)
	if idB["identity"] == idA["identity"] {
		t.Fatal("independent accounts must not share identity keys")
	}
	b.Close()

	wrongKey := NewRuntime()
	t.Cleanup(wrongKey.Close)
	if code := wrongKey.OpenEncryptedDeviceStore(pathA, accA, keyB); code != "device_state_auth_failed" {
		t.Fatalf("wrong key code=%q", code)
	}
	if wrongKey.HasEncryptedDevice() {
		t.Fatal("failed open must not install a device")
	}

	wrongAccount := NewRuntime()
	t.Cleanup(wrongAccount.Close)
	if code := wrongAccount.OpenEncryptedDeviceStore(pathA, accB, keyA); code != "device_state_auth_failed" {
		t.Fatalf("wrong account code=%q", code)
	}

	copied := filepath.Join(bDir, "stolen.sealed")
	data, err := os.ReadFile(pathA)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(copied, data, 0o600); err != nil {
		t.Fatal(err)
	}
	stolen := NewRuntime()
	t.Cleanup(stolen.Close)
	if code := stolen.OpenEncryptedDeviceStore(copied, accB, keyB); code != "device_state_auth_failed" {
		t.Fatalf("copied file with other account code=%q", code)
	}
}

func TestCorruptionAndWrongKeyDoNotRegenerate(t *testing.T) {
	dir := t.TempDir()
	key := testKeyHex(t)
	account := "facebook|333"
	r1 := openTestDevice(t, dir, account, key)
	id1 := identityMap(t, r1)
	path := r1.EncryptedDevicePath()
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	r1.Close()

	corrupted := append([]byte(nil), before...)
	corrupted[len(corrupted)-1] ^= 0xff
	if err := os.WriteFile(path, corrupted, 0o600); err != nil {
		t.Fatal(err)
	}
	r2 := NewRuntime()
	t.Cleanup(r2.Close)
	code := r2.OpenEncryptedDeviceStore(path, account, key)
	if code != "device_state_auth_failed" && code != "device_state_corrupt" {
		t.Fatalf("corrupt open code=%q", code)
	}
	if r2.HasEncryptedDevice() {
		t.Fatal("corrupt open must not create a device")
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(corrupted) {
		t.Fatal("corrupt file was overwritten")
	}

	if err := os.WriteFile(path, before, 0o600); err != nil {
		t.Fatal(err)
	}
	r3 := NewRuntime()
	t.Cleanup(r3.Close)
	if code := r3.OpenEncryptedDeviceStore(path, account, key); code != "" {
		t.Fatalf("restore original=%q", code)
	}
	id3 := identityMap(t, r3)
	if id3["identity"] != id1["identity"] {
		t.Fatalf("identity lost after corrupt attempt: %v vs %v", id1, id3)
	}
}

func TestInterruptedPersistKeepsPreviousState(t *testing.T) {
	dir := t.TempDir()
	key := testKeyHex(t)
	account := "facebook|444"
	r1 := openTestDevice(t, dir, account, key)
	id1 := identityMap(t, r1)
	gen1 := r1.LastPersistGeneration()
	path := r1.EncryptedDevicePath()
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}

	testHookBeforeRename = func(tmpPath string) error {
		return errors.New("simulated crash")
	}
	t.Cleanup(func() { testHookBeforeRename = nil })
	if code := r1.PersistEncryptedDevice(); code != "device_state_write_failed" {
		t.Fatalf("interrupted persist code=%q", code)
	}
	testHookBeforeRename = nil
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(before) {
		t.Fatal("interrupted persist replaced the previous sealed file")
	}
	if r1.LastPersistGeneration() != gen1 {
		t.Fatal("generation advanced despite failed write")
	}
	r1.Close()

	r2 := NewRuntime()
	t.Cleanup(r2.Close)
	if code := r2.OpenEncryptedDeviceStore(path, account, key); code != "" {
		t.Fatalf("reopen after interrupt=%q", code)
	}
	id2 := identityMap(t, r2)
	if id2["identity"] != id1["identity"] {
		t.Fatalf("identity lost after interrupt: %v vs %v", id1, id2)
	}
}

func TestNoPlaintextDeviceArtifacts(t *testing.T) {
	dir := t.TempDir()
	key := testKeyHex(t)
	r := openTestDevice(t, dir, "facebook|555", key)
	adoptRegisteredDevice(t, r, "555", 2)
	if artifacts := r.PlaintextDeviceArtifacts(); artifacts != "" {
		t.Fatalf("artifacts after persist: %s", artifacts)
	}
	if artifacts := PlaintextDeviceArtifacts(dir); artifacts != "" {
		t.Fatalf("dir artifacts: %s", artifacts)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		name := strings.ToLower(e.Name())
		for _, suffix := range []string{".db", ".sqlite", ".sqlite3", "-journal", "-wal", "-shm"} {
			if strings.HasSuffix(name, suffix) {
				t.Fatalf("plaintext sidecar %s", e.Name())
			}
		}
		if isPlaintextSQLite(filepath.Join(dir, e.Name())) {
			t.Fatalf("sqlite header in %s", e.Name())
		}
	}
	if !r.SealedDevicePresent() {
		t.Fatal("missing sealed file")
	}
}

func TestLegacyPlaintextDeviceMigratesWithoutRegeneration(t *testing.T) {
	dir := t.TempDir()
	legacy := filepath.Join(dir, "device.db")
	fp, session := writeLegacyPlaintextDevice(t, legacy)
	key := testKeyHex(t)
	account := "facebook|123"
	r := NewRuntime()
	t.Cleanup(r.Close)
	if code := r.OpenEncryptedDeviceStore(legacy, account, key); code != "" {
		t.Fatalf("migrate open=%q", code)
	}
	id := identityMap(t, r)
	if id["identity"] != fp {
		t.Fatalf("legacy identity not preserved: got %v want %s", id["identity"], fp)
	}
	if id["registered"] != true || id["jid_user"] != "123" {
		t.Fatalf("legacy registration lost: %v", id)
	}
	got, err := r.device.Sessions.GetSession(context.Background(), "legacy-session")
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(session) {
		t.Fatalf("legacy session lost: %v", got)
	}
	if _, err := os.Stat(legacy); !os.IsNotExist(err) {
		t.Fatal("plaintext legacy db still present")
	}
	for _, sidecar := range []string{"-journal", "-wal", "-shm"} {
		if _, err := os.Stat(legacy + sidecar); err == nil {
			t.Fatalf("legacy sidecar still present: %s", sidecar)
		}
	}
	if artifacts := r.PlaintextDeviceArtifacts(); artifacts != "" {
		t.Fatalf("artifacts after migration: %s", artifacts)
	}
	if !r.SealedDevicePresent() {
		t.Fatal("sealed file missing after migration")
	}
}

func writeLegacyPlaintextDevice(t *testing.T, path string) (identityFP string, session []byte) {
	t.Helper()
	dsn := "file:" + filepath.ToSlash(path) + "?_pragma=foreign_keys(1)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	container := sqlstore.NewWithDB(db, "sqlite3", waLog.Noop)
	if err := container.Upgrade(context.Background()); err != nil {
		t.Fatal(err)
	}
	device := container.NewDevice()
	identityFP = identityFingerprint(device.IdentityKey.Pub)
	jid := types.JID{User: "123", Device: 4, Server: types.MessengerServer}
	device.ID = &jid
	device.Account = dummyAccount()
	if err := device.Save(context.Background()); err != nil {
		t.Fatal(err)
	}
	session = []byte{1, 2, 3, 9}
	if err := device.Sessions.PutSession(context.Background(), "legacy-session", session); err != nil {
		t.Fatal(err)
	}
	if err := container.Close(); err != nil {
		t.Fatal(err)
	}
	if !isPlaintextSQLite(path) {
		t.Fatal("expected plaintext sqlite fixture")
	}
	return identityFP, session
}

func TestInboundDedupSurvivesReopen(t *testing.T) {
	dir := t.TempDir()
	key := testKeyHex(t)
	account := "facebook|666"
	r1 := openTestDevice(t, dir, account, key)
	r1.mu.Lock()
	r1.noteInboundLocked("mid-1", time.Now())
	r1.noteInboundLocked("mid-1", time.Now())
	if r1.e2eeUnique != 1 || r1.e2eeDuplicates != 1 {
		r1.mu.Unlock()
		t.Fatalf("unique=%d dup=%d", r1.e2eeUnique, r1.e2eeDuplicates)
	}
	r1.mu.Unlock()
	path := r1.EncryptedDevicePath()
	r1.Close()

	r2 := NewRuntime()
	t.Cleanup(r2.Close)
	if code := r2.OpenEncryptedDeviceStore(path, account, key); code != "" {
		t.Fatalf("reopen=%q", code)
	}
	r2.mu.Lock()
	r2.noteInboundLocked("mid-1", time.Now())
	r2.noteInboundLocked("mid-2", time.Now())
	unique, dup, total := r2.e2eeUnique, r2.e2eeDuplicates, r2.seenTotalLocked()
	r2.mu.Unlock()
	if unique != 1 || dup != 1 || total != 2 {
		t.Fatalf("after reopen unique=%d dup=%d total=%d", unique, dup, total)
	}
}

func TestRejectsMemoryURIAndShortKey(t *testing.T) {
	r := NewRuntime()
	t.Cleanup(r.Close)
	if code := r.OpenEncryptedDeviceStore("file:memory?mode=memory", "facebook|1", testKeyHex(t)); code != "device_path_must_be_filesystem" {
		t.Fatalf("memory uri code=%q", code)
	}
	if code := r.OpenEncryptedDeviceStore(filepath.Join(t.TempDir(), "x.sealed"), "facebook|1", "abcd"); code != "invalid_device_key" {
		t.Fatalf("short key code=%q", code)
	}
}
