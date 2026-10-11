# Paste into jcode — MINI Stage 2: correct message record + unified search

Continue the existing repo at HEAD (`b8a4d55` or later). Do not rebuild anything that works.
Read `CLAUDE.md` first. Work alone as coordinator — these tasks share files; no swarm.

## Build rules (learned in Stage 1)

- Run Gradle only through `tmp\gradle10.cmd <args>` (10-minute limit, no hanging pipe). One build at a time. Use `--offline` and `--tests <Class>` for targeted runs.
- Full suite (`scripts\verify.ps1`) only at the start, after T3, and at the end.
- Failing test first, one commit per task, per-test timeouts, never weaken a test.
- Forbidden: live sends, clearing send gates, `connectedDebugAndroidTest`, uninstalling the app, cloud inference, new dependencies in `native/shared`, logging message content/addresses/tokens.

## T0 — Housekeeping (no code)
Commit `CLAUDE.md`, `AGENTS.md`, `docs/JCODE_STAGE1_PROMPT.md`, `docs/JCODE_STAGE2_PROMPT.md`,
`docs/PRODUCT_AUDIT_AND_PLAN_2026-10-11.md` and `tmp/gradle10.cmd`. Run the baseline:
59 TypeScript, 142 shared JVM, 61 Android unit tests. Stop if it differs.

## T1 — Phone check of Stage 1 (phone `56270DLCH000KZ` is connected)
Build debug, install with `adb install -r` (keeps data). Confirm from logs (counts/states only):
- app opens, existing accounts and stored history are still present;
- one manual sync completes;
- reboot the phone, do not open the app, and a sync log line appears within 30 minutes.
Record the result in `docs/IMPLEMENTATION_STATUS.md` as device-verified or not. If the model file
or Gmail accounts are missing, say so; do not reconnect anything yourself.

## T2 — Messages keep the facts the product needs
`ConnectorObservationFactory.fromMessage` drops `direction`, `recipients` and `replyAnchor`.
`getObservationsForConversation` orders by `observed_at` (fetch time), and removed rows are included.

Done when:
- `ObservedNotification` and the SQLCipher + JVM stores persist direction, recipients and reply anchor (nullable = unknown for old and notification rows);
- the schema migrates in place (new version, added columns), existing data kept, covered by a migration test like the existing v1→v2 one;
- conversation timelines are ordered by provider event time, then fetch time, then id — stable for equal timestamps;
- removed rows are excluded from retrieval, understanding and reply selection (still stored);
- tests: out-of-order backfill, identical timestamps, a deleted message, a sent message.

## T3 — Reply targets the right message; style stays inside its account
- `InboxViewModel.prepareDraft` picks the reply anchor with `lastOrNull` on a newest-first list, so it selects the oldest message. Use stored direction (fall back to the current sender check only when direction is unknown) and pick the newest inbound message. Test with a 3-message thread.
- `AdaptiveStyleEngine` keys category profiles as `cat:<category>` with no account, so two accounts share style and example text. Include the account in every profile and sample key; on restore, discard old account-less category profiles (they are rebuilt from history). `ScopedRetrievalEngine.retrieve` must reject a compartment that does not match the conversation's account. Tests: two accounts, same category and same recipient name → no shared profile or example; mismatched compartment → refused.

## T4 — Unified search (new feature)
One search box in the inbox that searches every enabled source on the phone.

Done when:
- a full-text index lives inside the existing encrypted observation database (FTS5; the store already probes for it), kept in sync on insert, update and removal, and built for existing rows on upgrade;
- every index row carries its account; a query returns results grouped with account and source shown;
- a source filter narrows results; sources switched off in settings and removed messages never appear;
- tapping a result opens that conversation;
- queries run off the main thread and drop stale results (reuse the refresh pattern from Stage 1);
- tests: results from two accounts, filter, disabled source excluded, removed excluded, and 10,000 stored messages answered in under 300 ms on the JVM store;
- no search text or result content is logged.

## Stop and report
Stop after T4. Do not start summaries, labels, model work or connector changes.
Report: commits (task, SHA), test counts, per-task failing→passing test, device-verified or not,
anything not done and why, and risks noticed but not touched.
