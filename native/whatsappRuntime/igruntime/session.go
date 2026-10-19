package igruntime

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
)

const (
	SessionEnvelopeSchemaVersion = 1
	sessionEnvelopeMagic         = "MINI-IG-SESSION\x00"
	sessionEnvelopeHeaderSize    = len(sessionEnvelopeMagic) + 4
	sessionEnvelopeDigestSize    = sha256.Size
)

// InstagramSessionEnvelope is the post-authentication state that may be handed
// to the Android SQLCipher store. It deliberately has no password, challenge
// URL, CAPTCHA result, or raw login request/response field.
//
// NativeSession contains the upstream authorization and the installation-scoped
// InstagramLoginDevice, including the USDID private key. The bytes returned by
// EncodeSessionEnvelope are opaque to ordinary UI and must only be stored in the
// Keystore-wrapped SQLCipher database.
type InstagramSessionEnvelope struct {
	SchemaVersion int                           `json:"schema_version"`
	AccountID     string                        `json:"account_id"`
	Cookies       map[string]string             `json:"cookies"`
	NativeSession *types.InstagramNativeSession `json:"native_session"`
	CachedState   json.RawMessage               `json:"cached_state,omitempty"`
	SeqID         int64                         `json:"seq_id,omitempty"`
	SeqIDTS       time.Time                     `json:"seq_id_ts,omitempty"`
}

var allowedInstagramSessionCookies = map[string]struct{}{
	string(cookies.IGCookieSessionID): {},
	string(cookies.IGCookieCSRFToken): {},
	string(cookies.IGCookieDSUserID):  {},
	string(cookies.IGCookieMachineID): {},
	string(cookies.IGCookieDeviceID):  {},
	string(cookies.IGCookieRUR):       {},
	string(cookies.IGCookieSHBID):     {},
	string(cookies.IGCookieSHBTS):     {},
}

var forbiddenSessionKeys = map[string]struct{}{
	"password":          {},
	"passwd":            {},
	"login_password":    {},
	"captcha_token":     {},
	"challenge_url":     {},
	"challenge_payload": {},
	"request_body":      {},
	"response_body":     {},
}

// Validate checks the storage contract without contacting Instagram.
func (e *InstagramSessionEnvelope) Validate() error {
	if e == nil {
		return errors.New("Instagram session envelope is nil")
	}
	if e.SchemaVersion != SessionEnvelopeSchemaVersion {
		return fmt.Errorf("unsupported Instagram session schema %d", e.SchemaVersion)
	}
	if strings.TrimSpace(e.AccountID) == "" {
		return errors.New("Instagram session account ID is empty")
	}
	if e.NativeSession == nil {
		return errors.New("Instagram native session is missing")
	}
	if e.NativeSession.Authorization == "" || e.NativeSession.UserID == "" {
		return errors.New("Instagram native authorization or user ID is missing")
	}
	for name, value := range e.Cookies {
		if _, ok := allowedInstagramSessionCookies[name]; !ok {
			return fmt.Errorf("unsupported Instagram cookie %q", name)
		}
		if value == "" {
			return fmt.Errorf("empty Instagram cookie %q", name)
		}
	}
	if e.Cookies[string(cookies.IGCookieDSUserID)] != "" &&
		e.Cookies[string(cookies.IGCookieDSUserID)] != e.NativeSession.UserID {
		return errors.New("Instagram cookie user ID does not match native session")
	}
	if len(e.CachedState) > 0 {
		var value any
		if err := json.Unmarshal(e.CachedState, &value); err != nil {
			return fmt.Errorf("invalid cached state: %w", err)
		}
		if key, ok := findForbiddenSessionKey(value); ok {
			return fmt.Errorf("cached state contains forbidden field %q", key)
		}
	}
	return nil
}

// EncodeSessionEnvelope produces a versioned, checksummed opaque record. The
// record is not an encryption boundary. SQLCipher, with its Keystore-wrapped
// database key, is the encryption boundary on Android.
func EncodeSessionEnvelope(envelope *InstagramSessionEnvelope) ([]byte, error) {
	if err := envelope.Validate(); err != nil {
		return nil, err
	}
	payload, err := json.Marshal(envelope)
	if err != nil {
		return nil, fmt.Errorf("marshal Instagram session envelope: %w", err)
	}
	if uint64(len(payload)) > uint64(^uint32(0)) {
		return nil, errors.New("Instagram session envelope is too large")
	}
	result := make([]byte, sessionEnvelopeHeaderSize+len(payload)+sessionEnvelopeDigestSize)
	copy(result, sessionEnvelopeMagic)
	binary.BigEndian.PutUint32(result[len(sessionEnvelopeMagic):sessionEnvelopeHeaderSize], uint32(len(payload)))
	copy(result[sessionEnvelopeHeaderSize:], payload)
	digest := sha256.Sum256(payload)
	copy(result[sessionEnvelopeHeaderSize+len(payload):], digest[:])
	return result, nil
}

// DecodeSessionEnvelope verifies the framing and checksum before decoding any
// session material. It rejects malformed and unsupported records.
func DecodeSessionEnvelope(encoded []byte) (*InstagramSessionEnvelope, error) {
	if len(encoded) < sessionEnvelopeHeaderSize+sessionEnvelopeDigestSize {
		return nil, errors.New("Instagram session envelope is truncated")
	}
	if !bytes.Equal(encoded[:len(sessionEnvelopeMagic)], []byte(sessionEnvelopeMagic)) {
		return nil, errors.New("invalid Instagram session envelope magic")
	}
	payloadLength := int(binary.BigEndian.Uint32(encoded[len(sessionEnvelopeMagic):sessionEnvelopeHeaderSize]))
	end := sessionEnvelopeHeaderSize + payloadLength
	if payloadLength == 0 || end+sessionEnvelopeDigestSize != len(encoded) {
		return nil, errors.New("invalid Instagram session envelope length")
	}
	payload := encoded[sessionEnvelopeHeaderSize:end]
	want := sha256.Sum256(payload)
	if !bytes.Equal(want[:], encoded[end:]) {
		return nil, errors.New("Instagram session envelope checksum mismatch")
	}
	var envelope InstagramSessionEnvelope
	if err := json.Unmarshal(payload, &envelope); err != nil {
		return nil, fmt.Errorf("decode Instagram session envelope: %w", err)
	}
	if err := envelope.Validate(); err != nil {
		return nil, err
	}
	return &envelope, nil
}

func findForbiddenSessionKey(value any) (string, bool) {
	switch current := value.(type) {
	case map[string]any:
		for key, child := range current {
			if _, forbidden := forbiddenSessionKeys[strings.ToLower(key)]; forbidden {
				return key, true
			}
			if found, ok := findForbiddenSessionKey(child); ok {
				return found, true
			}
		}
	case []any:
		for _, child := range current {
			if found, ok := findForbiddenSessionKey(child); ok {
				return found, true
			}
		}
	}
	return "", false
}
