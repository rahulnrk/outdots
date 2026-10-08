# Validation report

Run from `C:\Users\nande\Downloads\Mini` on 8 October 2026.

| Check | Result |
|---|---|
| `npm audit --audit-level=moderate` | Passed, 0 vulnerabilities |
| `npm test` | Passed, 6 domain tests |
| `npm run typecheck` | Passed for domain, publisher and hub workspaces |
| `npm run build` | Passed, production hub bundle generated |

## Behaviors covered by tests

- User priority override wins over AI priority.
- Unsupported send routes fail closed from connector capability snapshots.
- Expired 24-hour style customer windows block freeform API sends.
- Drafting exposes missing commercial decisions instead of inventing them.
- Approval digest changes when exact body changes.
- Outbox dispatch consumes approval once and avoids duplicate blind sends.

## Dependency security note

The first install reported Vite/Vitest development dependency vulnerabilities. The dependency tree was rebuilt on the current Vite/Vitest stack and audit now reports 0 vulnerabilities.
