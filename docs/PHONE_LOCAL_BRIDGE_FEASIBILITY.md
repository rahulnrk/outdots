# Phone-local bridge feasibility

**Date:** 11 October 2026. **Status:** contract + refusal tests; no on-device runtime packaged.

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

- No bridge binary, JNI, or Go/Python runtime in the APK.
- No UI for provider QR/device linking.
- No encrypted bridge session store.
- No live qualification.

Next implementation step, if authorized: pick **one** network, inspect a specific library's license and Android packaging on this machine, then add a failing device-session test before any dependency.
