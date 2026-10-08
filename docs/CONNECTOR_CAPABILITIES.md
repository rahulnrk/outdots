# Connector capabilities and limitations

Updated 2026-10-08. This matrix describes the repository's current qualification status, not a promise that an
unconfigured provider works. No live credentials or provider calls were used.

| Source/account | Read path | Outbound path | Privacy/coverage boundary | Repository status |
|---|---|---|---|---|
| Gmail user mailbox | Official Gmail API with user OAuth and history cursor is the intended path | Gmail API send after exact review and current grant check | Scope, token, cursor expiry, edits/deletions and provider acceptance must be represented | Synthetic fixture only. Live OAuth/client consent blocked |
| Outlook user/work mailbox | Microsoft Graph delegated permissions are the intended path | Graph sendMail only after account-scoped grant and exact review | Tenant/admin consent, throttling, token expiry and mailbox scope apply | Not implemented |
| Teams | Microsoft Graph account/tenant-specific APIs only where approved | Route depends on qualified account and API capability | Tenant policies, app permissions and incomplete message visibility must remain visible | Not implemented |
| WhatsApp personal | Android notification listener observations only, with explicit opt-in | Native source-app handoff; no provider receipt | Observations are partial. History and outbound state may be absent. Never call it a complete thread | Seeded observation in web harness; Android listener not implemented |
| WhatsApp Business Platform | Separate Meta business account/API qualification would be required | API route only after official account permissions and review | Not equivalent to personal WhatsApp. Webhook/content route must be checked against local-only contract | Not implemented, unverified |
| Instagram | Account type and Meta permissions must be qualified separately | Only qualified professional/business API route | Personal account coverage and API restrictions cannot be inferred from UI labels | Not implemented |
| Facebook/Messenger | Page/business API route differs from personal accounts | Qualified Page messaging route only | User conversations, page messages and webhook privacy need separate review | Not implemented |
| LinkedIn | Product-approved API access is required | No send route is assumed | Marketing/community APIs are not proof of private mailbox access | Not implemented |
| Android notifications | `NotificationListenerService`, explicit user permission, app/package allow-list | None by itself | Fragmented observations, notification suppression/grouping and app lifecycle gaps are first-class | Contract only |
| iOS | No Android-style cross-app notification history claim | User-mediated share/import or qualified provider API | iOS platform limits require native validation on macOS/Xcode | Blocked in Windows environment |

## Official documentation starting points

- Gmail API overview: https://developers.google.com/gmail/api/guides
- Gmail history resource: https://developers.google.com/gmail/api/reference/rest/v1/users.history
- Microsoft Graph mail: https://learn.microsoft.com/graph/api/resources/mail-api-overview
- Microsoft Graph Teams: https://learn.microsoft.com/graph/teams-concept-overview
- Android notification listener: https://developer.android.com/reference/android/service/notification/NotificationListenerService
- WhatsApp Cloud API overview: https://developers.facebook.com/docs/whatsapp/cloud-api/overview
- Messenger Platform: https://developers.facebook.com/docs/messenger-platform
- Instagram Graph API: https://developers.facebook.com/docs/instagram-api
- LinkedIn developer products: https://learn.microsoft.com/linkedin/

These links identify official starting points. They do not by themselves qualify an account, permission set or
privacy-preserving implementation. Provider verification, OAuth consent, policy review and controlled fixtures are
required before changing a row to released.
