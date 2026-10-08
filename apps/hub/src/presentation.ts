import type { Account, Draft, Observation, OutboxCommand, ReplyEligibility, SendRoute, WindowState } from '@mini/domain';

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

export interface OutcomeCopy {
  title: string;
  detail: string;
  tone: Tone;
}

const providerNames: Record<string, string> = {
  gmail: 'Gmail',
  outlook: 'Outlook',
  teams: 'Teams',
  whatsapp: 'WhatsApp',
  facebook: 'Messenger',
  instagram: 'Instagram',
  linkedin: 'LinkedIn'
};

export function providerName(provider: string): string {
  return providerNames[provider] ?? provider;
}

const reasonTexts: Record<string, string> = {
  no_provider_connector_configured: 'No provider connector is configured, so nothing was sent.',
  review_payload_changed_since_approval: 'The draft changed after approval. The old approval cannot be used.',
  policy_or_grants_changed_since_approval: 'Account permissions or provider policy changed after approval.',
  approval_missing_consumed_or_expired: 'The approval expired (two-minute limit) or was already used.',
  draft_missing_for_approved_command: 'The approved draft no longer exists.',
  account_missing_for_approved_command: 'The sending account no longer exists.',
  discarded_by_user: 'You discarded this approval.',
  fixture_provider_rejected: 'The simulated provider refused the message.',
  fixture_provider_timeout: 'The simulated provider did not answer in time.',
  gmail_send_grant_missing: 'Send permission for this Gmail account is missing.',
  customer_reply_window_expired: 'The reply window has closed.',
  missing_qualifying_inbound_event: 'No qualifying incoming message was found to open a reply window.',
  route_not_supported_by_capability_snapshot: 'This account does not support that route.'
};

export function reasonText(reason: string | undefined): string {
  if (!reason) return '';
  if (reason.startsWith('route_recheck_failed:')) {
    const inner = reason.slice('route_recheck_failed:'.length);
    return `Rechecked at send time and the route is no longer allowed. ${reasonTexts[inner] ?? inner}`;
  }
  if (reason.startsWith('dispatch_error:')) {
    return `The send attempt failed unexpectedly (${reason.slice('dispatch_error:'.length)}). It may or may not have reached the provider.`;
  }
  return reasonTexts[reason] ?? reason.replaceAll('_', ' ');
}

export function routeLabel(route: SendRoute): string {
  switch (route) {
    case 'api': return 'Provider API send';
    case 'native_handoff': return 'Open source app (you finish there)';
    case 'notification_reply': return 'Notification reply';
    default: return 'No route available';
  }
}

export function windowLabel(state: WindowState): string {
  switch (state) {
    case 'active': return 'Active';
    case 'expiring': return 'Expiring soon';
    case 'expired': return 'Expired';
    case 'unknown': return 'Unknown';
    default: return 'Not applicable (no reply window for this route)';
  }
}

export function eligibilityLabel(eligibility: ReplyEligibility): string {
  return eligibility.eligibleNow ? 'Allowed now' : 'Blocked';
}

export function eligibilityReasonText(reasonCode: string): string {
  const map: Record<string, string> = {
    source_app_handoff_user_completes_action: 'You finish the action in the source app. The hub cannot send for you.',
    gmail_send_grant_present: 'Send permission is granted for this account.',
    gmail_send_grant_missing: 'Send permission is missing for this account.',
    reply_window_active: 'The provider reply window is open.',
    reply_window_expiring: 'The provider reply window closes soon.',
    customer_reply_window_expired: 'The provider reply window has closed.',
    missing_qualifying_inbound_event: 'No qualifying incoming message; window state is unknown.',
    route_not_supported_by_capability_snapshot: 'This account does not support that route.'
  };
  return map[reasonCode] ?? reasonCode.replaceAll('_', ' ');
}

export function draftStatusLabel(status: Draft['status']): string {
  switch (status) {
    case 'proposed': return 'Needs your decision';
    case 'stale': return 'Replaced by a newer version';
    case 'ready_for_review': return 'Ready for exact review';
    case 'approved': return 'Approved';
    // The domain marks a draft "sent" when the provider accepted it. Acceptance is not delivery.
    case 'sent': return 'Accepted by provider';
    case 'blocked': return 'Blocked';
  }
}

export function outcomeCopy(command: OutboxCommand, provider: string, simulated: boolean): OutcomeCopy {
  const name = providerName(provider);
  const sim = simulated ? ' This demo uses a simulated provider fixture; nothing left this browser.' : '';
  switch (command.state) {
    case 'ready':
      return {
        title: 'Approved, not sent yet',
        detail: `Your exact payload is approved and queued locally. Nothing has been sent. Approval is valid for two minutes and is rechecked at send time.`,
        tone: 'info'
      };
    case 'dispatching':
      return {
        title: `Sending to ${name}…`,
        detail: 'Waiting for the provider response. The hub will not retry on its own.',
        tone: 'info'
      };
    case 'accepted_by_provider':
      return {
        title: `Accepted by ${name}`,
        detail: `${name} accepted the message for sending (receipt ${command.receipt ?? 'none'}). This does not confirm delivery or that anyone read it.${sim}`,
        tone: 'success'
      };
    case 'rejected':
      return {
        title: 'Not sent: rejected',
        detail: `${reasonText(command.failureReason)} You can review a new version and approve it again.${sim}`,
        tone: 'danger'
      };
    case 'outcome_unknown':
      return {
        title: 'Outcome unknown',
        detail: `The request may or may not have reached ${name}. It will not be resent automatically. Reconcile first so the message is not sent twice. ${reasonText(command.failureReason)}${sim}`,
        tone: 'warning'
      };
    case 'expired_or_invalidated':
      return {
        title: 'Not sent: approval no longer valid',
        detail: `${reasonText(command.failureReason)} Review the current draft and approve it again if you still want to send it.`,
        tone: 'danger'
      };
    case 'handoff_opened':
      return {
        title: `Handed off to ${name}, not sent`,
        detail: `${name} was opened with your reviewed text so you can finish there. The hub cannot see whether you sent it.${simulated ? ' In this browser demo the handoff is simulated; no app was opened.' : ''}`,
        tone: 'warning'
      };
    case 'externally_handled_unverified':
      return {
        title: 'Marked handled by you (unverified)',
        detail: `You said you completed this in ${name}. The hub has no receipt and cannot confirm it.`,
        tone: 'neutral'
      };
  }
}

/** What the hub structurally cannot know for this account and evidence, derived from the capability snapshot. */
export function unknownsFor(account: Account, observations: Observation[]): string[] {
  const caps = account.capabilities;
  const items: string[] = [];
  if (caps.readHistory === 'none') items.push('Anything before observation started (no history access).');
  if (caps.readHistory === 'bounded') items.push('History outside the synced range.');
  if (caps.inboundVisibility === 'partial') items.push('Incoming messages whose notifications were suppressed, grouped, muted or truncated.');
  if (caps.inboundVisibility === 'unknown') items.push('Whether all incoming messages are visible.');
  if (caps.outboundVisibility !== 'complete_within_scope') items.push('Replies you sent from the source app, so the thread may already be handled.');
  if (caps.resultVisibility === 'unverified') items.push('Whether a handed-off message was actually sent.');
  if (caps.resultVisibility === 'provider_accepted') items.push(`Delivery or read status. ${providerName(caps.provider)} only reports that it accepted a message.`);
  for (const observation of observations) {
    if (!observation.contentAvailable) items.push(`Content of observation ${observation.id} (hidden by the notification).`);
    if (observation.truncationState !== 'complete') items.push(`Whether observation ${observation.id} was truncated (${observation.truncationState}).`);
    if (observation.correlationConfidence !== 'high') items.push(`Exact thread match for observation ${observation.id} (match confidence ${observation.correlationConfidence}).`);
  }
  return items;
}

export function isSimulatedRoute(route: SendRoute): boolean {
  // No real connector exists in this build. Every route is fixture-backed.
  return route === 'api' || route === 'native_handoff' || route === 'notification_reply';
}

export function formatTime(value: string): string {
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', day: '2-digit', month: 'short' }).format(new Date(value));
}

export function formatClock(value: string): string {
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(value));
}
