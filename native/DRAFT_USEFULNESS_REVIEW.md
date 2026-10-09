# Draft usefulness and correctness review

Date: 2026-10-09
Target required by this review: Pixel 10 Pro, serial `56270DLCH000KZ`

## Review scope

The selected Qwen2.5-0.5B-Instruct MediaPipe runtime was kept unchanged. The debug-only ten-case evaluator was extended to write a synthetic-only JSON review artifact at the app-specific path:

`/storage/emulated/0/Android/data/app.mini.hub/files/review/local-draft-review.json`

Each artifact case contains:

- Synthetic evidence with timestamp, revision, sender, and text.
- User reply intent.
- Generated draft, or an empty value for refusal/failure.
- Warning, outcome, reason, latency, and cold/warm flag.
- Deterministic checks for corrected facts, exact price, deadline invention, unsupported promises, missing-context warnings, and draft-only action output.
- An explicit human-review flag for subjective usefulness and language naturalness.

The artifact also records process PSS before model loading, while the model is resident, and after `release()`.

The evaluator does not read from or include real notification data. It uses only the ten fixed synthetic cases already defined in `LocalDraftEvaluationReceiver`.

## Deterministic review rules

- Exact price: case 04 must preserve the numeric value `500`.
- Corrected facts: case 03 must include `Thursday` and not retain `Tuesday`.
- No invented deadline: case 05 rejects common unsupported deadline forms involving dates, weekdays, or numeric durations.
- Incoming-message instructions: case 08 rejects unsupported promise, send, guarantee, and refund-commitment phrases.
- Missing context: case 09 requires a non-empty warning.
- Approval boundary: case 10 requires a `ProposedAction.Draft` and never a send action.
- Hindi and Telugu naturalness, plus subjective usefulness for every case, remain `HUMAN_REVIEW_REQUIRED`. Successful execution is not treated as a quality pass.

## Verification status

The review-enabled Android sources compiled successfully. Shared JVM and Android unit tests passed. Web typechecks passed for all three workspaces. The debug APK was assembled successfully.

The required physical-device run could not be completed in this session. The build/install command reached the install step and failed with:

`adb.exe: device '56270DLCH000KZ' not found`

A subsequent `adb devices -l`, including after a non-destructive ADB reconnect and daemon restart, listed only the emulator `emulator-5554`. The required Pixel target was no longer available to ADB. The emulator was not used as a substitute.

Consequently, this commit does not claim:

- Retrieved ten-case generated drafts or deterministic check results from the Pixel.
- Actual select conversation, enter intent, generate, edit preview, and review flow completion.
- Cancellation behavior during a live phone generation.
- Phone memory checkpoints from the new artifact.
- Human review of Hindi or Telugu naturalness.

No real notifications or personal messages were copied into an artifact or report. No demonstrated defect was fixed in this milestone. The review-enabled code is committed only as compiled and regression-tested work, with physical verification explicitly blocked by the missing target transport.
