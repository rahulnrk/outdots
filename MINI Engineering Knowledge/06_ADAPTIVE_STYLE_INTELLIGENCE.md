# MINI — Adaptive Personal Communication Style (Privacy-First)

**Product requirement:** MINI should sound naturally like the user **in the correct relationship**—professional, client, family, friends, marketing or other context—and adapt to changes in their actual writing. The user decides the meaning; MINI learns presentation. Learning is opt-in and local, never universal online training.

## 1. What to learn and what not to infer

**Allowed when consented:** message length, formality, degree of directness, first-person phrases, typical vocabulary, greetings/sign-offs, punctuation, emoji frequency, capitalization, abbreviations, humor, Hindi/Telugu/English or romanized code-switching, politeness level, recipient-specific conventions, marketing/brand tone.

**Not style authority:** the user's emotional diagnosis, political/religious identity, financial decisions, health traits, whether a recipient is family solely from contact name, likelihood of accepting an offer, or willingness to send money. Do not infer personal or sensitive attributes or convert past behavior into new commitments.

**High-quality samples:** authentic, user-authored outgoing messages known from authoritative sent-mail/provider API data, or final edits made by user **inside MINI**. An AI-generated draft **not edited/confirmed** by the user is **not** an authentic writing sample. An outbound native handoff with no provider sent receipt cannot be assumed sent. Inbound messages and third-party text must never teach the user's voice.

## 2. Hierarchical style profiles

```text
USER GLOBAL style
  → CONTEXT style (professional / family / friends / marketing / negotiations / user-defined)
      → RECIPIENT (person or group, user-confirmed identity)
          → SITUATION (urgent, apologetic, celebratory, serious, casual)
```

**Fallback**: recipient-specific if verified enough → context category → global → neutral natural draft. Each profile stores confidence, evidence count, updated timestamp, version, source provenance, user override and expiry. Use confidence thresholds determined by evaluation; a newly seen contact should not inherit another person's examples.

### Compact style profile (example, synthetic)

```json
{
  "id": "style:client:recipient-23",
  "user_id": "local-user",
  "context": "client",
  "relationship_source": "user_confirmed",
  "recipient_id": "contact-23",
  "language_preferences": ["English"],
  "traits": {
    "formality": 0.8, "brevity": 0.7, "warmth": 0.5,
    "emoji_frequency": 0.02,
    "intro_style": "brief greeting only if new thread",
    "signoff": "omit on short replies",
    "punctuation": "short paragraphs"
  },
  "examples": ["evidence:sender-001", "evidence:sender-074"],
  "observations": 21,
  "confidence": 0.72,
  "updated_at": "2026-10-09T00:00:00Z",
  "consent_version": 3,
  "profile_version": 8
}
```

Values are illustrative, **not** universal psychological scores; model inputs should be terse text distilled from this representation rather than raw JSON if token-efficient. Encrypt at rest. User can inspect/edit/remove context labels and delete all samples/profile derivatives.

## 3. Personalization learning loop

```text
Approved user-authored/outgoing text OR locally edited draft
   ↓ verify authorship + consent + context + source provenance
   ↓ redact unnecessary sensitive spans for style statistics
   ↓ cheap counters (length, greetings, punctuation, language/script patterns)
   ↓ update compact encrypted per-context profile & confidence
   ↓ optional infrequent on-device LLM check for nuance
   ↓ next draft: select 1 profile + 0–3 relevant authorized examples
   ↓ factual/evidence validator + user review
```

**No continuous on-device training.** Update counters upon an authorized outgoing event or user edit (debounce edits); avoid storing every version of typed drafts or keypress telemetry. If examples are saved, keep a minimal bounded, user-visible set with deletion links. Use recency decay for style changes but do not prematurely erase relationship-specific style without user choice.

## 4. Draft composition and safeguards

Write the **content plan** from facts first, then realize it in style. Example:

```text
Intent: acknowledge colleague request
Evidence: delivery date is undecided; user has not approved quote
Style: colleague = brief, direct, English, no emoji
Safe draft: "Thanks for checking. I'll confirm the timing after reviewing the details."
Unsafe draft: "Done, we'll deliver tomorrow for ₹10,000."  # invented terms
```

For family, the same factual content may be warmer or romanized Telugu **only if prior examples and explicit language preference support it**. Don't invent Telugu dialect authenticity from just a contact category. Marketing voice needs separate opt-in and clear distinction between branded public promotion and private conversations.

**Prompt budget:** roughly 60–150 tokens for a compact style summary plus 1–3 short approved style examples; evaluate the optimum. Larger profiles are not automatically more human. Set *source priority*: user-approved facts > latest authoritative provider history > observed notifications > generated summaries; style data has **no authority** over facts.

## 5. User experience controls

- Toggle: **Learn my style** (off until opt-in); simple explanation and data-source permissions.
- Per-context presets: `Professional`, `Family`, `Friends`, `Marketing`, `Custom`; change any conversation's classification.
- `Sound more like me` correction: user edits final draft, can optionally give specific feedback (more direct, less formal, fewer emoji).
- `Show why`: reveal applied style profile and safe snippets/source metadata, with permission and secure display.
- `Reset this contact` and `Delete my writing-style data`; clear evidence references, vector index, caches and unsent style jobs.
- **Never auto-send**. Sending and profile learning are separate permissions. If app cannot observe the final native-app message, it must not hallucinate feedback.

## 6. Naturalness tests beyond 'tone selection'

- **Cold start:** neutral, concise, user-editable; no fabricated personality claims.
- **Recipient leakage:** family greeting never appears in a client quote; friend nicknames never borrowed across contacts.
- **Style switching:** same factual response rewritten appropriately for manager/family/customer/marketing without changed factual content.
- **Drift:** user stops using emojis; profile gradually adjusts with later evidence.
- **Code-switch:** English + Telugu in romanized and native scripts; Hindi vs Hinglish; don't claim success from generic language coverage.
- **Identity:** two recipients with same display name do not share a profile.
- **User correction:** revised AI draft update is attributed to user's editing, not unedited model text.
- **Adversarial inbound:** message instructing the assistant to change the user's style profile is ignored as untrusted source content.
- **Fact gate:** unsupported money/date/delivery/medical/legal decisions never become commitments because old examples contained such language.

## 7. Evaluation protocol

**Automated:** retained fact matching; critical claims and send approval tests; profile selection; no cross-account retrieval; deletion propagation; update latency/storage/RAM measurements; language/script detection error.

**Blind human:** compare draft A (generic neutral) with B (personalized) using synthetic trained profile, without knowing labels. Score naturalness, appropriateness, concision and how often user would send with minimal edits. Track factual correctness separately. Real user testing requires informed consent and may use user-held local evaluation on device to avoid collecting private samples centrally.

**Primary win metric:** high rate of **approved-with-few-edits** drafts *without* extra fabricated claims. Protect user autonomy; do not make the product mimic the user in a way that hides AI assistance from the user or sends on their behalf without authorization.
