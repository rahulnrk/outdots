# Phone-local bridge feasibility

**Date:** 11 October 2026. **Status:** WhatsApp phone-local **contract + encrypted envelope**; runtime **not packaged**.

This is not a claim that WhatsApp, Messenger, Instagram or LinkedIn work in MINI.

## Agreed product rule

Pilot plaintext for those four networks (plus WhatsApp Business *mobile app*, not Cloud API) must be processed on the phone. A remote Matrix homeserver is not an allowed substitute. That supersedes the earlier unresolved hosting question in [product decisions](PRODUCT_DECISIONS_2026-10-10.md). See [product audit](PRODUCT_AUDIT_AND_PLAN_2026-10-11.md) finding 8.

## What the code does today

| Check | Result | Evidence |
|---|---|---|
| Matrix `whoami` | Matrix user id, not a provider account | `PhoneLocalBridgePolicyTest.whoamiMatrixUserIsNotAWhatsAppAccount` |
| Send receipt | `matrix:{room}:{eventId}` = homeserver acceptance | `PhoneLocalBridgePolicyTest.matrixSendReceiptIsHomeserverAcceptanceNotProviderForwarding` |
| Phone-local session | Current adapter fails every required field | `PhoneLocalBridgePolicy.matrixHomeserverAdapterProof` |
| Dispatch | Third-party bridge sends are rejected | `remote_bridge_plaintext_forbidden` |
| Connect UI | Matrix entry never offered; `connectMatrixBridge` refuses | `ConnectRoutePolicyTest`, `AndroidConnectorRegistry.connectMatrixBridge` |
| Messenger | No Matrix route; notification-only plus a deferred Page API | `PhoneLocalBridgePolicyTest.messengerHasNoMatrixRouteAndIsStillAPilotBridgeNetwork` |

The Matrix Client-Server adapter remains in the tree as foundation code. It is not a product path.

## WhatsApp personal: inspection (this milestone)

Chosen candidate to inspect, **not packaged:** [whatsmeow](https://github.com/tulir/whatsmeow) (`go.mau.fi/whatsmeow`).

| Fact | Evidence | MINI implication |
|---|---|---|
| License | [MPL-2.0 LICENSE](https://github.com/tulir/whatsmeow/blob/main/LICENSE) fetched 2026-10-11 | Source of modified files must stay MPL-2.0; the rest of MINI can remain separately licensed |
| Protocol | Unofficial WhatsApp **web multi-device** client (QR / `GetQRChannel`, `PairPhone`, SQLite device store) | Same class of companion-device linking as WhatsApp Linked Devices, not Cloud API |
| Android packaging | Library is **Go**. No first-party AAR. Would need gomobile, a sidecar, or a rewrite | APK size, Play policy, JNI, battery and SQLCipher coexistence **not measured** |
| Session | Device keys live in a local SQL store in the example | MINI must wrap that blob in Keystore/SQLCipher; never paste a Matrix token |
| Identity | Linked device has a WhatsApp JID / phone | Required identity is E.164 (`+15551234567`), never `@user:homeserver` |
| Send proof | WhatsApp stanza id after protocol ack | Receipt prefix `whatsapp:` means provider forwarding, `matrix:` does not |

Official complementary doc: [WhatsApp Linked Devices](https://faq.whatsapp.com/1317564962315842). This is not an SDK and does not authorize an unofficial client.

### What landed in code

| Piece | Behavior |
|---|---|
| `WhatsAppPhoneSession` | Phone-local hosting; pairing/send fail with `whatsapp_runtime_not_packaged` until a client is injected |
| `InMemoryEncryptedBridgeSessionStore` | Opaque envelope copies; JVM tests |
| `AndroidKeystoreBridgeSessionStore` | AES-GCM Keystore envelope, no content logging |
| Catalog | `whatsappPhoneLocal` preferred over Matrix; status `DeferredExternalRequirement` |
| Accounts UI | WhatsApp still **not offered** |
| Dispatch | `PhoneLocalBridge` may use the exact-review API path; unprovisioned sender still rejects; remote Matrix still forbidden |

Tests: `WhatsAppPhoneSessionTest`. A fake linked client proves the **policy can be met**; the default client proves the **runtime is absent**.

No Go module, gomobile bind, JNI, or Gradle dependency was added.

## Candidates inspected from public docs, not packaged

These are research notes. License, Android packaging, memory, battery and session durability were **not** verified on a device in this milestone. No new Gradle dependency was added.

| Candidate | Why it was considered | Known limit for MINI |
|---|---|---|
| Beeper on-device connections | Shipping example of phone-local WhatsApp/Instagram/Messenger/LinkedIn | Not an SDK MINI can vendor. [Beeper on-device connections](https://help.beeper.com/chat-networks/using-on-device-chat-network-connections-in-beeper) |
| mautrix bridges | Existing adapter assumed this shape | Typically a **server** process. End-to-bridge encryption hides content from a homeserver; the **bridge still sees plaintext**. [mautrix encryption](https://docs.mau.fi/bridges/general/end-to-bridge-encryption.html) |
| whatsmeow / similar Go WhatsApp multi-device clients | Closest public multi-device protocol client | Go runtime on Android (gomobile or a sidecar) is unproven here: APK size, Play policy, session store, battery |
| Official Meta/LinkedIn personal inbox APIs | Would avoid a bridge | Not available for personal WhatsApp/Instagram/Messenger/LinkedIn inboxes. Page/Cloud APIs are different products |

End-to-bridge encryption is not phone-local processing.

## Required of the first real runtime

A packaged phone-local session for **one** network must:

1. Link a provider account (phone number, Instagram user, etc.), not `@user:homeserver`.
2. Store the session encrypted on the phone (Keystore/SQLCipher, same bar as other MINI secrets).
3. Keep plaintext on the device.
4. Survive process death and reconnect without pasting a homeserver token.
5. Report provider forwarding, not only local/homeserver acceptance.
6. Use the existing exact-review outbox; no send without approval.
7. Show coverage limits honestly.

Success on WhatsApp does not qualify Messenger, Instagram or LinkedIn.

## Not done

- No whatsmeow/gomobile/JNI binary in the APK.
- No QR linking UI.
- No live WhatsApp qualification.
- Messenger, Instagram and LinkedIn unchanged.

Next implementation step, if authorized: gomobile (or equivalent) spike of whatsmeow on this machine, record APK size and whether Keystore can wrap its device store, then replace `UnprovisionedWhatsAppClient` behind the existing contract. Do not enable a Matrix homeserver.
