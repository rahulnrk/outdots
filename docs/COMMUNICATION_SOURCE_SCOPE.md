# Communication sources and connected accounts

Product-owner clarification: 2026-10-09.

MINI is a communication hub. The primary inbox must focus on the chosen sources:
Gmail, Outlook, Teams, WhatsApp, Facebook/Messenger, Instagram and LinkedIn.
Delivery, shopping, games, system and unrelated promotional app notifications
must not be captured into this workflow merely because notification access exists.

## Next increment: selected sources

- Let users explicitly choose which supported communication apps to observe.
- Apply exact package-based source eligibility before normalization and storage,
  and to the displayed inbox. Do not use loose substring matches as authorization.
- Preserve existing stored data; hide previously captured excluded sources rather
  than deleting it in this increment. Changes must not erase attention choices.
- Keep observed sources separate from authenticated provider accounts in the UI.
- Treat promotional content within an enabled communication app as a separate
  user-controlled filtering concern. An app allow-list alone does not identify it.
- Keep account identity unknown when the observation cannot establish it reliably.

## Connected accounts

Users must be able to connect multiple Gmail and other qualified provider accounts.
Each connection needs its own stable account ID, protected credentials, sync state,
permissions, conversation scope, attention state and private context. Display name
or contact name alone is not an account identity. Disconnecting an account must
stop its sync and invalidate access without affecting other connections.

Use official provider authorization where a qualified connector exists. Do not
collect social-media passwords, fabricate login flows, or present notification
permission as full authenticated account access. Unsupported connection routes
must show their actual limitation instead of a working-looking Connect button.

Sequence: selected-source filtering and settings first; genuine multi-account
Gmail authorization/sync next, subject to configured provider credentials; other
channels follow their capability qualification. Notification-only coverage and
provider mailbox coverage must remain visibly distinct. This does not claim that
any new provider connector is implemented.

English remains the MVP drafting language. Preserve existing runtime protections
and the pending recovery verification. Background reminders do not take priority
over this clarified source/account workflow.
