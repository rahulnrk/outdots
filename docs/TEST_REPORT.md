# Test report

Only commands actually executed are recorded here. Each entry lists the commit, command and observed result.

## 2026-10-08, commit e8ea976 (coordinator)

Run from `C:\Users\nande\Downloads\Mini`, Windows, Node from `C:\Program Files\nodejs`.

| Command | Result |
|---|---|
| `npm run typecheck` | Pass: domain, publisher, hub |
| `npm run build` | Pass: hub bundle 162.31 kB JS (52.39 kB gzip) |
| `npm test` (vitest 5.0.3) | Pass: 1 file, 13 tests |
| `npm audit --audit-level=moderate` (at dd0f709) | 0 vulnerabilities |

### Not yet executed

- No native (Gradle/Android) tests exist yet.
- No browser end-to-end (Playwright) tests exist yet.
- No performance, memory or battery measurements have been taken. Targets in architecture section 15 are unmeasured.
- No test has used a real provider, account or local model.
