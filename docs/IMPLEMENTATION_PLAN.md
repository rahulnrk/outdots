# Implementation plan and scope decisions

## Scope understood from the requirement

The product is a private, local-first communication hub. The user wants quality and architecture sustainability over a quick mockup. The requirements document is architecture-heavy and explicitly says it is not an implementation. The first code milestone should therefore build the rules that are hardest to retrofit later:

1. Source evidence and derived state stay separate.
2. Provider messages and notification observations have different coverage labels.
3. User decisions override AI suggestions.
4. Drafts are evidence-grounded and must expose missing business decisions.
5. Sending requires exact user review, payload-bound approval, route recheck and durable outbox state.
6. Publisher backend cannot receive private message content.

## Correction to the original delivery expectation

A fully native cross-platform app with live Gmail, Outlook, Teams, WhatsApp, Messenger, Instagram, LinkedIn, on-device LLM inference and provider-reviewed sending is not a single-step build from one markdown file. It requires external provider credentials, review flows, device testing, security review and native platform implementation.

The better approach is to build the product around a small, tested shared domain core first, then add native adapters one by one. This prevents UI screens and connector demos from becoming the real source of truth.

## Architecture chosen for this repository

- `packages/domain`: pure product logic and safety gates. No UI, no network, no credentials.
- `apps/hub`: React implementation of the hub experience using seeded local evidence. This is the first end-to-end slice and a fast product-quality reference for native UI.
- `packages/publisher`: minimal release/entitlement API skeleton with an explicit no-private-content surface.

## MVP acceptance checklist

English is the initial supported drafting language. Test romanized Hindi and
Telugu now as exploratory coverage for later builds; their quality does not gate
the English MVP. Native-script support also follows MVP. See
[MVP language scope](MVP_LANGUAGE_SCOPE.md) for evaluation and scoring rules.

- Gmail and WhatsApp notification records appear together but show different coverage.
- Inferred and user-set priority are both visible and user override wins.
- Draft generation refuses to invent the user's commercial decision.
- Approval is bound to exact account, recipients, body, evidence versions and route.
- WhatsApp notification route uses native handoff, not a false API send.
- Publisher API has no endpoint for uploading inbox content, prompts or memory.

## Known gaps

- Native Android/KMP project is not created yet.
- SQLCipher, Android Keystore, iOS Keychain, WorkManager and platform notifications are not wired.
- Gmail/Microsoft/Meta OAuth and provider review are not configured.
- Local generative model runtime is behind an interface and not embedded.
- Encryption is documented as required but not claimed by the demo web app.
