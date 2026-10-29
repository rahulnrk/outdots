# Jcode implementation prompt

> **Continuation after `eb6cc5b`:** Read [the independent review and next prompt](JCODE_STABILIZATION_REVIEW_2026-10-16.md) first. Some work below is now implemented, but S1/S4/S5 acceptance is still open; a fresh full shared run has one failure, and new synthetic probes reproduce message loss and ambiguous-send misclassification. Prioritize those review corrections and remaining S2/S3 work instead of repeating already passing narrow cases.

Work in `C:\Users\nande\Downloads\Mini`. Implement the stabilization work below while preserving the complete MINI/Outdots product scope, privacy, security, functionality and usability.

First read `AGENTS.md`, `docs/OUTDOTS_COMPLETE_ROADMAP_2026-10-16.md` including section 15, `docs/AUDIT_CROSSCHECK_2026-10-16.md`, `docs/PRODUCT_REQUIREMENTS.md`, and `docs/IMPLEMENTATION_STATUS.md`. Record current HEAD and inspect the working tree. The audit baseline was `2178872`; inspect any later changes before acting. Preserve unrelated uncommitted work. Do not accept previous completion reports or commit titles without verifying the implementation.

The current product is a pre-pilot engineering prototype. WP01 remains partial. The broader goals remain: connected-account Inbox; dashboard-first Focus and Ask Focus; commitments/waiting/reminders; shared drafts and exact review; document understanding and media; progressive connectors; optional calendars; encrypted backup/paused restore; local intelligence by default and separately approved external AI. Stabilization changes delivery order, not these goals.

Implement in reviewable packages with the detailed acceptance criteria in the cross-check:

1. **S1 / WP01 first — trustworthy stored messages and search.** Remove fixture-name filtering from Android and JDBC and implement a shared evidence-aware eligibility policy. Correct search omissions for legitimate NULL-category history without admitting all old noise. Make search, Inbox/counts/Focus agree before limits. Repair duplicate FTS rows using a transactional, idempotent, versioned derived-index migration; stop full-history population on every open. Make notification dedupe durable and atomic across restart/cache eviction, with account/source/occurrence identity; preserve real same-text messages and reused notification keys. Align both stores on dismissal versus provider deletion, Unicode search and newest matching message. Preserve source history, drafts and user choices.

2. **S2 / WP04 — reliable sync and credentials.** Coalesce per-account sync across every trigger, including specialized Gmail. Use one coordinated per-account refresh path for sync, conversation reads and sending. Prevent stale refresh results/failures from overwriting/deleting current credentials or undoing disconnect/reconnect. Persist and enforce Retry-After and bounded jittered backoff across restarts. Catch unexpected worker failures without private logs. Keep other accounts progressing; never hold DB transactions over HTTP.

3. **S3 / WP03 — responsive startup and review.** Move storage opening, conversation retrieval and approval writes off main. Keep durable approval consumption before provider calls, native handoff on main, loading/storage-failure barriers, draft preservation, stale-result guards and double-tap protection. Verify real UI responsiveness with slow storage and populated databases.

4. **S4 / WP04 — parser and protocol correctness.** Bound depth/size and strictly reject malformed JSON. Distinguish allowed empty 202/204 from malformed required success bodies. Validate endpoint shapes, do not advance sync checkpoints on malformed reads, and preserve unknown-send semantics without retries.

5. **S5 / WP03 + WP10 — dispatch-time policy parity.** Recheck time-sensitive reply windows immediately before sending, including after refresh delays, with injected time and current source/permissions. Kotlin already checks approval, grants, sources and capability gates: preserve those checks. Fix TS missing/consumed/expired/clock diagnostic branches and keep corresponding rules aligned. Complete S5 before any additional live-send qualification.

Start by reproducing S1 with failing tests and genuine-message controls. Existing synthetic scratch probes demonstrate failures but must become maintainable meaningful regressions. Complete S1–S5 package by package; record any external qualification dependency explicitly while continuing independent implementation. Do not silently start unrelated future features or claim the entire product finished after these corrections.

Non-negotiable constraints:

- Keep Kotlin Multiplatform shared rules and thin Android hosts; preserve TS parity for corresponding domain rules. No wholesale rewrite or disabling features to pass checks.
- Preserve SQLCipher/Keystore, account isolation, consent, no-training/no-sale commitments, content-free diagnostics and exact outgoing-payload approval. No silent external AI or private-data uploads.
- Unknown send outcomes are never automatically resent. Provider acceptance is not delivery. Tests/defaults never clear owner qualification gates.
- Do not delete source history, wipe/reinstall the app, reset authorizations or weaken checks. Migrations must preserve an existing populated installation and recover from interruption. Use `adb install -r` only after preservation regressions pass; never `connectedDebugAndroidTest`.
- Keep the user's screenshot preference and application/signing/OAuth identity. Do not log messages, addresses, tokens or sensitive content fingerprints.

Validation must include true database close/reopen and old-schema migration; repeated opens with stable FTS cardinality; restart/cache-eviction dedupe and key-reuse controls; SQLCipher/JDBC semantic parity; Unicode search; forced concurrent refresh/disconnect races; fake-clock cooldown and reply-window expiry; malformed JSON/read checkpoints/write outcomes; process-death and UI stale-result preservation.

Run `:shared:jvmTest`, `:androidApp:testDebugUnitTest`, and `:androidApp:assembleDebug` for native integration. Run root tests/typecheck/build for TS changes. Qualify affected production MainActivity workflows on an available authorized device using synthetic data. A successful launch or old passing XML report is not workflow evidence. If hardware/model/provider access is unavailable, report that limitation; do not manufacture results or enable gates.

For each package report: actual changed files and behavior, reproduced defect, fresh command outcomes, preservation checks, device/live evidence separately, remaining limitations and exact commit only if created. Do not commit unrelated work, publish, send live messages or perform private-data uploads. Update roadmap/status with evidence levels; keep unresolved acceptance open. Continue toward the full existing roadmap only under its established implementation scope and qualification gates.
