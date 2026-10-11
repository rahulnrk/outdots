# Security review

Engineering review of the current code, not a production certification. Status words follow
[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md): "fixture-tested" means unit/fixture tests
only, "qualified on device/live" means exercised on a physical device or live provider.

## Trust boundaries

1. Provider APIs and notification content are untrusted evidence, never instructions or
   authorization to act.
2. The KMP core (`native/shared`) holds decision logic: account-scoped IDs refuse cross-account
   access, time comes from an injected `Clock`.
3. Model output is an untrusted proposal. It cannot approve or execute actions.
4. The TypeScript reference and web demo run over synthetic in-memory state. They are not a vault.
5. `packages/publisher` sits outside the device and accepts only sanitized aggregate diagnostics.

## Controls

| Area | Control | Status |
|---|---|---|
| Storage | SQLCipher with random DB keys wrapped by Android Keystore (AES-GCM) for actions, observations, attention | Qualified on device (Pixel 10 Pro) |
| Storage failure | If any encrypted store cannot open, the runtime enters an explicit failed state, the UI shows a blocking error, and background hosts do nothing. There is no memory-only fallback | Fixture-tested; not device-verified |
| Screen capture | `FLAG_SECURE` on the main window | Implemented |
| Approval binding | SHA-256 digest of exact payload (account, route, recipients, body, attachments, evidence, grants/policy, expiry); re-checked at dispatch | Fixture-tested (TS and KMP) |
| Send outcomes | "Accepted" means provider acceptance only. Failures after the call starts are `OutcomeUnknown` and never auto-retried. The consumed approval and `Dispatching` state are committed before the provider call, which runs off the main thread and outside any DB transaction; at process start an interrupted `Dispatching` command becomes `OutcomeUnknown` | Fixture-tested; not device-verified |
| API send gating | Off unless `CapabilityResolver` finds the scope granted and every `ReleaseGate` is cleared. Qualification gates clear only from owner-recorded evidence written by the debug-only `GateEvidenceDebugReceiver`. Tests and defaults never clear them | Fixture-tested; no live send claimed |
| Handoff | Opens the native app with the reviewed text; never fabricates a receipt | Qualified on device |
| Source exposure | Release builds offer Gmail, Outlook and notifications only. Slack/Teams/Google Chat/Telegram/X are debug-only and labelled "not live-qualified". Matrix bridge route is not offered in the UI | Implemented |
| Logging | Local account IDs, states and counts only; no content, addresses or tokens | Convention |
| OAuth | Public client IDs only; no client secrets in the repo | Implemented |
| Publisher | Strict diagnostics schema, byte limit, no fabricated entitlement | Fixture-tested |

## Open risks

- Durable outbox restart recovery is wired at process start but not yet qualified on device.
- Non-Gmail connectors and the Matrix bridge are fixture-tested only; bridge hosting is undecided.
- The local model is not qualified for drafting quality or prompt-injection resistance on device.
- No low-memory, thermal, accessibility or malicious-app testing has run.
- iOS is not built.
- Publisher has no billing authority or signed manifest verification.
