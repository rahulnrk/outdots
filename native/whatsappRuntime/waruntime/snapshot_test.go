package waruntime

import (
	"os"
	"path/filepath"
	"testing"

	"go.mau.fi/whatsmeow/proto/waE2E"
)

func TestSnapshotRoundTripAndNoPlaintextFiles(t *testing.T) {
	dir := t.TempDir()
	r, err := NewRuntime(dir)
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	defer r.Close()
	snap, err := r.Snapshot()
	if err != nil {
		t.Fatalf("snapshot: %v", err)
	}
	if len(snap) == 0 {
		t.Fatal("empty snapshot")
	}
	if err := r.Restore(snap); err != nil {
		t.Fatalf("restore: %v", err)
	}
	if files := r.UnencryptedFiles(); files != "" {
		t.Fatalf("plaintext session files: %s", files)
	}
	r.mu.Lock()
	r.keepMediaLocked("m1", &waE2E.Message{ImageMessage: &waE2E.ImageMessage{JPEGThumbnail: []byte{1, 2}}})
	r.mu.Unlock()
	snap2, err := r.Snapshot()
	if err != nil {
		t.Fatal(err)
	}
	if err := r.Restore(snap2); err != nil {
		t.Fatal(err)
	}
	if len(r.MediaThumbnail("m1")) != 2 {
		t.Fatal("media ref lost across restore")
	}
	if err := os.WriteFile(filepath.Join(dir, "leak.sqlite"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if r.UnencryptedFiles() == "" {
		t.Fatal("expected to detect planted sqlite file")
	}
}

func TestStartPhonePairingRejectsLocalAndShortNumbers(t *testing.T) {
	r, err := NewRuntime(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	if err := r.StartPhonePairing("0123456789"); err == nil {
		t.Fatal("expected leading-0 rejection")
	}
	if err := r.StartPhonePairing("+12"); err == nil {
		t.Fatal("expected short rejection")
	}
	if got := digitsOnly("+1 (555) 123-4567"); got != "15551234567" {
		t.Fatalf("digits: %s", got)
	}
}
