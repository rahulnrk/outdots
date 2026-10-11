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
	for _, c := range []string{"status@broadcast", "12036@newsletter"} {
		if !isHiddenChat(c) {
			t.Fatalf("%s should be hidden", c)
		}
	}
	if isHiddenChat("15551234567@s.whatsapp.net") {
		t.Fatal("direct chat hidden")
	}
	tmpl := &waE2E.Message{TemplateMessage: &waE2E.TemplateMessage{
		HydratedTemplate: &waE2E.TemplateMessage_HydratedFourRowTemplate{
			HydratedContentText: proto.String("Your order shipped"),
			Title:               &waE2E.TemplateMessage_HydratedFourRowTemplate_ImageMessage{ImageMessage: &waE2E.ImageMessage{}},
		},
	}}
	if got := messageBody(tmpl); got != "Your order shipped" {
		t.Fatalf("template body=%q", got)
	}
	if mediaKind(tmpl) != "image" {
		t.Fatal("template image not detected")
	}
	wrapped := &waE2E.Message{EphemeralMessage: &waE2E.FutureProofMessage{Message: &waE2E.Message{Conversation: proto.String("hey")}}}
	if messageBody(wrapped) != "hey" {
		t.Fatal("ephemeral not unwrapped")
	}
}
