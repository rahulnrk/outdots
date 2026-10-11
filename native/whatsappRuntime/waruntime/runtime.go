package waruntime

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"

	"go.mau.fi/whatsmeow"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

// Runtime is the JNI-exported WhatsApp companion client.
type Runtime struct {
	mu         sync.Mutex
	db         *sql.DB
	container  *sqlstore.Container
	device     *store.Device
	client     *whatsmeow.Client
	cancelPair context.CancelFunc
	state      string
	qr         string
	qrExpiry   int64
	pairCode   string
	lastErr    string
	inbound    []inboundMessage
	chats      map[string][]inboundMessage
	names      map[string]string
	media      map[string]*waE2E.Message
	dirty      bool
	watchDir   string
}

type inboundMessage struct {
	ID     string `json:"id"`
	Chat   string `json:"chat"`
	Sender string `json:"sender"`
	Name   string `json:"name"`
	Title  string `json:"title"`
	Body   string `json:"body"`
	TS     int64  `json:"ts"`
	Out    bool   `json:"out"`
}

// NewRuntime builds an empty in-memory session. watchDir is scanned for accidental
// plaintext sqlite files; pass the Android app-specific whatsapp directory.
func NewRuntime(watchDir string) (*Runtime, error) {
	r := &Runtime{
		state:    StateIdle,
		watchDir: watchDir,
		chats:    make(map[string][]inboundMessage),
		names:    make(map[string]string),
	}
	if err := r.openMemory(); err != nil {
		return nil, err
	}
	return r, nil
}

func (r *Runtime) openMemory() error {
	db, err := sql.Open("sqlite", "file:miniwa?mode=memory&cache=shared&_pragma=foreign_keys(1)")
	if err != nil {
		return err
	}
	if _, err := db.Exec("PRAGMA foreign_keys = ON"); err != nil {
		db.Close()
		return err
	}
	log := waLog.Noop
	container := sqlstore.NewWithDB(db, "sqlite3", log)
	if err := container.Upgrade(context.Background()); err != nil {
		db.Close()
		return err
	}
	device, err := container.GetFirstDevice(context.Background())
	if err != nil {
		container.Close()
		return err
	}
	r.db = db
	r.container = container
	r.device = device
	r.client = whatsmeow.NewClient(device, log)
	r.client.AddEventHandler(r.onEvent)
	return nil
}

func (r *Runtime) resetClient() {
	if r.client != nil {
		r.client.Disconnect()
	}
	if r.container != nil {
		_ = r.container.Close()
	}
	r.db = nil
	r.container = nil
	r.device = nil
	r.client = nil
}

// Version returns the pinned whatsmeow git revision.
func (r *Runtime) Version() string { return PinnedWhatsmeow }

func (r *Runtime) PairingState() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.state
}

func (r *Runtime) QrPayload() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.qr
}

func (r *Runtime) QrExpiresAtMillis() int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.qrExpiry
}

func (r *Runtime) PairingCode() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.pairCode
}

func (r *Runtime) LastError() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.lastErr
}

func (r *Runtime) LinkedPhone() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return linkedPhone(r.device)
}

func (r *Runtime) IsLoggedIn() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.client != nil && r.client.IsLoggedIn()
}

func (r *Runtime) Dirty() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.dirty
}

func (r *Runtime) MarkClean() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.dirty = false
}

// StartPairing begins QR linking. Poll PairingState/QrPayload until linked or error.
func (r *Runtime) StartPairing() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.client == nil {
		return errors.New("runtime closed")
	}
	if r.client.Store.ID != nil {
		return errors.New("already linked")
	}
	r.cancelPairLocked()
	ctx, cancel := context.WithCancel(context.Background())
	r.cancelPair = cancel
	qrChan, err := r.client.GetQRChannel(ctx)
	if err != nil {
		cancel()
		r.cancelPair = nil
		return err
	}
	if err := r.client.Connect(); err != nil {
		cancel()
		r.cancelPair = nil
		return err
	}
	r.state = StateQr
	r.lastErr = ""
	r.qr = ""
	r.pairCode = ""
	go r.consumeQR(qrChan)
	return nil
}

// StartPhonePairing requests an 8-character code for WhatsApp "Link with phone number".
// phone may include + or spaces; it is reduced to international digits (no leading 0).
func (r *Runtime) StartPhonePairing(phone string) error {
	phone = digitsOnly(phone)
	if len(phone) <= 6 {
		return errors.New("phone too short")
	}
	if strings.HasPrefix(phone, "0") {
		return errors.New("use international number, not a leading 0")
	}

	r.mu.Lock()
	if r.client == nil {
		r.mu.Unlock()
		return errors.New("runtime closed")
	}
	if r.client.Store.ID != nil {
		r.mu.Unlock()
		return errors.New("already linked")
	}
	r.cancelPairLocked()
	if r.client.IsConnected() {
		r.client.Disconnect()
	}
	ctx, cancel := context.WithCancel(context.Background())
	r.cancelPair = cancel
	client := r.client
	r.state = StateCode
	r.pairCode = ""
	r.qr = ""
	r.lastErr = ""
	r.mu.Unlock()

	qrChan, err := client.GetQRChannel(ctx)
	if err != nil {
		r.setPairError(err)
		return err
	}
	if err := client.Connect(); err != nil {
		r.setPairError(err)
		return err
	}
	select {
	case evt, ok := <-qrChan:
		if !ok {
			err = errors.New("whatsapp_connect_closed")
			r.setPairError(err)
			return err
		}
		if evt.Error != nil {
			r.setPairError(evt.Error)
			return evt.Error
		}
		go r.consumeQR(qrChan)
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(20 * time.Second):
		err = errors.New("whatsapp_connect_timeout")
		r.setPairError(err)
		return err
	}

	code, err := client.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		r.setPairError(err)
		return err
	}
	r.mu.Lock()
	r.pairCode = code
	r.state = StateCode
	r.lastErr = ""
	r.mu.Unlock()
	return nil
}

func (r *Runtime) setPairError(err error) {
	r.mu.Lock()
	r.state = StateError
	r.lastErr = err.Error()
	r.mu.Unlock()
}

func digitsOnly(phone string) string {
	var b strings.Builder
	for _, ch := range phone {
		if ch >= '0' && ch <= '9' {
			b.WriteRune(ch)
		}
	}
	return b.String()
}

func (r *Runtime) consumeQR(qrChan <-chan whatsmeow.QRChannelItem) {
	for evt := range qrChan {
		r.mu.Lock()
		switch evt.Event {
		case "code":
			r.qr = evt.Code
			r.qrExpiry = time.Now().Add(20 * time.Second).UnixMilli()
			if r.state != StateCode {
				r.state = StateQr
			}
		case "success":
			r.state = StateLinked
			r.qr = ""
			r.pairCode = ""
			r.dirty = true
		case "timeout":
			r.state = StateError
			r.lastErr = "qr_timeout"
		default:
			if evt.Error != nil {
				r.state = StateError
				r.lastErr = evt.Error.Error()
			}
		}
		r.mu.Unlock()
	}
}

func (r *Runtime) CancelPairing() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.cancelPairLocked()
	if r.client != nil {
		r.client.Disconnect()
	}
	if r.state != StateLinked {
		r.state = StateCancelled
		r.qr = ""
		r.pairCode = ""
	}
}

func (r *Runtime) cancelPairLocked() {
	if r.cancelPair != nil {
		r.cancelPair()
		r.cancelPair = nil
	}
}

func (r *Runtime) Snapshot() ([]byte, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.db == nil {
		return nil, errors.New("runtime closed")
	}
	return dumpSQL(r.db)
}

func (r *Runtime) Restore(snapshot []byte) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.cancelPairLocked()
	r.resetClient()
	if err := r.openMemory(); err != nil {
		return err
	}
	if err := restoreSQL(r.db, snapshot); err != nil {
		return err
	}
	device, err := r.container.GetFirstDevice(context.Background())
	if err != nil {
		return err
	}
	r.device = device
	r.client = whatsmeow.NewClient(device, waLog.Noop)
	r.client.AddEventHandler(r.onEvent)
	if device.ID != nil {
		r.state = StateLinked
	} else {
		r.state = StateIdle
	}
	r.dirty = false
	return nil
}

func (r *Runtime) Reconnect() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.client == nil {
		return errors.New("runtime closed")
	}
	if r.client.Store.ID == nil {
		return errors.New("whatsapp_session_missing")
	}
	if r.client.IsConnected() {
		return nil
	}
	if err := r.client.Connect(); err != nil {
		return err
	}
	r.state = StateLinked
	return nil
}

func (r *Runtime) Logout() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.cancelPairLocked()
	if r.client != nil && r.client.IsLoggedIn() {
		_ = r.client.Logout(context.Background())
	}
	r.resetClient()
	if err := r.openMemory(); err != nil {
		return err
	}
	r.state = StateIdle
	r.qr = ""
	r.inbound = nil
	r.chats = make(map[string][]inboundMessage)
	r.names = make(map[string]string)
	r.dirty = false
	return nil
}

func (r *Runtime) SendText(chatJID, body string) (string, error) {
	r.mu.Lock()
	client := r.client
	r.mu.Unlock()
	if client == nil || !client.IsLoggedIn() {
		return "", errors.New("whatsapp_runtime_not_logged_in")
	}
	jid, err := types.ParseJID(canonicalChat(chatJID))
	if err != nil {
		return "", err
	}
	msg := &waE2E.Message{Conversation: proto.String(body)}
	resp, err := client.SendMessage(context.Background(), jid, msg)
	if err != nil {
		return "", err
	}
	r.mu.Lock()
	r.rememberLocked(inboundMessage{
		ID:     resp.ID,
		Chat:   jid.ToNonAD().String(),
		Sender: linkedPhone(r.device),
		Name:   "You",
		Body:   body,
		TS:     time.Now().UnixMilli(),
		Out:    true,
	})
	r.mu.Unlock()
	return resp.ID, nil
}

func (r *Runtime) DrainInboundJSON() (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.inbound == nil {
		return "[]", nil
	}
	data, err := json.Marshal(r.inbound)
	r.inbound = nil
	return string(data), err
}

func (r *Runtime) UnencryptedFiles() string {
	r.mu.Lock()
	dir := r.watchDir
	r.mu.Unlock()
	if dir == "" {
		return ""
	}
	var found []string
	_ = filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		name := strings.ToLower(info.Name())
		if strings.HasSuffix(name, ".db") || strings.HasSuffix(name, ".sqlite") || strings.HasSuffix(name, ".sqlite3") {
			found = append(found, path)
		}
		return nil
	})
	return strings.Join(found, "\n")
}

func (r *Runtime) onEvent(evt any) {
	switch v := evt.(type) {
	case *events.Message:
		r.mu.Lock()
		r.ingestLiveMessageLocked(v)
		r.mu.Unlock()
	case *events.HistorySync:
		r.mu.Lock()
		r.ingestHistoryLocked(v.Data)
		r.mu.Unlock()
	case *events.PushName:
		r.mu.Lock()
		r.setNameLocked(v.JID, v.NewPushName)
		r.mu.Unlock()
	case *events.Connected:
		r.mu.Lock()
		if r.client != nil && r.client.Store.ID != nil {
			r.state = StateLinked
			r.dirty = true
		}
		r.mu.Unlock()
	case *events.PairSuccess:
		r.mu.Lock()
		r.state = StateLinked
		r.dirty = true
		r.mu.Unlock()
	case *events.LoggedOut:
		r.mu.Lock()
		r.state = StateError
		r.lastErr = "logged_out"
		r.mu.Unlock()
	}
}

func linkedPhone(device *store.Device) string {
	if device == nil || device.ID == nil {
		return ""
	}
	user := device.ID.User
	if user == "" {
		return ""
	}
	if strings.HasPrefix(user, "+") {
		return user
	}
	return "+" + user
}

func (r *Runtime) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.cancelPairLocked()
	r.resetClient()
	r.state = StateIdle
}
