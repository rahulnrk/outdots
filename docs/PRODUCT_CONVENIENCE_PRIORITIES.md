# Product convenience priorities

Product-owner direction, 2026-10-09: learn from Beeper's strengths and weaknesses
and make MINI a convenient daily communication tool, not merely a list of integrations.

## Evidence boundary

The original review screenshots were supplied on 2026-10-09 in
`C:\Users\nande\Downloads\32316_merged.pdf`. All 104 pages have now been reviewed
and mapped in [the Beeper/PocketPal analysis](research/beeper-pocketpal-analysis.md),
with a review ledger and explicit distinction between reports, developer replies,
store AI summaries and proposed MINI requirements. Earlier access to the shared
ChatGPT link did not expose its text; the PDF supplies the recovered evidence.

Beeper's own documentation establishes unified network bridges, on-device
connections and multiple accounts. Its engineering writing also establishes use
of reverse-engineered interfaces for some connections. Supported by an aggregator
does not establish provider endorsement or equivalent capabilities across networks.

Sources:
- https://developers.beeper.com/bridges/
- https://help.beeper.com/chat-networks/using-on-device-chat-network-connections-in-beeper
- https://www.beeper.com/engineering

## Build priorities

- Keep the convenience of one inbox, multiple accounts and fewer app switches.
  Gmail, Outlook, Teams and the chosen social networks belong in the connection
  plan; assess additional networks rather than promising unverified support.
- Make account setup understandable, with clear connection status, limits and
  recovery steps. Separate notification observation from authenticated sync.
- Prove message completeness, deduplication and reconnect behavior per provider.
  Show partial or stale coverage; never silently imply a complete inbox.
- Keep account contexts separate and delivery states accurate. Require exact user
  review before sending, and do not blindly retry a send with an uncertain outcome.
- Reduce noise through source selection and user-controlled filtering. Preserve
  important messages and existing attention choices.
- Keep phone-local processing and test battery use, memory pressure and background
  behavior on target devices. Do not trade basic inbox reliability for AI drafting.
- Validate English draft usefulness with real conversational context. Retain
  romanized Hindi/Telugu exploratory tests for later releases.

## What counts as progress

Prioritize a reliable complete daily workflow over connector count: connect an
account, receive a conversation, find what needs attention, review a useful draft,
reply through a qualified route and recover from a broken connection. Measure
setup completion, missed/duplicate messages, reply completion, recovery success,
draft usefulness and battery impact. Establish baselines before promising targets.

For each recovered review finding, record the original evidence, the MINI
requirement it motivates and a reproducible acceptance check.
