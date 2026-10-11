package waruntime

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

const maxMessagesPerChat = 400

func (r *Runtime) ensureStores() {
	if r.chats == nil {
		r.chats = make(map[string][]inboundMessage)
	}
	if r.names == nil {
		r.names = make(map[string]string)
	}
}

// ConversationJSON returns stored messages for one chat, oldest first.
func (r *Runtime) ConversationJSON(chat string) (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.ensureStores()
	key := canonicalChat(chat)
	list := r.chats[key]
	if list == nil {
		for jid, msgs := range r.chats {
			if jid == chat || strings.HasSuffix(jid, chat) || strings.HasSuffix(chat, jid) {
				list = msgs
				break
			}
		}
	}
	if list == nil {
		return "[]", nil
	}
	data, err := json.Marshal(list)
	return string(data), err
}

func (r *Runtime) rememberLocked(msg inboundMessage) {
	r.ensureStores()
	if msg.ID == "" || msg.Chat == "" || msg.Body == "" || isHiddenChat(msg.Chat) {
		return
	}
	msg.Chat = canonicalChat(msg.Chat)
	list := r.chats[msg.Chat]
	for _, existing := range list {
		if existing.ID == msg.ID {
			return
		}
	}
	if msg.Name == "" {
		msg.Name = r.labelLocked(msg.Sender)
	}
	msg.Title = r.labelLocked(msg.Chat)
	list = append(list, msg)
	if len(list) > maxMessagesPerChat {
		list = list[len(list)-maxMessagesPerChat:]
	}
	r.chats[msg.Chat] = list
	r.inbound = append(r.inbound, msg)
	r.dirty = true
}

func (r *Runtime) setNameLocked(jid types.JID, name string) {
	r.ensureStores()
	name = strings.TrimSpace(name)
	if name == "" || jid.IsEmpty() {
		return
	}
	r.names[jid.ToNonAD().String()] = name
	r.names[jid.User] = name
}

func (r *Runtime) labelLocked(raw string) string {
	r.ensureStores()
	if raw == "" {
		return "WhatsApp contact"
	}
	if named := r.names[raw]; named != "" {
		return named
	}
	jid, err := types.ParseJID(raw)
	if err != nil {
		if strings.Contains(raw, "@lid") {
			return "WhatsApp contact"
		}
		return raw
	}
	key := jid.ToNonAD().String()
	if named := r.names[key]; named != "" {
		return named
	}
	if named := r.names[jid.User]; named != "" {
		return named
	}
	if r.device != nil {
		if c, err := r.device.Contacts.GetContact(context.Background(), jid); err == nil && c.Found {
			if n := firstNonEmpty(c.FullName, c.PushName, c.BusinessName, c.FirstName); n != "" {
				r.names[key] = n
				return n
			}
		}
		if jid.Server == types.HiddenUserServer && r.device.LIDs != nil {
			if pn, err := r.device.LIDs.GetPNForLID(context.Background(), jid); err == nil && !pn.IsEmpty() {
				if n := r.labelLocked(pn.ToNonAD().String()); n != "" && n != "WhatsApp contact" {
					return n
				}
				if pn.User != "" {
					return "+" + pn.User
				}
			}
		}
	}
	return fallbackLabel(jid)
}

// isHiddenChat drops Status updates, Channels (newsletters) and broadcast lists.
func isHiddenChat(chat string) bool {
	c := strings.ToLower(chat)
	return strings.HasPrefix(c, "status@") || strings.HasSuffix(c, "@newsletter") ||
		strings.HasSuffix(c, "@broadcast") || strings.Contains(c, "status@broadcast")
}

func fallbackLabel(jid types.JID) string {
	switch jid.Server {
	case types.DefaultUserServer:
		if jid.User != "" {
			return "+" + jid.User
		}
	case types.GroupServer:
		return "WhatsApp group"
	case types.HiddenUserServer:
		return "WhatsApp contact"
	}
	if strings.Contains(jid.Server, "lid") {
		return "WhatsApp contact"
	}
	if jid.User != "" {
		return jid.User
	}
	return "WhatsApp contact"
}

func canonicalChat(raw string) string {
	trimmed := strings.TrimSpace(raw)
	if i := strings.LastIndex(trimmed, "|conversation|"); i >= 0 {
		trimmed = trimmed[i+len("|conversation|"):]
	}
	jid, err := types.ParseJID(trimmed)
	if err != nil {
		return trimmed
	}
	return jid.ToNonAD().String()
}

// unwrap returns the real content inside ephemeral/view-once/edited wrappers and turns
// business template/button/list/interactive messages into plain text + media.
func unwrap(msg *waE2E.Message) *waE2E.Message {
	for i := 0; i < 4 && msg != nil; i++ {
		var inner *waE2E.FutureProofMessage
		switch {
		case msg.GetEphemeralMessage() != nil:
			inner = msg.GetEphemeralMessage()
		case msg.GetViewOnceMessage() != nil:
			inner = msg.GetViewOnceMessage()
		case msg.GetDocumentWithCaptionMessage() != nil:
			inner = msg.GetDocumentWithCaptionMessage()
		case msg.GetEditedMessage() != nil:
			inner = msg.GetEditedMessage()
		}
		if inner == nil || inner.GetMessage() == nil {
			break
		}
		msg = inner.GetMessage()
	}
	return msg
}

func joinNonEmpty(parts ...string) string {
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if s := strings.TrimSpace(p); s != "" {
			out = append(out, s)
		}
	}
	return strings.Join(out, "\n")
}

// businessContent extracts text and an embedded media message from business formats.
func businessContent(msg *waE2E.Message) (string, *waE2E.Message) {
	if t := msg.GetTemplateMessage(); t != nil {
		h := t.GetHydratedTemplate()
		if h == nil {
			h = t.GetHydratedFourRowTemplate()
		}
		if h != nil {
			var media *waE2E.Message
			switch {
			case h.GetImageMessage() != nil:
				media = &waE2E.Message{ImageMessage: h.GetImageMessage()}
			case h.GetVideoMessage() != nil:
				media = &waE2E.Message{VideoMessage: h.GetVideoMessage()}
			case h.GetDocumentMessage() != nil:
				media = &waE2E.Message{DocumentMessage: h.GetDocumentMessage()}
			}
			return joinNonEmpty(h.GetHydratedTitleText(), h.GetHydratedContentText(), h.GetHydratedFooterText()), media
		}
		if im := t.GetInteractiveMessageTemplate(); im != nil {
			return joinNonEmpty(im.GetBody().GetText(), im.GetFooter().GetText()), nil
		}
	}
	if b := msg.GetButtonsMessage(); b != nil {
		var media *waE2E.Message
		switch {
		case b.GetImageMessage() != nil:
			media = &waE2E.Message{ImageMessage: b.GetImageMessage()}
		case b.GetVideoMessage() != nil:
			media = &waE2E.Message{VideoMessage: b.GetVideoMessage()}
		case b.GetDocumentMessage() != nil:
			media = &waE2E.Message{DocumentMessage: b.GetDocumentMessage()}
		}
		return joinNonEmpty(b.GetText(), b.GetContentText(), b.GetFooterText()), media
	}
	if im := msg.GetInteractiveMessage(); im != nil {
		var media *waE2E.Message
		if h := im.GetHeader(); h != nil {
			switch {
			case h.GetImageMessage() != nil:
				media = &waE2E.Message{ImageMessage: h.GetImageMessage()}
			case h.GetVideoMessage() != nil:
				media = &waE2E.Message{VideoMessage: h.GetVideoMessage()}
			case h.GetDocumentMessage() != nil:
				media = &waE2E.Message{DocumentMessage: h.GetDocumentMessage()}
			}
			return joinNonEmpty(h.GetTitle(), h.GetSubtitle(), im.GetBody().GetText(), im.GetFooter().GetText()), media
		}
		return joinNonEmpty(im.GetBody().GetText(), im.GetFooter().GetText()), nil
	}
	if l := msg.GetListMessage(); l != nil {
		return joinNonEmpty(l.GetTitle(), l.GetDescription(), l.GetFooterText()), nil
	}
	if r := msg.GetButtonsResponseMessage(); r != nil {
		return r.GetSelectedDisplayText(), nil
	}
	if r := msg.GetTemplateButtonReplyMessage(); r != nil {
		return r.GetSelectedDisplayText(), nil
	}
	if r := msg.GetListResponseMessage(); r != nil {
		return r.GetTitle(), nil
	}
	if p := msg.GetPollCreationMessage(); p != nil {
		return "📊 " + p.GetName(), nil
	}
	if c := msg.GetContactMessage(); c != nil {
		return "👤 " + c.GetDisplayName(), nil
	}
	if loc := msg.GetLocationMessage(); loc != nil {
		return fmt.Sprintf("📍 %s https://maps.google.com/?q=%f,%f", loc.GetName(), loc.GetDegreesLatitude(), loc.GetDegreesLongitude()), nil
	}
	return "", nil
}

func messageBody(msg *waE2E.Message) string {
	msg = unwrap(msg)
	if msg == nil {
		return ""
	}
	if t := msg.GetConversation(); t != "" {
		return t
	}
	if ext := msg.GetExtendedTextMessage(); ext != nil && ext.GetText() != "" {
		return ext.GetText()
	}
	if img := msg.GetImageMessage(); img != nil {
		return img.GetCaption()
	}
	if vid := msg.GetVideoMessage(); vid != nil {
		return vid.GetCaption()
	}
	if doc := msg.GetDocumentMessage(); doc != nil {
		return firstNonEmpty(doc.GetCaption(), doc.GetFileName())
	}
	text, _ := businessContent(msg)
	return text
}

// mediaMessage returns the message that actually carries a downloadable attachment.
func mediaMessage(msg *waE2E.Message) *waE2E.Message {
	msg = unwrap(msg)
	if msg == nil {
		return nil
	}
	if msg.GetImageMessage() != nil || msg.GetVideoMessage() != nil || msg.GetStickerMessage() != nil ||
		msg.GetAudioMessage() != nil || msg.GetDocumentMessage() != nil {
		return msg
	}
	_, media := businessContent(msg)
	return media
}

// mediaKind classifies a downloadable attachment. Empty means text only.
func mediaKind(msg *waE2E.Message) string {
	msg = mediaMessage(msg)
	if msg == nil {
		return ""
	}
	switch {
	case msg.GetImageMessage() != nil:
		return "image"
	case msg.GetVideoMessage() != nil:
		if msg.GetVideoMessage().GetGifPlayback() {
			return "gif"
		}
		return "video"
	case msg.GetStickerMessage() != nil:
		return "sticker"
	case msg.GetAudioMessage() != nil:
		return "audio"
	case msg.GetDocumentMessage() != nil:
		return "document"
	}
	return ""
}

// encodeBody prefixes a media marker the Android client renders inline.
func encodeBody(id string, msg *waE2E.Message) string {
	body := messageBody(msg)
	kind := mediaKind(msg)
	if kind == "" {
		return body
	}
	marker := "\u27e6" + kind + ":" + id + "\u27e7"
	if body == "" {
		return marker
	}
	return marker + " " + body
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func (r *Runtime) ingestHistoryLocked(data *waHistorySync.HistorySync) {
	if data == nil {
		return
	}
	for _, mapping := range data.GetPhoneNumberToLidMappings() {
		pn, pnErr := types.ParseJID(mapping.GetPnJID())
		lid, lidErr := types.ParseJID(mapping.GetLidJID())
		if pnErr == nil && lidErr == nil && !pn.IsEmpty() && !lid.IsEmpty() {
			if r.device != nil && r.device.LIDs != nil {
				_ = r.device.LIDs.PutLIDMapping(context.Background(), lid, pn)
			}
			if pn.User != "" {
				r.setNameLocked(lid, "+"+pn.User)
			}
		}
	}
	for _, pn := range data.GetPushnames() {
		jid, err := types.ParseJID(pn.GetID())
		if err == nil {
			r.setNameLocked(jid, pn.GetPushname())
		}
	}
	for _, conv := range data.GetConversations() {
		chatRaw := conv.GetID()
		if alt := conv.GetPnJID(); alt != "" {
			if chatJID, err := types.ParseJID(chatRaw); err == nil {
				if pnJID, err := types.ParseJID(alt); err == nil && pnJID.User != "" {
					r.setNameLocked(chatJID, "+"+pnJID.User)
					if r.device != nil && r.device.LIDs != nil {
						_ = r.device.LIDs.PutLIDMapping(context.Background(), chatJID, pnJID)
					}
				}
			}
		}
		if name := firstNonEmpty(conv.GetName(), conv.GetDisplayName()); name != "" {
			if chatJID, err := types.ParseJID(chatRaw); err == nil {
				r.setNameLocked(chatJID, name)
			}
		}
		for _, wrapped := range conv.GetMessages() {
			r.ingestWebMessageLocked(conv.GetID(), wrapped.GetMessage())
		}
	}
}

func (r *Runtime) ingestWebMessageLocked(chatFallback string, info *waWeb.WebMessageInfo) {
	if info == nil {
		return
	}
	key := info.GetKey()
	if key == nil {
		return
	}
	body := encodeBody(key.GetID(), info.GetMessage())
	if body == "" {
		return
	}
	r.keepMediaLocked(key.GetID(), info.GetMessage())
	chat := key.GetRemoteJID()
	if chat == "" {
		chat = chatFallback
	}
	fromMe := key.GetFromMe()
	sender := key.GetParticipant()
	if sender == "" {
		if fromMe {
			sender = linkedPhone(r.device)
		} else {
			sender = chat
		}
	}
	if push := info.GetPushName(); push != "" {
		if senderJID, err := types.ParseJID(sender); err == nil {
			r.setNameLocked(senderJID, push)
		}
	}
	ts := int64(info.GetMessageTimestamp()) * 1000
	r.rememberLocked(inboundMessage{
		ID:     key.GetID(),
		Chat:   chat,
		Sender: sender,
		Name:   r.labelLocked(sender),
		Body:   body,
		TS:     ts,
		Out:    fromMe,
	})
}

func (r *Runtime) ingestLiveMessageLocked(v *events.Message) {
	body := encodeBody(v.Info.ID, v.Message)
	if body == "" {
		return
	}
	r.keepMediaLocked(v.Info.ID, v.Message)
	if v.Info.PushName != "" {
		r.setNameLocked(v.Info.Sender, v.Info.PushName)
	}
	sender := v.Info.Sender.ToNonAD().String()
	if v.Info.IsFromMe {
		if me := linkedPhone(r.device); me != "" {
			sender = me
		}
	}
	r.rememberLocked(inboundMessage{
		ID:     v.Info.ID,
		Chat:   v.Info.Chat.ToNonAD().String(),
		Sender: sender,
		Name:   r.labelLocked(sender),
		Body:   body,
		TS:     v.Info.Timestamp.UnixMilli(),
		Out:    v.Info.IsFromMe,
	})
}
