// Package waruntime is a gomobile-friendly WhatsApp companion-device runtime.
// Session SQL lives in memory. Callers persist Snapshot() bytes with Android
// Keystore/SQLCipher. This package must not write a plaintext session database.
package waruntime

const (
	PinnedWhatsmeow = "c386243a72bacad080ba2938b37e1e4e0f1ca3b2"
	StateIdle       = "idle"
	StateQr         = "qr"
	StateLinked     = "linked"
	StateError      = "error"
	StateCancelled  = "cancelled"
)
