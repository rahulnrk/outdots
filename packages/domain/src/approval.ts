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

export async function dispatchOutbox(state: HubState, commandId: string, now: string): Promise<HubState> {
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

  const nextState = command.route === 'native_handoff' ? 'handoff_opened' : 'accepted_by_provider';
  return {
    ...state,
    approvals: state.approvals.map((item) => item.id === approval.id ? { ...item, consumed: true } : item),
    outbox: state.outbox.map((item) => item.id === commandId ? { ...item, state: nextState, lastAttemptAt: now, receipt: command.route === 'api' ? `provider-accepted-${command.id}` : 'source-app-opened-unverified' } : item),
    drafts: state.drafts.map((draft) => draft.id === command.draftId ? { ...draft, status: command.route === 'api' ? 'sent' : 'approved' } : draft)
  };
}
