package msgruntime

// Sealed Messenger E2EE device state.
//
// The whatsmeow device store (identity, noise and signed-prekey private keys,
// one-time prekeys, Signal sessions, sender keys, app-state keys and the
// decrypted-event buffer) never touches disk as SQLite. It lives in a named
// in-memory SQLite database for the lifetime of a Runtime. Durable copies are
// a JSON dump of every table (the WhatsApp waruntime snapshot approach)
// sealed with AES-256-GCM:
//
//	file = magic(12) | nonce(12) | GCM(key, nonce, payload, aad)
//	aad  = magic | "|" | accountKey
//	payload = formatVersion(u32 BE) | generation(u64 BE) | dumpSQL JSON
//
// The 32-byte key is supplied by the Android host (random per account, wrapped
// by an Android Keystore AES-GCM key). The account key is bound as AAD so a file
// copied between accounts fails authentication even if keys were reused.
//
// Writes are atomic: tmp file, fsync, rename over the previous file, fsync dir.
// An interrupted write leaves the previous sealed file intact. Unreadable,
// tampered or wrong-key files are reported and never overwritten or replaced
// with a freshly generated device.

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
)

const (
	sealedFormatVersion = 1
	sealedKeySize       = 32
	sealedNonceSize     = 12
	sealedTmpSuffix     = ".tmp"
)

var sealedMagic = []byte("MINIMSGRDEV1")

var (
	errSealedTooShort = errors.New("sealed device state truncated")
	errSealedMagic    = errors.New("sealed device state header mismatch")
	errSealedAuth     = errors.New("sealed device state failed authentication")
	errSealedFormat   = errors.New("sealed device state unsupported format")
)

// testHookBeforeRename lets tests simulate a crash after the temporary file is
// fully written but before it replaces the previous sealed state.
var testHookBeforeRename func(tmpPath string) error

func sealedAAD(accountKey string) []byte {
	aad := make([]byte, 0, len(sealedMagic)+1+len(accountKey))
	aad = append(aad, sealedMagic...)
	aad = append(aad, '|')
	return append(aad, accountKey...)
}

func sealDeviceState(key []byte, accountKey string, generation uint64, image []byte) ([]byte, error) {
	if len(key) != sealedKeySize {
		return nil, fmt.Errorf("device state key must be %d bytes", sealedKeySize)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, sealedNonceSize)
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	payload := make([]byte, 12+len(image))
	binary.BigEndian.PutUint32(payload[0:4], sealedFormatVersion)
	binary.BigEndian.PutUint64(payload[4:12], generation)
	copy(payload[12:], image)
	out := make([]byte, 0, len(sealedMagic)+sealedNonceSize+len(payload)+gcm.Overhead())
	out = append(out, sealedMagic...)
	out = append(out, nonce...)
	out = gcm.Seal(out, nonce, payload, sealedAAD(accountKey))
	clear(payload)
	return out, nil
}

func openDeviceState(key []byte, accountKey string, blob []byte) (uint64, []byte, error) {
	if len(key) != sealedKeySize {
		return 0, nil, fmt.Errorf("device state key must be %d bytes", sealedKeySize)
	}
	if len(blob) < len(sealedMagic)+sealedNonceSize+16+12 {
		return 0, nil, errSealedTooShort
	}
	if string(blob[:len(sealedMagic)]) != string(sealedMagic) {
		return 0, nil, errSealedMagic
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return 0, nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return 0, nil, err
	}
	nonce := blob[len(sealedMagic) : len(sealedMagic)+sealedNonceSize]
	payload, err := gcm.Open(nil, nonce, blob[len(sealedMagic)+sealedNonceSize:], sealedAAD(accountKey))
	if err != nil {
		return 0, nil, errSealedAuth
	}
	if binary.BigEndian.Uint32(payload[0:4]) != sealedFormatVersion {
		return 0, nil, errSealedFormat
	}
	return binary.BigEndian.Uint64(payload[4:12]), payload[12:], nil
}

// writeFileAtomic replaces path with data so that a crash at any point leaves
// either the previous complete file or the new complete file.
func writeFileAtomic(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp := path + sealedTmpSuffix
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	if testHookBeforeRename != nil {
		if err := testHookBeforeRename(tmp); err != nil {
			return err
		}
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return err
	}
	if d, err := os.Open(dir); err == nil {
		_ = d.Sync() // best effort; unsupported for directories on Windows
		d.Close()
	}
	return nil
}

var memoryDBSeq atomic.Uint64

// openMemoryDB opens a uniquely named shared-cache in-memory SQLite database
// and returns it with its URI. No file, journal, WAL or temp file is created.
func openMemoryDB() (*sql.DB, string, error) {
	var nonce [6]byte
	_, _ = rand.Read(nonce[:])
	name := fmt.Sprintf("minimsgr-%d-%s", memoryDBSeq.Add(1), hex.EncodeToString(nonce[:]))
	dsn := "file:" + name + "?mode=memory&cache=shared&_pragma=foreign_keys(1)&_pragma=temp_store(MEMORY)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, "", err
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	db.SetConnMaxLifetime(0)
	db.SetConnMaxIdleTime(0)
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, "", err
	}
	return db, dsn, nil
}

// readPlaintextLegacyImage dumps a legacy plaintext device database. Used only
// to migrate pre-sealed builds into the sealed snapshot format.
func readPlaintextLegacyImage(path string) ([]byte, error) {
	dsn := "file:" + filepath.ToSlash(filepath.Clean(path)) + "?mode=ro&_pragma=temp_store(MEMORY)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	return dumpSQL(db)
}

var plaintextSidecars = []string{"", "-journal", "-wal", "-shm"}

func removePlaintextLegacy(path string) error {
	var firstErr error
	for _, suffix := range plaintextSidecars {
		p := path + suffix
		if _, err := os.Stat(p); err == nil {
			if f, err := os.OpenFile(p, os.O_WRONLY, 0); err == nil {
				if info, err := f.Stat(); err == nil && info.Size() > 0 {
					_, _ = f.WriteAt(make([]byte, info.Size()), 0)
					_ = f.Sync()
				}
				f.Close()
			}
			if err := os.Remove(p); err != nil && firstErr == nil {
				firstErr = err
			}
		}
	}
	return firstErr
}

// PlaintextDeviceArtifacts lists SQLite database, journal, WAL and SHM files
// under dir. A sealed-only Messenger device directory returns "".
func PlaintextDeviceArtifacts(dir string) string {
	dir = strings.TrimSpace(dir)
	if dir == "" {
		return ""
	}
	var found []string
	_ = filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		name := strings.ToLower(info.Name())
		for _, suffix := range []string{".db", ".sqlite", ".sqlite3", "-journal", "-wal", "-shm"} {
			if strings.HasSuffix(name, suffix) {
				found = append(found, info.Name())
				return nil
			}
		}
		if isPlaintextSQLite(path) {
			found = append(found, info.Name())
		}
		return nil
	})
	return strings.Join(found, "\n")
}

func isPlaintextSQLite(path string) bool {
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	header := make([]byte, 16)
	n, _ := f.Read(header)
	return n == 16 && string(header) == "SQLite format 3\x00"
}

func identityFingerprint(pub *[32]byte) string {
	if pub == nil {
		return ""
	}
	sum := sha256.Sum256(pub[:])
	return hex.EncodeToString(sum[:4])
}

func hashMessageID(id string) string {
	sum := sha256.Sum256([]byte("mini-msgr-seen|" + id))
	return hex.EncodeToString(sum[:16])
}
