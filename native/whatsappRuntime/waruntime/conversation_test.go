package waruntime

import (
	"testing"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

func TestMessageBodyAndLidFallback(t *testing.T) {
	if got := messageBody(&waE2E.Message{Conversation: proto.String("hi")}); got != "hi" {
		t.Fatalf("body=%q", got)
	}
	if got := fallbackLabel(types.NewJID("15551234567", types.DefaultUserServer)); got != "+15551234567" {
		t.Fatalf("phone label=%q", got)
	}
	if got := fallbackLabel(types.NewJID("123456789012345", types.HiddenUserServer)); got != "WhatsApp contact" {
		t.Fatalf("lid label=%q", got)
	}
	if got := canonicalChat("whatsapp|1555|conversation|15551239999@s.whatsapp.net"); got != "15551239999@s.whatsapp.net" {
		t.Fatalf("canonical=%q", got)
	}
}
