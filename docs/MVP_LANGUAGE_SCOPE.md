# MVP language scope

Decision: 2026-10-09, confirmed by the product owner.

## English MVP

English is the initial supported drafting language and the language used for MVP
quality acceptance. The architecture's proposed target of at least 70% of eligible
drafts usable with minor edits applies to the English evaluation set. Report
critical factual and commitment errors separately. Successful generation alone
does not count as a useful draft.

This language decision does not waive approval, privacy, evidence, storage or
other product acceptance requirements.

## Test now, qualify after MVP

Hindi and Telugu written in English letters (romanized Hindi and Telugu), including
English mixed into those conversations, must be tested now. They are exploratory
results for later builds, not advertised MVP support or English release blockers.
Native-script Hindi and Telugu also remain post-MVP scope; retain their existing
regression cases without delaying the English MVP to qualify them.

Keep English, romanized Hindi, romanized Telugu and native-script results separate.
Do not combine their scores into an MVP usability percentage. English letters do
not mean the message is English, and merely avoiding native script does not prove
the model understood a romanized message.

## Synthetic evaluation cases

The debug on-device evaluator includes six new romanized cases:

- 12: conversational Hindi, asking for a preferred time.
- 13: conversational Telugu, asking for a preferred time.
- 14: Hindi mixed with English, preserving Rs 500 and requesting payment details.
- 15: Telugu mixed with English, preserving Rs 500 and requesting payment details.
- 16: informal Hindi spelling and a Tuesday-to-Thursday correction.
- 17: informal Telugu spelling and a Tuesday-to-Thursday correction.

Artifact version 2 labels each case `ENGLISH_MVP` or `POST_MVP_EXPLORATORY`.
Automatic checks cover Latin letters, unwanted conversion to native script,
byte-token artifacts, echoed incoming text, timestamp leaks, and selected price
and corrected-date facts. A human familiar with the language must still judge
meaning, reply-intent compliance, naturalness and unsupported commitments.
An English-only reply to a romanized-language request is not a language-quality
pass simply because its script checks pass.

The fixtures contain no personal messages. Device results and limitations belong
in `native/DRAFT_USEFULNESS_REVIEW.md`, with the synthetic JSON retained alongside
the existing review artifacts. Future model/runtime changes should rerun these
cases while treating only qualified English quality as the MVP language gate.

For an isolated rerun, send the existing debug evaluation broadcast with the
string extra `case_id` set to an exact fixture ID, such as
`14-hindi-mixed-price`. Only predefined synthetic cases can be selected. The
result is written to `review/local-draft-review-14-hindi-mixed-price.json`,
separately from the complete suite. New artifacts record process ID and start
time so separate invocations can be identified.
