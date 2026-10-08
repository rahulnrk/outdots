# JCode — Use Its Capabilities Strategically to Build MINI

**Reference date:** 2026-10-09. Distinguish the **JCode coding harness** from the **MINI mobile AI harness**. JCode's swarm, memory, tool routing and shared daemon improve engineering workflow; shipping a swarm on a phone would likely hurt memory, battery and reliability.

Official starting points: https://github.com/1jehuang/jcode · https://github.com/1jehuang/jcode/blob/master/docs/SWARM_TASK_GRAPH.md · https://github.com/1jehuang/jcode/blob/master/docs/MEMORY_ARCHITECTURE.md . **Before use**, JCode must report installed binary/version and available/working tools. The GitHub README can lead or lag installed version behavior.

## 1. Capability-to-decision table

| JCode feature | Best MINI use | Pitfall to avoid |
|---|---|---|
| **Swarm/task graph/DAG** | Parallel *independent* repo work (native contracts; benchmark fixtures; security audit) | Overlapping file ownership, expensive coordination, rate-limited workers |
| **Light vs deep swarm/reasoning effort** | Light for docs/tests; deep for architecture/privacy boundary only | Max reasoning on routine edits; don't assume effort setting affects all workers |
| **AgentGrep/code search** | Find precise implementations/symbols/tests before editing | Re-reading whole repo into context every task |
| **Shared daemon/headless workers** | Keep coordination overhead lean; use author's memory benchmark as inspiration | The ~3.1 MB per-worker author benchmark **does not** measure Android model memory |
| **Worker messaging + file-change notifications** | Assign owner files and coordinate dependency handoffs | Notifications do not replace merge review or race-free writes |
| **Model/provider routing** | Cheap model for mechanical tasks; stronger for root-cause/security; fallbacks | Provider exhaustion mid-task; surprise tool/model limits |
| **MCP/skills/project instructions** | Coding standards, build/testing helpers, controlled access to official docs | Auto-installing tools or adding secrets without review |
| **Session checkpoint/context compaction** | Preserve milestones, status and blockers | Accepting compressed summaries as test proof or as full project truth |
| **Automated browser QA (when available)** | Validate web UI, approvals, keyboard, accessibility | Calling a 200 HTTP response 'end-to-end UI tested' |
| **Ambient/proactive/self-dev** | **Defer** until reproducibility/security of regular workflow is proven | Unbounded edits, hidden token use, uncontrolled environment mutation |

## 2. Recommended topology: one coordinator + max three workers

```text
Coordinator (main, owns architecture decision and commits)
  ├── Worker A: Kotlin/native supervisor contract + tests (named files only)
  ├── Worker B: benchmark + synthetic eval fixtures (separate directories)
  └── Worker C: adversarial security review (read-only until findings signed off)
  ↓
Coordinator: independent full tests → diff/security review → documentation → commit
```

For small changes: **use coordinator alone**. Only spawn when work can run independently **and** output is worth coordination overhead. Stage dependencies in a DAG rather than starting all modules at once. Each agent receives acceptance criteria, test command, forbidden files and expected artifact.

## 3. Durable engineering task graph

```text
T0 Audit HEAD + existing docs/tests + Android runtime availability
  |
  +--> T1 Shared supervisor interface + fake runtime + lifecycle tests
  |
  +--> T2 Benchmark runner + synthetic event/quality fixtures
  |
  +--> T3 Read-only architecture/privacy threat review
  |
  +--> G1 Coordinator independent verification gate
           |
           +--> T4 Real Android encrypted storage runtime qualification
           +--> T5 Android notification pipeline + coverage health
           +--> G2 Device reliability/performance gate
                    |
                    +--> T6 First real local model/runtime A/B test
                    +--> T7 Limited authorized provider integration
                    +--> G3 Privacy/quality pilot decision
```

Task closure must include test output and commit evidence. A worker saying 'done' is not evidence. A failed test remains failed until the coordinator independently re-runs it. Never merge unfinished worker work just to increase completion percentages.

## 4. Files as durable context, not one 100K-token prompt

This pack is a **selective reference library**, not a command to read every line on every turn. `01_JCODE_START_PROMPT.md` is the active instruction; `00_START_HERE.md` is navigation. For supervisor work, read `05` and `08` first, `02` when selecting model ABI, `03/04` only when doing connector work, and `06` only during personalization implementation. Persist decisions in compact repo ADRs rather than bloating long-running chat context.

Suggested repository `AGENTS.md` sections: system boundaries; verified source-of-truth files; required tests; forbidden shortcuts; privacy; task graph; own-file isolation; test evidence; concise final report. Never overwrite an existing project's AGENTS.md blindly—merge with current rules and respect any higher-priority instructions.

## 5. Model and token-budget discipline

- Choose small code/search operations; do not invoke the strongest model for reading a README or formatting docs.
- Limit parallel workers to available model/provider budgets, not max theoretical swarm size.
- Prefer *small tracked changes* and `git diff` over broad rewrites.
- Use cached/symbol-specific search and file paths; avoid repeated full scans.
- Stop worker polling when no independent work remains; prefer event/worker completion notifications if installed tool supports them.
- Recover explicitly from rate limits: checkpoint task status and transfer/reassign only after checking touched files, rather than repeating an entire task blindly.
- Do not permit autonomous tool installation, production credential use, blind external network model inference, or privileged device commands without user consent.

## 6. Evidence gates and a ready-to-reuse worker assignment

```text
Workstream: [name]
Owner: [worker]
Files owned: [paths]
Read-only references: [paths]
Dependencies: [completed IDs]
Definition of done: [specific implementation and test]
Required test commands: [exact]
Forbidden actions: [no deps/no credentials/no bypass/no sending]
Expected output: [diff + test evidence + blockers]
Review gate: coordinator independently re-runs tests & reviews changed source
```

**Minimum coordinator final report:** actual HEAD, commits, current tests passed/failed, host vs phone measurements, things unverified, P0 blockers, and the next smallest release-blocking milestone. This is more valuable than 10 pages of optimistic progress logs.

## 7. Prior JCode log: lessons for MINI

The user-provided log reported ~59 web tests, 17 native JVM tests, debug APK with SQLCipher; native runtime was unverified without adb/emulator/device. It also documented provider rate limits and a conflict over same source files, and attempts to over-poll workers. Therefore the next run should start by **auditing current HEAD**, keeping workers file-isolated, checking SDK/ADB availability in multiple known locations, and rejecting device-performance claims unless device tests actually run.

## 8. Avoid copying JCode memory blindly

JCode's repository README and detailed memory architecture may describe distinct or evolving extraction/relevance implementations. Some relevance checks may use external inference. **MINI's communication memory must not inherit external memory assessment accidentally.** Take only resource-conscious principles: bounded retrieval, provenance, context compaction with original evidence retained, and TTL/invalidation. Implement private on-device decision-making with explicit user controls.
