# Next requirements: genuine multi-account provider connections

> **Historical milestone snapshot; not current implementation status.** Gmail/Outlook adapters and account flows now exist. The missing-connector/configuration claims below describe the earlier notification-only milestone and must not be applied to the current checkout or phone. Use [Product requirements](PRODUCT_REQUIREMENTS.md), especially PR-01 and PR-03–07, for current account/history/search/media gaps, and current source plus dated evidence for capability claims. Do not inspect or expose stored credentials to resolve this older text.

The selected-source milestone is notification observation only. A checked Gmail, Outlook, Teams, WhatsApp, Facebook/Messenger, Instagram, or LinkedIn source means that exact Android package may contribute notification observations. It does not establish a signed-in account, mailbox access, provider identity, or authenticated sync.

## Gmail authorization and sync prerequisites

Before implementing genuine multi-account Gmail coverage, MINI still needs:

1. A configured official Google OAuth client for the supported Android package and release signing identity.
2. A declared redirect/deep-link strategy and a verified OAuth flow using Google authorization endpoints, not a password form or a notification-access shortcut.
3. Explicit scopes, consent copy, and a review of whether Gmail metadata and message scopes require restricted-scope verification.
4. Secure credential storage for refresh tokens, token expiry and revocation handling, account disconnect, and invalidation of only the affected account.
5. A stable provider-account identifier from the authorized account. Display names, sender names, package IDs, and email snippets are not substitutes for account identity.
6. Per-account sync state, cursors or history IDs, retry/backoff, pagination, rate-limit handling, deduplication, and conversation scope isolation.
7. A mailbox-to-MINI normalization contract that remains visibly distinct from notification-only observations.
8. Tests for two Gmail accounts, account disconnect, revoked consent, expired credentials, partial sync, duplicate history, account-specific attention state, and encrypted local data boundaries.

## Current missing configuration and credentials

No provider OAuth client ID, client secret/configuration, verified redirect URI, granted mailbox scopes, authenticated account tokens, or qualified connector is present in this repository. This increment therefore intentionally provides no Connect Gmail or fake login control. The next connector work is blocked until those official provider credentials, application identity, scope decisions, and authorization configuration are supplied and reviewed.
