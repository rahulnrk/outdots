# Independent stabilization review at eb6cc5b

> **Execution follow-up, 17 October:** [Implemented corrections, fresh checks and remaining acceptance](STABILIZATION_EXECUTION_2026-10-17.md) records continuation from the newer `de6c970` source plus subsequent working-tree fixes. Treat the findings below as historical reproduction evidence. Device/live and ambiguous-legacy acceptance remain open.

Reviewed 16 October 2026, Asia/Kolkata. Report supplied by the user names `0877e7b`, `9df2a05`, `107e33f`, and `eb6cc5b`; all four commits exist and contain application/test changes. This is substantive progress. S1/S4/S5 nevertheless cannot yet be accepted as fully verified, and the shared-suite all-passing claim does not reproduce.

## Fresh evidence

- Independently ran `native/gradlew.bat :shared:jvmTest --console=plain`: **245 tests completed, one failed**, exit code 1. Failure: `UnifiedSearchTest.removedMessagesNeverAppearInSearchResults`, expected one JDBC result, received two. The nine new `S1StoredMessageIntegrityTest` cases passed.
- The failing assertion needs examination against the intended policy: dismissal should preserve permitted history; provider deletion must exclude the deleted message. Update an outdated test only with explicit separate coverage for those two cases and in-memory/JDBC/SQLCipher policy alignment. Do not remove an assertion simply to obtain a green build.
- Existing Android unit XML reports show 115 tests and zero failures, and an APK exists. These outputs were inspected, not independently rebuilt in this review. Root TS tests/typecheck/build were not rerun. No device or live-provider qualification was performed.
- `tmp/audit-crosscheck-2026-10-16/StabilizationReviewProbe.java`, executed against current compiled shared classes, returned:
  - Genuine Instagram DirectMessage, `Please call me today`: eligible `true`.
  - Same identity/category, `Please send me your recommendation today`: eligible `false`.
  - `ConnectorProviderGateway.classify` on a Protocol error from HTTP 200: `Rejected(reason=protocol:200:invalid_success_body)`.
- No production source, accounts, authorizations or user data was changed by this review.

## Findings requiring correction

### R1 / High — malformed successful send can be falsely labelled rejected

`ProviderApiClient` now raises `ProviderErrorKind.Protocol` for malformed successful HTTP bodies. `ConnectorProviderGateway.classify` only treats `Transient` as unknown; Protocol falls into definite rejection. Gmail and other sender catch paths use this classifier after their write request.

A provider can execute a send and return a malformed/truncated 200 body. MINI then claims rejection although the outcome is uncertain. No automatic retry was observed, but the false failure can induce a duplicate manual resend and violates the product's outcome contract.

Correct classification with request-stage awareness: a read/protocol failure before any write is distinct from a malformed success response after a potentially executed write. Add sender-level malformed-success tests that pass through the real API client, sender, gateway and outbox state; require unknown outcome with no resend. Preserve supported empty Graph 202 semantics. Generic JSON validity alone is also insufficient: valid JSON of the wrong required endpoint shape must not silently become empty history or advance a cursor.

Anchors: `ProviderApiClient.kt:39`; `SendContracts.kt:112,199`; `ProviderApiClientProtocolTest.kt` currently tests only the client exception, not resulting send state.

### R2 / High — topic-based filtering hides genuine messages

`isConversationSummary` rejects notification-only Instagram/X/Facebook DMs whenever `looksLikeRecommendation` finds phrases including the bare word `recommendation`. The synthetic probe above reproduces loss of a valid request. This replaces the removed fixture-name rule with a broader message-loss rule. Search separately reconstructs raw payloads with structural messaging evidence absent, so sharing some helpers has not established one consistent evidence-aware policy.

Retain trustworthy message/event metadata from ingest and use it in all projections. A recommendation notification and a person discussing a recommendation are different. Do not repair this with another name/keyword exception. Test actual messaging-style input through persistence/reopen and Inbox/search/counts/Focus, including messages about recommendations/jobs/posts/likes and equivalent real non-message alerts. Preserve ambiguous old source records and user recovery.

Anchors: `CommunicationClassification.kt:155,186,222`; per-store search eligibility branches. The current nine S1 tests do not cover this genuine-message wording.

### R3 / Medium — new cooldown can retry earlier than Retry-After

`persistRetryAdmission` clamps provider-requested cooldowns to 15 minutes. For `Retry-After: 3600`, next admission is roughly 15–15.5 minutes, violating the requested hour. Refresh also occurs before cooldown admission. HTTP-date Retry-After is still ignored; persistence failure is swallowed by `runCatching`.

Cap the application's exponential backoff independently of a provider's minimum retry time. Parse supported delay/date forms with injected time and overflow bounds, check admission before relevant provider work, and surface durable scheduling failures honestly. Add tests for long cooldowns, date values, restart and storage failure.

Anchors: `ConnectorSyncEngine.kt:88-98,196-220`; `ConnectorContracts.kt:247`.

### R4 / High, acknowledged S2 scope — Gmail and shared credential ownership remain open

The new in-flight set belongs to one `ConnectorSyncEngine` instance. `refreshForUse` bypasses it. Android's specialized Gmail connector remains unchanged and has no matching cooldown/in-flight guard. Sync/read/send/disconnect races therefore remain possible. Do not describe sync coalescing or backoff as complete across all account paths.

Complete one coordinated per-account credential owner and sync admission mechanism used by all triggers and refresh callers, including Gmail. Protect token writes/removal and account health/cursor commits against stale generations and concurrent disconnect/reconnect. Keep unrelated accounts progressing. Test these races deterministically, without live credentials or clearing qualification gates.

### R5 / High, remaining S1 acceptance — reused keys and transaction boundaries

The persisted fallback in `NotificationDeduplicator.evaluate` improves restart replay handling. However, every changed event with an existing notification key becomes an update reusing its ID; it has no occurrence distinction for a key reused by a genuinely new message. Lookup/evaluation and persistence are separate operations. Restart replay tests do not establish preservation under key reuse or concurrent ingestion.

Define account/source/occurrence identity using reliable available evidence, distinguish revisions from new occurrences, and make durable admission/write atomic at the appropriate owner. Add same-key-new-occurrence, post-dismissal key reuse, same-text-distinct-message and overlapping-ingestion tests. Do not collapse legitimate history to obtain duplicate-free counts.

### R6 / Open — S3 responsiveness and qualification

The report correctly leaves off-main startup/conversation/approval work open. Finish it with durable send ordering, UI stale-result/edit protection and slow-storage tests. S5 has a useful gateway window check and two negative tests; full acceptance still needs allowed-route controls, window expiry during review/refresh and current-state integration. Android SQLCipher migration/search/restart and MainActivity workflows remain device qualification work.

## Next prompt for Jcode

Read this review and the existing audit/roadmap. Continue from actual HEAD, preserving unrelated work. First resolve the full shared-suite failure using the intended dismissal-versus-provider-deletion contract. Correct R1's ambiguous send classification and R2's genuine-message filtering, with failing-before/passing-after integration regressions. Close R5's durable occurrence/atomicity acceptance gaps. Then complete S2 across Gmail, sync, reads, sends and disconnect/reconnect, including R3's minimum Retry-After guarantee. Finish S3 and the remaining S5 integration cases.

Keep full product scope, encrypted data, drafts, account boundaries, consent, exact approval and owner qualification gates. Do not automatically resend unknown outcomes or delete history. Run the complete required suites without narrow filters and report fresh commands, exit codes and counts; separately report device/live dependencies. Update status by evidence level. Do not claim S1/S4/S5 or the overall stabilization complete until their full acceptance criteria pass. No live sends, private uploads, destructive reinstall or unrelated commits are authorized by this review.
