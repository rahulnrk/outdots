package igruntime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
)

type InstagramReadMessage struct {
	ID             string `json:"id"`
	ConversationID string `json:"conversation_id"`
	TimestampMS    int64  `json:"timestamp_ms"`
	Text           string `json:"text"`
	SenderID       string `json:"sender_id"`
	Direction      string `json:"direction"`
}

type InstagramReadConversation struct {
	ID              string                 `json:"id"`
	Title           string                 `json:"title"`
	Messages        []InstagramReadMessage `json:"messages"`
	HasMoreMessages bool                   `json:"has_more_messages"`
}

type InstagramReadResult struct {
	AccountID      string                      `json:"account_id"`
	VerifiedUserID string                      `json:"verified_user_id"`
	Conversations  []InstagramReadConversation `json:"conversations"`
	HasMoreThreads bool                        `json:"has_more_threads"`
}

// ReadRecentInboxJSON reads only the bounded mailbox snapshot already exposed by
// the pinned upstream client. It does not send, mark read, mutate threads, or
// request another page. The result is explicitly partial when upstream reports
// more threads or messages.
func (r *Runtime) ReadRecentInboxJSON(maxThreads, maxMessages int) ([]byte, error) {
	if maxThreads < 1 {
		maxThreads = 1
	}
	if maxThreads > 50 {
		maxThreads = 50
	}
	if maxMessages < 1 {
		maxMessages = 1
	}
	if maxMessages > 100 {
		maxMessages = 100
	}

	r.mu.Lock()
	client := r.client
	accountID := r.accountID
	r.mu.Unlock()
	if client == nil || !client.IsAuthenticated() {
		return nil, fmt.Errorf("instagram session expired")
	}
	viewer, mailbox, err := client.LoadIndex(context.Background())
	if err != nil {
		return nil, classifyReadError(err)
	}
	if viewer == nil || mailbox == nil || viewer.GetUserId() == "" {
		return nil, fmt.Errorf("instagram inbox unavailable")
	}

	result := InstagramReadResult{
		AccountID:      accountID,
		VerifiedUserID: viewer.GetUserId(),
		Conversations:  make([]InstagramReadConversation, 0, maxThreads),
	}
	seenThreads := make(map[string]struct{})
	addThread := func(thread *slidetypes.ThreadInfo) {
		if thread == nil || thread.ID == "" || len(result.Conversations) >= maxThreads {
			return
		}
		if _, exists := seenThreads[thread.ID]; exists {
			return
		}
		seenThreads[thread.ID] = struct{}{}
		title := strings.TrimSpace(thread.ThreadTitle)
		if title == "" {
			title = "Instagram conversation"
		}
		conversation := InstagramReadConversation{
			ID:       thread.ID,
			Title:    title,
			Messages: make([]InstagramReadMessage, 0, maxMessages),
		}
		if thread.SlideMessages != nil {
			conversation.HasMoreMessages = thread.SlideMessages.PageInfo.HasNextPage
			for _, edge := range thread.SlideMessages.Edges {
				message := edge.Node
				if message == nil || message.ID == "" || len(conversation.Messages) >= maxMessages {
					continue
				}
				senderID := ""
				if message.Sender != nil {
					senderID = message.Sender.IGID
					if senderID == "" {
						senderID = message.Sender.ID
					}
				}
				direction := "inbound"
				if senderID != "" && senderID == viewer.GetUserId() {
					direction = "outbound"
				}
				conversation.Messages = append(conversation.Messages, InstagramReadMessage{
					ID:             message.ID,
					ConversationID: thread.ID,
					TimestampMS:    message.TimestampMS.UnixMilli(),
					Text:           message.TextBody,
					SenderID:       senderID,
					Direction:      direction,
				})
			}
			if len(thread.SlideMessages.Edges) > len(conversation.Messages) {
				conversation.HasMoreMessages = true
			}
		}
		result.Conversations = append(result.Conversations, conversation)
	}

	for _, edge := range mailbox.ThreadsByFolder.Edges {
		addThread(edge.Node.AsIGDirectThread)
		if len(result.Conversations) >= maxThreads {
			result.HasMoreThreads = true
			break
		}
	}
	if !result.HasMoreThreads {
		result.HasMoreThreads = mailbox.ThreadsByFolder.PageInfo.HasNextPage
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		return nil, fmt.Errorf("encode Instagram inbox result: %w", err)
	}
	return encoded, nil
}

// classifyReadError intentionally exposes only a stage, fixed upstream error
// class, and HTTP status. It must never expose upstream error text or response
// data through the mobile binding.
func classifyReadError(err error) error {
	if errors.Is(err, httpclient.ErrTokenInvalidated) {
		return fmt.Errorf("instagram_read stage=upstream_request class=session_expired")
	}
	if errors.Is(err, httpclient.ErrRateLimited) {
		return fmt.Errorf("instagram_read stage=upstream_request class=throttled status=429")
	}
	if errors.Is(err, httpclient.ErrRequestFailed) || errors.Is(err, httpclient.ErrMaxRetriesReached) {
		return fmt.Errorf("instagram_read stage=upstream_request class=network_failure")
	}
	if errors.Is(err, httpclient.ErrResponseReadFailed) {
		return fmt.Errorf("instagram_read stage=response_decode class=response_read_failed")
	}
	if strings.Contains(strings.ToLower(err.Error()), "mailbox missing") {
		return fmt.Errorf("instagram_read stage=response_decode class=mailbox_missing")
	}
	message := err.Error()
	for _, status := range []string{"401", "403", "429", "500", "502", "503", "504"} {
		if strings.Contains(message, " "+status) {
			return fmt.Errorf("instagram_read stage=upstream_request class=http_status status=%s", status)
		}
	}
	return fmt.Errorf("instagram_read stage=upstream_request class=upstream_error")
}
