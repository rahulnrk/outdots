import type { Account, Approval, Draft, HubState, OutboxCommand, ReviewPayload } from './types';
import { evaluateReplyEligibility } from './eligibility';

function stableJson(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`;
  return `{${Object.entries(value as Record<string, unknown>)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, item]) => `${JSON.stringify(key)}:${stableJson(item)}`)
    .join(',')}}`;
}

export function reviewPayloadFromDraft(draft: Draft, policyVersion: string, grantVersion: string): ReviewPayload {
  return {
    accountId: draft.accountId,
    conversationId: draft.conversationId,
    route: draft.route,
    recipients: [...draft.recipients].sort(),
    subject: draft.subject,
    body: draft.body,
    evidence: [...draft.evidence].sort((a, b) => `${a.kind}:${a.id}:${a.revision}`.localeCompare(`${b.kind}:${b.id}:${b.revision}`)),
    policyVersion,
    grantVersion
  };
}

export async function digestReviewPayload(payload: ReviewPayload): Promise<string> {
  const input = stableJson(payload);
  const cryptoImpl = globalThis.crypto;
  if (cryptoImpl?.subtle) {
    const bytes = new TextEncoder().encode(input);
    const hash = await cryptoImpl.subtle.digest('SHA-256', bytes);
    return [...new Uint8Array(hash)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
  }

  let hash = 5381;
  for (let index = 0; index < input.length; index += 1) {
    hash = (hash * 33) ^ input.charCodeAt(index);
  }
  return `fallback-${(hash >>> 0).toString(16)}`;
}

export async function approveDraft(draft: Draft, policyVersion: string, grantVersion: string, now: string): Promise<{ approval: Approval; command: OutboxCommand }> {
  if (draft.status !== 'ready_for_review') {
    throw new Error('Only review-ready drafts can be approved.');
  }

  const digest = await digestReviewPayload(reviewPayloadFromDraft(draft, policyVersion, grantVersion));
  const expiresAt = new Date(Date.parse(now) + 2 * 60 * 1000).toISOString();
  const approval: Approval = {
    id: `approval-${draft.id}`,
    draftId: draft.id,
    reviewDigest: digest,
    approvedAt: now,
    expiresAt,
    consumed: false
  };
  const command: OutboxCommand = {
    id: `outbox-${draft.id}`,
    draftId: draft.id,
    reviewDigest: digest,
    policyVersion,
    grantVersion,
    route: draft.route,
    state: 'ready',
    createdAt: now
  };
  return { approval, command };
}

export type GatewayResult =
  | { kind: 'accepted'; receipt: string }
  | { kind: 'rejected'; reason: string }
  | { kind: 'unknown'; reason: string };

/** Injected provider boundary. The domain never talks to a network itself. */
export interface ProviderGateway {
  send(command: OutboxCommand, draft: Draft, account: Account): Promise<GatewayResult>;
}

/** Default when no real connector is configured: the send is refused, never simulated as accepted. */
export const unconfiguredGateway: ProviderGateway = {
  async send() {
    return { kind: 'rejected', reason: 'no_provider_connector_configured' };
  }
};

function failCommand(state: HubState, commandId: string, stateName: OutboxCommand['state'], failureReason: string, now: string): HubState {
  return {
    ...state,
    outbox: state.outbox.map((item) => item.id === commandId ? { ...item, state: stateName, lastAttemptAt: now, failureReason } : item)
  };
}

function latestInboundAt(state: HubState, draft: Draft): string | undefined {
  return [...state.messages]
    .filter((message) => message.conversationId === draft.conversationId && message.direction === 'inbound')
    .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))[0]?.occurredAt;
}

function accountForDraft(state: HubState, draft: Draft): Account | undefined {
  return state.accounts.find((item) => item.id === draft.accountId);
}

export interface DispatchOptions {
  gateway?: ProviderGateway;
}

export async function dispatchOutbox(state: HubState, commandId: string, now: string, options: DispatchOptions = {}): Promise<HubState> {
  const command = state.outbox.find((item) => item.id === commandId);
  if (!command) throw new Error(`Command not found: ${commandId}`);
  if (command.state !== 'ready') return state;

  const draft = state.drafts.find((item) => item.id === command.draftId);
  if (!draft) {
    return failCommand(state, commandId, 'expired_or_invalidated', 'draft_missing_for_approved_command', now);
  }

  const account = accountForDraft(state, draft);
  if (!account) {
    return failCommand(state, commandId, 'expired_or_invalidated', 'account_missing_for_approved_command', now);
  }

  const currentDigest = await digestReviewPayload(reviewPayloadFromDraft(draft, command.policyVersion, command.grantVersion));
  if (currentDigest !== command.reviewDigest) {
    return failCommand(state, commandId, 'expired_or_invalidated', 'review_payload_changed_since_approval', now);
  }

  const eligibility = evaluateReplyEligibility(account, command.route, now, latestInboundAt(state, draft));
  if (!eligibility.eligibleNow) {
    return failCommand(state, commandId, 'rejected', `route_recheck_failed:${eligibility.reasonCode}`, now);
  }

  const approval = state.approvals.find((item) => item.draftId === command.draftId && item.reviewDigest === command.reviewDigest);
  if (!approval || approval.consumed || Date.parse(approval.expiresAt) < Date.parse(now)) {
    return failCommand(state, commandId, 'expired_or_invalidated', 'approval_missing_consumed_or_expired', now);
  }

  // Consume the one-use approval before any network call, so a retry cannot reuse it.
  const consumed: HubState = {
    ...state,
    approvals: state.approvals.map((item) => item.id === approval.id ? { ...item, consumed: true } : item)
  };

  if (command.route === 'native_handoff') {
    return {
      ...consumed,
      outbox: consumed.outbox.map((item) => item.id === commandId ? { ...item, state: 'handoff_opened', lastAttemptAt: now, receipt: 'source-app-opened-unverified' } : item),
      drafts: consumed.drafts.map((item) => item.id === command.draftId ? { ...item, status: 'approved' } : item)
    };
  }

  const gateway = options.gateway ?? unconfiguredGateway;
  const result = await gateway.send(command, draft, account);

  if (result.kind === 'accepted') {
    return {
      ...consumed,
      outbox: consumed.outbox.map((item) => item.id === commandId ? { ...item, state: 'accepted_by_provider', lastAttemptAt: now, receipt: result.receipt } : item),
      drafts: consumed.drafts.map((item) => item.id === command.draftId ? { ...item, status: 'sent' } : item)
    };
  }

  if (result.kind === 'rejected') {
    return {
      ...consumed,
      outbox: consumed.outbox.map((item) => item.id === commandId ? { ...item, state: 'rejected', lastAttemptAt: now, failureReason: result.reason } : item)
    };
  }

  // Timeout or transport failure after the request may have reached the provider.
  // Never retry automatically; the outcome must be reconciled before any further attempt.
  return {
    ...consumed,
    outbox: consumed.outbox.map((item) => item.id === commandId ? { ...item, state: 'outcome_unknown', lastAttemptAt: now, failureReason: result.reason } : item)
  };
}
