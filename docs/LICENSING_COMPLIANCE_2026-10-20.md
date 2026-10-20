# Licensing compliance review: mautrix-meta, mautrix-linkedin and the MINI runtime (20 Oct 2026)

> Engineering analysis, not legal advice. Have counsel confirm before any
> distribution of a build that contains the protocol runtime.

## 1. What the upstream licenses actually say

Both repositories, at the exact versions MINI pins, ship two license files.

| Repo | Pinned | `LICENSE` | `LICENSE.exceptions` |
|---|---|---|---|
| [mautrix/meta](https://github.com/mautrix/meta) | `v0.2610.0` | GNU AGPL v3 (SHA-256 `0D96A4FF68AD6D4B…`) | Exceptions for **Beeper** (embed in clients/servers, distribute collective work without applying AGPL to the whole) and **Element** (ship binaries in server bundles). Valid only if modifications to mautrix-meta stay public under AGPL v3+. |
| [mautrix/linkedin](https://github.com/mautrix/linkedin) | `v0.2609.0` | GNU AGPL v3 (identical text and hash) | Same two exceptions, same condition. |

The exceptions are **named grants to Beeper and Element only**. They are not
general linking exceptions and do not extend to MINI. Without our own grant,
MINI receives these packages under plain AGPL v3 or later.

### Obligations that apply when MINI *conveys* (distributes) a build

Running a build privately on the owner's own phone is not conveying (AGPL s2).
The moment an APK reaches anyone else (testers, Play Store, sideload link), these apply:

| AGPL section | Requirement | Practical meaning for MINI |
|---|---|---|
| s5(c) | License the **entire work based on the Program** under AGPL | Go + gomobile statically links everything into one `libgojni.so`, called from Kotlin via JNI with intimate data exchange. That is one combined work, not an "aggregate". The Kotlin app is very likely in scope too. |
| s6 | Ship or offer **Corresponding Source** (all source plus build scripts needed to regenerate and install the object code) | Whole repo needed to rebuild the APK: `native/whatsappRuntime`, `go.mod`/`go.sum`, `build-android.ps1`, Gradle files, Kotlin app. |
| s6 + "User Product" | Installation Information for consumer devices | Users must be able to install a modified build. Fine for Android sideloading; we must not block it. |
| s5(a) | Prominent notice of modifications with dates | MINI does not modify upstream sources today (only wraps them). Keep it that way or record each change. |
| s5(d) | **Appropriate Legal Notices** in every interactive UI | An in-app screen with copyright, no-warranty statement, license name and how to view the license. |
| s13 | Offer source to users interacting remotely | MINI has no server; the s6 offer covers it, but keep the offer in the app. |
| s10 / s12 | No additional restrictions; no terms that contradict the license | Our ToS/EULA cannot forbid reverse engineering or modification of the covered work. |

## 2. What we were missing (gaps found)

| # | Gap | Severity | Status |
|---|---|---|---|
| G1 | **libsignal (GPL-3.0) was not listed anywhere.** `go.mau.fi/libsignal v0.2.2` is linked by `waruntime` (whatsmeow) and `msgruntime`. The "production" WhatsApp runtime is therefore already copyleft-encumbered, independent of the AGPL decision. NOTICE claimed whatsmeow (MPL-2.0) was the only obligation. | **Critical** | Recorded in this doc + NOTICE |
| G2 | NOTICE listed the wrong `LICENSE` SHA-256 for mautrix-meta (`E0EEDB…`). Both repos' LICENSE hash to `0D96A4FF…`. | Medium | Fixed in NOTICE |
| G3 | `msgruntime` (Messenger) not documented in NOTICE; it links mautrix-meta **and** libsignal and whatsmeow. | High | Fixed in NOTICE |
| G4 | No complete third-party inventory. 58 Go modules are linked; only 6 were named. Permissive licenses (MIT/BSD/ISC/Apache-2.0) still require their notice text to ship with the binary. | High | `license-inventory.ps1` + `THIRD_PARTY_LICENSES.md` added |
| G5 | `github.com/beeper/poly1305` has **no LICENSE file**. Source headers show it is a fork of `golang.org/x/crypto` (BSD-3-Clause, Go Authors). | Low | Classified via override; ship Go BSD text with it |
| G6 | No in-app "Open-source licenses" screen. App ships only two font OFL texts in `assets/licenses`. | High (blocks any distribution) | Open |
| G7 | No Corresponding Source offer or source archive process. | High (blocks any distribution) | Open |
| G8 | Release builds currently exclude the runtime only because the `classes.jar`/JNI are wired as `debugImplementation` / `debug` sourceSet. There is no explicit guard; one Gradle edit could ship AGPL code silently. | High | Guard added (see s4) |
| G9 | Repo has no top-level LICENSE for MINI's own code, so the product's licensing posture is undefined. | Medium | Decision required (s3) |

## 3. Options for the product and recommendation

| Option | What it means | Pros | Cons |
|---|---|---|---|
| **A. License MINI under AGPL-3.0-or-later** | Publish the whole app source; every APK ships with license screen + source link | Fully compliant; same model as many open messaging clients; keeps phone-local architecture; no dependence on upstream permission | Competitors may fork; any proprietary AI/inference code in the APK must also be AGPL or moved out of the combined work; Play Store is fine with AGPL apps |
| **B. Ask the mautrix developers for a MINI exception** (like Beeper/Element got) | Email Tulir / mautrix maintainers, offer sponsorship or commercial terms | Keeps MINI proprietary; upstream has precedent for granting this | Not guaranteed; libsignal (GPL-3.0) still needs the same answer from its copyright holders, and whatsmeow ships it, so you need both |
| **C. Split MINI into open "connector runtime" + proprietary app via a real process/IPC boundary** | Runtime runs as a separate AGPL app or service, MINI talks to it over a documented local protocol | Proprietary UI possible | Legally grey (FSF treats intimate IPC as one program); worse UX; a lot of engineering |
| **D. Clean-room independent clients** | Write Messenger/Instagram/LinkedIn protocol code without mautrix | No AGPL | Enormous effort; also need non-GPL Signal crypto for WhatsApp and Messenger E2EE |
| **E. Don't distribute protocol connectors** | Ship only official-API connectors (Gmail, Outlook, notifications) | Simple | Loses WhatsApp, Instagram, Messenger, LinkedIn phone-local, which is the core product direction |

**Recommendation:** pursue **B and A in parallel**.

1. Send the exception request now (template in s6). It is the only path that keeps MINI proprietary and still uses the best available protocol engine.
2. Prepare everything Option A requires anyway (it is also what an exception would require for upstream modifications), so we can ship immediately if B is declined or slow.
3. Because libsignal is GPL-3.0, even a WhatsApp-only release is copyleft today. Treat the AGPL/GPL decision as one decision covering **all** phone-local connectors.

## 4. Changes made in this review

- `native/whatsappRuntime/NOTICE` rewritten: correct hashes, libsignal GPL-3.0, msgruntime, exception text summary, distribution rule.
- `native/whatsappRuntime/license-inventory.ps1`: regenerates `THIRD_PARTY_LICENSES.md` from the real `go list -deps` graph of all four runtime packages; exits non-zero if any module's license is unknown.
- `native/whatsappRuntime/THIRD_PARTY_LICENSES.md`: 58 linked modules, license, file hash, which runtime package pulls each in.
- Release guard in `native/androidApp/build.gradle.kts`: release tasks fail if the gomobile runtime is on the release classpath or in release `jniLibs`, unless `-Pmini.licenseDecision=agpl` (or an explicit exception flag) is set.

## 5. Checklist to make distribution "the best" (fully compliant and user-respecting)

Do these before any APK leaves the owner's device:

1. **Decide** A vs B (and libsignal) and record the decision in `docs/CONNECTOR_ROADMAP.md`.
2. **Root LICENSE**: add `LICENSE` (AGPL-3.0 text) at repo root if A; add `LICENSE.exceptions`-style file if B is granted.
3. **In-app licenses screen** (Settings, "Open-source licenses"):
   - MINI copyright, "provided without warranty", license name, link to full text.
   - Render `THIRD_PARTY_LICENSES.md` plus every full license text (AGPL, GPL, MPL, Apache-2.0, BSD, MIT, ISC, OFL).
   - "Get source code" link to the exact tagged source for this build.
4. **Corresponding Source per release**: tag each release, publish a source archive containing the full repo (Kotlin + Go + `go.mod`/`go.sum` + build scripts + pinned toolchain/NDK versions) next to the APK. Keep it available at least as long as the binary is offered.
5. **Reproducible build notes**: document Go `1.26.0`, NDK `26.1.10909125`, `gomobile` revision, `-ldflags`, so a user can rebuild `libgojni.so`.
6. **Upstream modifications policy**: never patch vendored mautrix/whatsmeow/libsignal sources in place. If a patch is needed, keep it in a public fork with dated change notes (AGPL s5(a)) and pin to it with `replace`.
7. **MPL-2.0 file-level obligations** (whatsmeow, go.mau.fi/util, mautrix, zeroconfig): source of those files (and any modifications) must be available; covered by the Corresponding Source archive.
8. **Apache-2.0 NOTICE files** (thrift, flatbuffers, klauspost/compress, goid, yaml.v3): include any upstream `NOTICE` file contents in the licenses screen.
9. **ToS/privacy policy**: no clause forbidding modification or reverse engineering of the covered work.
10. **CI gate**: run `license-inventory.ps1` on every build; fail on unknown license or on a newly introduced GPL/AGPL module that is not acknowledged here.
11. **Provider terms are separate from copyright licenses.** AGPL compliance does not address Meta/LinkedIn terms of service for unofficial clients. Track that risk separately (account bans, cease-and-desist) and keep connectors clearly labelled as unofficial with owner opt-in.

## 6. Exception request template (Option B)

> To: mautrix maintainers (Tulir Asokan), via #meta:maunium.net / #linkedin:maunium.net or the contact on maunium.net
>
> Subject: Licensing exception request for embedding mautrix-meta / mautrix-linkedin in a phone-local Android client
>
> We build MINI, an Android app that runs messaging protocol clients entirely on the user's phone (no server, no Matrix homeserver). We link `pkg/messagix`, `pkg/instameow` and `pkg/linkedingo` unmodified into a single gomobile library. We would like an exception similar to the one granted to Beeper, allowing us to distribute the collective work without applying AGPL to the whole app. We commit to keeping any modifications to mautrix code public under AGPL v3+, and we are open to sponsorship or a commercial arrangement. We also use `go.mau.fi/libsignal` (GPL-3.0) via whatsmeow; please let us know who holds copyright there so we can ask the same question.

## 7. Current device and build posture

- Production `app.mini.hub` debug builds and `app.mini.hub.rtprobe` contain the runtime. **They are private developer builds and must not be shared** until s5 is complete.
- Release variant does not include the runtime, and the new Gradle guard keeps it that way.
- LinkedIn remains BLOCKED for product reasons (Cloudflare); licensing does not change that.
