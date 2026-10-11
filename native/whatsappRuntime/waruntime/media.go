package waruntime

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func (r *Runtime) attachExtrasLocked(raw []byte) ([]byte, error) {
	var dump sqliteDump
	if err := json.Unmarshal(raw, &dump); err != nil {
		return raw, nil
	}
	dump.Media = make(map[string][]byte, len(r.media))
	for id, msg := range r.media {
		if b, err := proto.Marshal(msg); err == nil {
			dump.Media[id] = b
		}
	}
	dump.Names = r.names
	return json.Marshal(dump)
}

func (r *Runtime) restoreExtrasLocked(raw []byte) {
	var dump sqliteDump
	if err := json.Unmarshal(raw, &dump); err != nil {
		return
	}
	r.media = make(map[string]*waE2E.Message, len(dump.Media))
	for id, b := range dump.Media {
		m := &waE2E.Message{}
		if proto.Unmarshal(b, m) == nil {
			r.media[id] = m
		}
	}
	if dump.Names != nil {
		r.names = dump.Names
	}
}

const maxMediaRefs = 2000

func (r *Runtime) keepMediaLocked(id string, msg *waE2E.Message) {
	if id == "" || mediaKind(msg) == "" {
		return
	}
	if r.media == nil {
		r.media = make(map[string]*waE2E.Message)
	}
	if len(r.media) >= maxMediaRefs {
		for k := range r.media {
			delete(r.media, k)
			break
		}
	}
	r.media[id] = msg
}

// MediaThumbnail returns the small inline preview WhatsApp embeds in the message (no network).
func (r *Runtime) MediaThumbnail(id string) []byte {
	r.mu.Lock()
	msg := r.media[id]
	r.mu.Unlock()
	if msg == nil {
		return nil
	}
	switch {
	case msg.GetImageMessage() != nil:
		return msg.GetImageMessage().GetJPEGThumbnail()
	case msg.GetVideoMessage() != nil:
		return msg.GetVideoMessage().GetJPEGThumbnail()
	case msg.GetStickerMessage() != nil:
		return msg.GetStickerMessage().GetPngThumbnail()
	case msg.GetDocumentMessage() != nil:
		return msg.GetDocumentMessage().GetJPEGThumbnail()
	}
	return nil
}

// DownloadMedia decrypts the full attachment into dir (app-private cache) and returns its path.
func (r *Runtime) DownloadMedia(id, dir string) (string, error) {
	r.mu.Lock()
	msg := r.media[id]
	client := r.client
	r.mu.Unlock()
	if msg == nil || client == nil {
		return "", errors.New("media_unavailable")
	}
	var dl whatsmeow.DownloadableMessage
	ext := ".bin"
	switch {
	case msg.GetImageMessage() != nil:
		dl, ext = msg.GetImageMessage(), ".jpg"
	case msg.GetVideoMessage() != nil:
		dl, ext = msg.GetVideoMessage(), ".mp4"
	case msg.GetStickerMessage() != nil:
		dl, ext = msg.GetStickerMessage(), ".webp"
	case msg.GetAudioMessage() != nil:
		dl, ext = msg.GetAudioMessage(), ".ogg"
	case msg.GetDocumentMessage() != nil:
		dl = msg.GetDocumentMessage()
		if e := filepath.Ext(msg.GetDocumentMessage().GetFileName()); e != "" {
			ext = e
		}
	}
	if dl == nil {
		return "", errors.New("media_unavailable")
	}
	path := filepath.Join(dir, safeName(id)+ext)
	if _, err := os.Stat(path); err == nil {
		return path, nil
	}
	data, err := client.Download(context.Background(), dl)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

func safeName(id string) string {
	out := make([]rune, 0, len(id))
	for _, c := range id {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') {
			out = append(out, c)
		}
	}
	if len(out) == 0 {
		return "media"
	}
	return string(out)
}
