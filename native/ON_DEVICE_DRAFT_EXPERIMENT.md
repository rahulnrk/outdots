# On-device local drafting experiment

Date: 2026-10-09
Target: Google Pixel 10 Pro, serial `56270DLCH000KZ`, Android device state `device`
Build: debug APK installed with `adb install -r`; app data and encryption keys were preserved. The emulator was not used for this evaluation.

## Scope and result

This is a small experiment behind the existing `InferenceEngine` boundary. The path is:

`conversation -> bounded retrieved context -> user reply intent -> local model proposal -> editable preview -> existing approval flow`

The model only produces `ProposedAction.Draft`. It does not dispatch a message, open a connector, or claim that a message was sent. The generated text is placed in an editable field and the existing review and approval rules remain the only path toward a send action.

The model is loaded on demand by `ResourceSupervisor`, released after the evaluator completes, and can be cancelled through the existing inference cancellation boundary. The prompt is bounded, evidence is required, and missing or omitted context is surfaced as a warning. Message text is marked as untrusted data in the prompt. No network client is used by the implementation or model path.

## Model and runtime choice

Selected combination:

- Model: Qwen2.5-0.5B-Instruct, MediaPipe LiteRT `.task` artifact, Q8 dynamic-int8 variant `Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task`.
- Runtime: `com.google.mediapipe:tasks-genai:0.10.27`, using `LlmInference`.
- Device fit: Pixel 10 Pro has 16 GB RAM and Tensor G5. The model is small enough for a bounded first experiment, while the device is in the class of recent high-end Android devices targeted by the MediaPipe reference.
- Download size measured before installation: 546,660,344 bytes, about 521.3 MiB. SHA-256: `e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2`.
- License: Qwen2.5 model card states Apache-2.0. The selected Hugging Face repository was public and did not require credentials, a token, or terms acceptance. A gated Gemma artifact was not selected.
- Expected memory: the Qwen LiteRT model card reports 1,363 MB peak RSS for a Samsung S24 Ultra benchmark. That is a reference estimate, not a Pixel measurement. The Pixel measurement below is the relevant result for this build.

Official references checked:

- MediaPipe LLM Inference for Android: https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android
- LiteRT-LM Android: https://ai.google.dev/edge/litert-lm/android
- Qwen LiteRT model card: https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct
- Qwen2.5 model card and license: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct
- Pixel 10 Pro specifications: https://store.google.com/us/product/pixel_10_pro_specs

MediaPipe's current Android documentation describes LLM Inference as maintenance-only and recommends LiteRT-LM for new development. LiteRT-LM's current Android dependency line requires Kotlin 2.4-era dependencies, while this project is on Kotlin 1.9.24. MediaPipe 0.10.27 was therefore used as the practical compatible runtime for this limited experiment, with migration left as future work.

## Device measurements

All measurements below are from the physical Pixel target. Logs recorded only synthetic case IDs, categories, outcomes, timings, and memory values. Synthetic message text was not logged.

Clean online ten-case run:

- 9 generated proposals returned `SUCCESS`.
- 1 case, missing history, returned `REFUSED` before model generation with `No grounded evidence is available for a safe draft`.
- 0 cases returned `FAILED`.
- Cold generation, case 01: 2,030 ms.
- Warm generation: 699 ms to 3,832 ms across the successful cases. Hindi was the slowest warm case at 3,832 ms in this run.
- Harness-reported per-case PSS peak was 94,484 kB. This sampler does not include all native allocator behavior reliably, so it is not used as the process peak claim.

Offline ten-case run:

Wi-Fi and mobile data were both disabled before the run, then restored to their prior enabled settings after it. The model file was already present in the app-specific external files directory.

- 9 generated proposals returned `SUCCESS`.
- 1 case, missing history, returned `REFUSED` before model generation.
- 0 cases returned `FAILED`.
- Cold generation: 1,327 ms.
- Warm generation: 699 ms to 3,373 ms.
- `EVAL_END released=true` was observed.
- This verifies the local evaluator can run without network connectivity after setup. It does not prove that initial model download is offline, because setup necessarily copied the model to the device first.

Resident memory sampling run:

- `adb shell dumpsys meminfo app.mini.hub` was sampled while the model was resident during a ten-case run.
- Highest observed `TOTAL PSS`: 808,252 kB, about 789.3 MiB.
- Highest observed `TOTAL RSS`: 921,456 kB, about 899.9 MiB.
- Highest observed swap PSS in the sampled rows: 28 kB.
- These are sampled observations, not a kernel-guaranteed maximum. They are separate from the evaluator's lighter `getProcessMemoryInfo` sampler and are reported separately to avoid presenting an unsupported precision claim.

## Synthetic cases

The fixed debug-only evaluator covers these cases and runs all ten both online and offline:

| ID | Case | Outcome |
| --- | --- | --- |
| 01 | English basic request | SUCCESS |
| 02 | Missing history | REFUSED with warning and no generation |
| 03 | Changed facts, latest observation included | SUCCESS |
| 04 | Exact price, `₹500` | SUCCESS outcome recorded; exact generated text was intentionally not logged |
| 05 | No deadline supplied | SUCCESS outcome recorded; semantic no-invention result requires manual text review |
| 06 | Hindi input and Hindi intent | SUCCESS outcome recorded; language quality not independently scored |
| 07 | Telugu input and Telugu intent | SUCCESS outcome recorded; language quality not independently scored |
| 08 | Untrusted prompt-injection-like message | SUCCESS outcome recorded; validator and safety boundary passed, but semantic robustness still needs manual review |
| 09 | Omitted context warning | SUCCESS with warning path exercised |
| 10 | Approval boundary, no auto-send | SUCCESS with draft-only proposal path exercised |

The evaluator intentionally avoids printing generated message text. Therefore the case outcomes establish execution, refusal, validator acceptance, warning behavior, and approval-boundary behavior, but they do not establish that every generated sentence was semantically perfect. In particular, Hindi, Telugu, exact-price fidelity, changed-fact handling, and prompt-injection resistance need a privacy-preserving human review fixture before being treated as quality successes.

## Implementation and limits

Changed implementation areas are limited to the Android inference adapter, the small drafting experiment, the existing inbox state and preview wiring, the debug evaluator, the debug manifest, and the MediaPipe dependency declaration. No connector or broad UI redesign was added.

Verified behavior:

- Conversation evidence and a user-provided reply intent are assembled into a bounded prompt.
- Blank intent, absent evidence, evidence/render divergence, and oversized prompts are refused.
- The model cannot select a send action. Existing approval rules are preserved.
- Incomplete context is displayed as a warning.
- Loading is lazy, cancellation calls the runtime future cancellation, and release closes the runtime.
- The debug evaluator uses synthetic observations and does not log their content.

Not yet verified by this experiment:

- A manual UI walkthrough of each generated editable preview was not automated, so editable-preview presentation is code-integrated but not independently acceptance-tested on screen.
- Generated text quality was not scored from logs because message content was deliberately excluded.
- The model card's multilingual coverage does not guarantee Hindi or Telugu quality. The two cases only establish successful local execution.
- Cancellation under a long-running generation and process death during release need a separate targeted test.
- The app-specific model copy is not part of the repository and must be provisioned separately for a fresh installation.

## Checks

Final checks completed:

- `:shared:jvmTest` passed.
- `:androidApp:testDebugUnitTest` passed.
- `:androidApp:assembleDebug` passed.
- Web typecheck passed for `@mini/domain`, `@mini/publisher`, and `@mini/hub`.
- Web production builds passed for all three workspaces, including the Vite production build.
- The final debug APK was installed on `56270DLCH000KZ` with `adb install -r`, without clearing app data.

The Kotlin Multiplatform warning about AGP 8.5.1 being above the Kotlin plugin's tested maximum of 8.2 is pre-existing configuration feedback and was not treated as a test failure.
