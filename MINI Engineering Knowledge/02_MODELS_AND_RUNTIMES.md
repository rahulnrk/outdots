# MINI — Local AI Models, Runtimes and Effective Integration

**Reviewed 2026-10-09.** Status: decision support, not a device benchmark. Model architecture, runtime compatibility, weights, licenses and distribution conditions are version-specific. Revalidate for the exact artifact SHA and deployed runtime commit. Sources at the end of this document and in `09_OFFICIAL_SOURCES_AND_UNKNOWNs.md`.

## 1. Decision rule

**Do not select one winning model prematurely.** Select an *evaluation ladder*: accurate-enough compact model on supported devices; optional higher-quality model on more capable devices; deterministic no-model fallback if neither is viable. The user's data remains local unless they explicitly enable a clearly segregated alternative.

Evaluation objective: **highest accurate, natural, relevant communication quality per joule, per second, and per MB**—not raw benchmark score or tokens per second. Hard fail on invented business commitments, forbidden content disclosure, unauthorized sends, and missing-evidence certainty.

### Model capability matrix

| Model | Primary role | Notable published abilities | Important caveats | License status |
|---|---|---|---|---|
| **Gemma 4 E2B (mobile/text-only QAT variant)** | Default generation candidate | Instruct/system messages, tool/function-call prompting, 128K advertised small-model context; mobile-optimized artifact options; multilingual | Test correct exact text-only model, tokenization, prompt format, QAT conversion and accelerator compatibility; large advertised context is *not* an efficient phone context budget; runtime RAM includes much more than weights | Google's Gemma terms; responsible commercial use permitted; read current terms and distribution requirements |
| **LFM2.5-1.2B-Instruct** | Low-end efficient challenger | 1.17B-class text model, 32,768 context, edge-targeted hybrid blocks; fast CPU-oriented performance claimed | Officially enumerated language coverage includes English/Arabic/Chinese/French/German/Japanese/Korean/Spanish; **Hindi/Telugu are not on its listed supported-language set**. Do not assume code-switched Telugu quality | **lfm1.0**, not Apache; review business/revenue thresholds, redistribution, and attribution with counsel |
| **Qwen3.5-4B** | High-quality challenger | 4B model, hybrid gated delta/attention architecture and vision encoder; publisher claims 201 languages/dialects; potential nuanced drafting | Larger weight and runtime footprint, mixed operator/back-end support; actual Hindi/Telugu and low-latency phone behavior unproven | **Apache-2.0** per model card; still confirm exact quantized derivative's license |
| **EmbeddingGemma 2 text-only 270M** | Optional retrieval/search encoder | 768-d output with native 128/256/512 truncations (Matryoshka); 100+ language coverage claim; modular text-only loading | Embeddings do not write drafts; test retrieval benefit over SQLite FTS; cold load and indexing may cost more than saved inference | **Apache-2.0** per model card |

**Published footprint examples:** Google's Gemma 4 overview lists ~0.84 GB for E2B mobile *text-only static weights*; the LiteRT-LM overview describes a **different** 2.58 GB E2B artifact. Neither is total resident process RAM or full battery cost. LiteRT-LM lists EmbeddingGemma 2 text-only at 165 MB. Never subtract these blindly to predict phone memory.

### Model choice by task (hypotheses to test)

| Task | Desired cheap path | When to invoke generation |
|---|---|---|
| Deduplicate/merge updated events | Stable source IDs + deterministic algorithm | Never |
| Identify user-defined priority | Explicit user rule and known contacts | Ambiguous conversational intent only |
| Detect specific dates/prices | Parser + typed fact candidates | Resolve indirect or ambiguous language, followed by typed validation |
| Search historical context | FTS5 + verified structured facts | Semantic retrieval only if lexical retrieval recall is insufficient |
| Draft short, natural reply | Template/handoff for predictable messages | Consent-gated local generator using grounded evidence and style |
| Draft client negotiation | Verified facts + consent boundaries | Better generation model, factual validator + approval |
| Update writing style | Local aggregate counters + user-approved edits | Optional occasional structured style analysis; no continuous inference |
| Summarize old messages | Keep raw evidence + bounded incremental synopsis | Only on user request or controlled idle maintenance |

## 2. Runtimes: compare artifacts, not marketing labels

| Runtime | Platform suitability | Strength | Qualification risk |
|---|---|---|---|
| **LiteRT-LM** | Android Kotlin stable per Google's docs; Swift early preview on iOS; C++ API also available | Google-optimized model artifacts, hardware backends, documented Gemma mobile paths, controlled structured output options | Exact model/backend/device operator support, converted artifact availability, GPU/NPU availability, native packaging and Swift preview maturity |
| **llama.cpp / GGUF** | Android CPU, Apple Metal via C++ and platform bindings; availability depends on build | Quantization ecosystem, flexible CPU control, KV-cache options, many model derivatives | ABI packaging, mmap behavior, correct model-specific tokenizer, support for hybrid/new architectures and GPU memory lifecycle |
| **MLX Swift** | Apple silicon/iOS/macOS, version-specific | Apple-native Metal acceleration, open ecosystem of example integrations | Verify iOS minimum OS, device compatibility, code/signing and bundled or downloaded artifact handling; not an Android solution |
| **Core ML / Apple foundation-model APIs** | Apple devices/OS versions with supported capabilities | Native platform integration, possible OS-level lifecycle benefits | Availability tied to device, region, OS, entitlement/API constraints; may not expose model choice and may not meet offline/quality needs |

**Default first experiments:** (A) Gemma E2B QAT + LiteRT-LM on Android; (B) LFM 1.2B + supported llama.cpp artifact/build as efficiency challenger; (C) Qwen3.5-4B on high-RAM device as quality challenger. For iOS, compare *same evaluation corpus* on LiteRT-LM Swift vs supported MLX Swift and llama.cpp/Metal stacks after compatibility is verified. Do not presume LiteRT-LM works with every LFM or Qwen derivative.

## 3. Integrating models safely: runtime adapter contract

Define stable interfaces across Kotlin Multiplatform and native Android/iOS adapters (illustrative types, not mandatory implementation):

```text
ModelDescriptor { id, source_url, artifact_sha256, model_family, modality, license,
                  tokenizer_id, template_version, quant_format, backend_compat,
                  minimum_ram_tested, expected_disk_size, supported_languages_claimed }

InferenceRequest { task_id, intent, locale, evidence_refs, system_policy_version,
                   style_profile_ref, max_input_tokens, max_output_tokens,
                   deadline_ms, priority, cancellation, output_schema }

InferenceResult { task_id, status, output, tokens_prefill, tokens_generated,
                  ttft_ms, total_ms, peak_rss_mb, failure_code,
                  verified_evidence_refs, model_build_id }

InferenceAdapter { probeCapability(), warmUp(), load(), generate(), cancel(), unload(), health() }
```

Platform-specific loading, memory-pressure callbacks, accelerator selection and native FFI remain in Android/iOS layers; **the shared core owns decisions, authorization boundaries and task budgets**. A fallback must not silently switch to network inference.

### Packaging and downloading

- Download artifacts only after clear user choice/consent; disclose model download size, on-disk size, expected memory and battery tradeoffs.
- Verify SHA-256 / signature, license, artifact-runtime ABI, device compatibility before install. Store in private sandbox; never expose an executable plugin via arbitrary downloaded model metadata.
- Use resumable downloads, atomic staging+rename, disk quota, sufficient-space preflight, rollback and user-visible progress.
- Do not load two large model instances simultaneously; preserve old known-good model only as long as safe on disk.
- Support uninstalling a model while keeping encrypted user data intact.
- Check mobile store policies for downloaded executable code vs data; model weights are data but packaging still needs review.

## 4. Techniques: order of operations

**P0 (build first)**
1. Event-first, rules-first: make common communication operations work with **zero model loaded**.
2. Single inference owner and bounded queue: one loaded generation model max; coalesce redundant tasks; cancel stale draft generation.
3. Lazy load + memory pressure response: classify cold/warm/hot states; shut down safely rather than being killed unpredictably; unload when memory pressure is high.
4. Hard context cap: evidence retrieval by conversation, time, source, recency, changes; avoid a whole thread in every prompt.
5. Output cap: a short appropriate reply needs fewer tokens than an explanation; user's preference controls length.
6. Cache **deterministic** artifacts (normalized messages, retrieval indexes, tokenized stable system instructions when runtime supports), invalidating on consent, message change or deletion.

**P1 (benchmark after baseline)**
7. Quantization-aware models / validated Q4-Q8 checkpoints with observed task-quality regression and peak RAM. QAT model and a third-party generic Q4 are not automatically equivalent.
8. KV-cache policy: bound context; measure prefill, decode, per-request KV growth; avoid retaining private KV past session/deletion policy. If supported, benchmark quantized KV and cache trimming without assuming no quality tradeoff.
9. Prefix caching: reuse only within the same privacy scope and matching *exact* system/policy/model/version; invalidate whenever user style, evidence or authorization changes.
10. Flash Attention / fused kernels: use vetted backend support; record GPU/RAM and thermal behavior; reject unstable acceleration.
11. NPU/GPU vs CPU: faster is not always lower energy. Record sustained throughput, thermal throttling and energy on each device.

**P2 (only if evidence supports)**
12. Speculative decoding / multi-token prediction (MTP): require checkpoints/runtime that implement it; benchmark *total* energy/RAM and matching output quality. Faster tokens/s may cost extra resident memory.
13. Model cascades: only if a *real* task-router saves energy overall. Two models loaded together may defeat efficiency; prefer rules + one model first.
14. Task-specific LoRA/fine tuning: defer until 100+ consented style/quality examples and measurable benefit; high maintenance and portability cost. Synthetic pretraining or private fine-tuning is not the immediate wedge.

### Simple memory accounting

```
Peak device impact = application baseline + DB/index + model mapped/resident weights
                   + tokenizer/runtime + activations + KV cache + GPU/NPU allocations
                   + queue buffers + OS/accounting overhead
```

Track PSS/RSS, GPU or hardware allocations where observable, **peak** rather than only end-state, GC/native heap, allocator fragmentation, cold/warm load and final released memory. Model *file size* is not model *working memory*. Some memory may be shared/mapped and RSS can double-count; specify measurement methodology. Record CPU-vs-GPU path and whether its memory counter underreports non-CPU memory.

## 5. Getting the best output: evidence-constrained drafting

Recommended pipeline:

1. **Retrieve** source messages, author direction, timestamp, edits, coverage warnings and the most recent confirmed decisions.
2. **Distill** an immutable facts package: `confirmed`, `contradicted`, `unknown`, `user_preferences`, and `allowed_actions`, with source IDs.
3. **Classify relationship/style** from user-provided label or sufficient local evidence, *never infer a sensitive trait*.
4. **Generate** with a compact system instruction, evidence excerpt and appropriate style profile + 1–3 explicitly authorized examples.
5. **Validate** structured critical fields (recipients, price, money, dates, commitments, meeting details, claims). Unknown authority => do not assert. Review user-visible final text before send.
6. **Capture feedback** only if user consented, based on actual final sent text/edits; no passive model fine-tuning.

Illustrative prompt contract:

```text
SYSTEM: You are a private drafting assistant. Only available evidence and explicitly
approved facts are authoritative. Do not invent commitments, prices or dates.
Never send; return a DRAFT only. Follow current relationship style profile.
If a required fact is unknown, ask or provide a neutral placeholder.

INPUT:
intent: reply_to_message
language: English / Hindi / Telugu / mixed (inferred confidence + user preference)
recipient_relationship: professional (user-confirmed)
style: concise, cordial, no exaggerated promises
verified_facts: [{fact_id, text, source_id, timestamp}]
unknowns: [proposed_delivery_date]
coverage: notification-only; earlier messages may be missing
latest_message: ...

OUTPUT JSON: {draft, unsupported_claims:[], clarification_required:boolean,
              evidence_ids:[], style_profile_version}
```

Use constrained decoding only when supported and benchmark its latency. Never treat an LLM's self-supplied evidence IDs as verified—the application must cross-check that referenced source IDs exist and actually support claims.

### Generation parameters

- Validate correct **model-specific chat template and special tokens**. Wrong templates can ruin quality while seeming to work.
- For fact extraction use low randomness + constrained schema. For natural drafting compare moderate sampling with robust factual validation. Do not enforce a single global temperature.
- Use brief responses when the user is brief; avoid default over-politeness, greetings and signoffs in casual threads.
- Keep mixed Hindi/Telugu evaluation separate from English; measure Unicode, transliteration preferences and honorifics.
- Distinguish assistant output, draft edits, provider-accepted sends and **confirmed delivered messages**.

## 6. Benchmarks and decision thresholds

Create at least 50 synthetic scenarios (see `08_QUALITY_GATES_AND_TEST_PLAN.md`). For each model/backend/device record:

- Exact model+quant SHA, runtime commit/version, device model, RAM, OS, power mode, temperature, thread counts, accelerator.
- Cold load sec, warm load sec, TTFT p50/p95, decode tokens/sec, full-task time p50/p95, peak system/app memory, final reclaimed memory, energy estimate and thermal sustained 15-minute degradation.
- Retrieval top-k recall, changed-fact recall, grounding, critical-error count, fluency/blind style preference, Hindi/Telugu code-switch accuracy.
- Draft with 300/1000/3000-token evidence budgets; compare correctness versus context size. Start with smaller real needs rather than advertising 128K.

Suggested **selection rule**: disqualify any model with a critical action/factual/privacy failure on acceptance corpus. Among remaining options, choose the Pareto-efficient model with acceptable naturalness, response time, footprint and language quality on target hardware. Model rankings are *deployment-specific*.

## 7. Key source material (official)

- Gemma 4 overview, memory and QAT: https://ai.google.dev/gemma/docs/core
- LiteRT-LM overview, Kotlin/Swift status and illustrative device benchmarks: https://developers.google.com/edge/litert-lm/overview
- Qwen3.5-4B model card (Apache-2.0, language and architecture claims): https://huggingface.co/Qwen/Qwen3.5-4B
- LFM2.5-1.2B-Instruct model card (8 listed languages, lfm1.0): https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct
- EmbeddingGemma 2 card (text encoder/Matryoshka dimensions): https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2
- llama.cpp architecture/backend ecosystem: https://github.com/ggml-org/llama.cpp
- MLX Swift: https://github.com/ml-explore/mlx-swift
- Apple Core ML: https://developer.apple.com/documentation/coreml

**Unverified until MINI runs it:** exact weights' device fit, usable Telugu/Hindi quality, artifact conversion support, runtime Swift readiness on actual minimum iOS version, quantization regression, power, sustained thermals, publisher performance claims and commercial license compliance.
