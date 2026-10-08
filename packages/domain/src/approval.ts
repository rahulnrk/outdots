import type { Account, Approval, Draft, HubState, OutboxCommand, ReviewPayload, SendRoute, SourceRef } from './types';
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
    attachments: [...(draft.attachments ?? [])].sort((a, b) => a.id.localeCompare(b.id)),
    evidence: [...draft.evidence].sort((a, b) => `${a.kind}:${a.id}:${a.revision}`.localeCompare(`${b.kind}:${b.id}:${b.revision}`)),
    policyVersion,
    grantVersion
  };
}

export async function digestReviewPayload(payload: ReviewPayload): Promise<string> {
  const cryptoImpl = globalThis.crypto;
  if (!cryptoImpl?.subtle) throw new Error('cryptographic_digest_unavailable');
  const bytes = new TextEncoder().encode(stableJson(payload));
  const hash = await cryptoImpl.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(hash)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

function validInstant(value: string): boolean {
  return Number.isFinite(Date.parse(value));
}

function sourceVersionsFor(state: HubState, draft: Draft): SourceRef[] {
  return [
    ...state.messages.filter((item) => item.accountId === draft.accountId && item.conversationId === draft.conversationId).map((item) => ({ id: item.id, kind: 'message' as const, revision: item.revision })),
    ...state.observations.filter((item) => item.accountId === draft.accountId && item.conversationId === draft.conversationId).map((item) => ({ id: item.id, kind: 'observation' as const, revision: item.revision }))
  ].sort((a, b) => `${a.kind}:${a.id}`.localeCompare(`${b.kind}:${b.id}`));
}

function uniqueId(prefix: string, base: string, state: HubState): string {
  let candidate = `${prefix}-${base}`;
  let index = 2;
  while (state.approvals.some((item) => item.id === candidate) || state.outbox.some((item) => item.id === candidate)) candidate = `${prefix}-${base}-v${index++}`;
  return candidate;
}

export async function approveDraft(draft: Draft, account: Account, now: string, state?: HubState): Promise<{ approval: Approval; command: OutboxCommand }> {
  if (!validInstant(now)) throw new Error('invalid_approval_time');
  if (draft.status !== 'ready_for_review') throw new Error('Only review-ready drafts can be approved.');
  if (draft.accountId !== account.id) throw new Error('Account does not own draft.');
  if (draft.route === 'api' && draft.recipients.length === 0) throw new Error('API approval requires at least one recipient.');
  const currentState = state ?? { accounts: [], conversations: [], messages: [], observations: [], facts: [], actionItems: [], priorityOverrides: [], drafts: [], approvals: [], outbox: [] };
  if (state && !state.accounts.some((item) => item.id === account.id)) throw new Error('Account is not present in current state.');
  const policyVersion = account.capabilities.policyVersion;
  const grantVersion = grantVersionFor(account);
  const digest = await digestReviewPayload(reviewPayloadFromDraft(draft, policyVersion, grantVersion));
  const sourceVersions = state ? sourceVersionsFor(state, draft) : undefined;
  const base = `${draft.id}-${Date.parse(now)}`;
  const approval: Approval = { id: uniqueId('approval', base, currentState), draftId: draft.id, reviewDigest: digest, approvedAt: now, expiresAt: new Date(Date.parse(now) + 2 * 60 * 1000).toISOString(), consumed: false, sourceVersions };
  const command: OutboxCommand = { id: uniqueId('outbox', base, { ...currentState, approvals: [...currentState.approvals, approval] }), draftId: draft.id, reviewDigest: digest, policyVersion, grantVersion, route: draft.route, state: 'ready', createdAt: now, sourceVersions };
  return { approval, command };
}

export type GatewayResult =
  | { kind: 'accepted'; receipt: string }
  | { kind: 'rejected'; reason: string }
  | { kind: 'unknown'; reason: string };

export interface ProviderGateway { send(command: OutboxCommand, draft: Draft, account: Account): Promise<GatewayResult>; }
export const unconfiguredGateway: ProviderGateway = { async send() { return { kind: 'rejected', reason: 'no_provider_connector_configured' }; } };

export interface HandoffLauncher { open(command: OutboxCommand, draft: Draft, account: Account): Promise<{ kind: 'opened' } | { kind: 'unavailable'; reason: string }>; }
export interface DispatchOptions { gateway?: ProviderGateway; handoff?: HandoffLauncher; }

function failCommand(state: HubState, commandId: string, stateName: OutboxCommand['state'], failureReason: string, now: string): HubState {
  return { ...state, outbox: state.outbox.map((item) => item.id === commandId ? { ...item, state: stateName, lastAttemptAt: now, failureReason } : item) };
}

function latestInboundAt(state: HubState, draft: Draft): string | undefined {
  return [...state.messages].filter((message) => message.accountId === draft.accountId && message.conversationId === draft.conversationId && message.direction === 'inbound').sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))[0]?.occurredAt;
}
function accountForDraft(state: HubState, draft: Draft): Account | undefined { return state.accounts.find((item) => item.id === draft.accountId); }
export function grantVersionFor(account: Account): string { return `grants:${[...account.grants].sort().join('+') || 'none'}`; }

export function recoverInterruptedDispatches(state: HubState, now: string): HubState {
  return { ...state, outbox: state.outbox.map((item) => item.state === 'dispatching' ? { ...item, state: 'outcome_unknown', lastAttemptAt: now, failureReason: 'interrupted_during_dispatch' } : item) };
}

const dispatchLocks = new Map<string, Promise<HubState>>();

export function dispatchOutbox(state: HubState, commandId: string, now: string, options: DispatchOptions = {}): Promise<HubState> {
  const existing = dispatchLocks.get(commandId);
  if (existing) return existing;
  const operation = dispatchOutboxInternal(state, commandId, now, options).finally(() => dispatchLocks.delete(commandId));
  dispatchLocks.set(commandId, operation);
  return operation;
}

async function dispatchOutboxInternal(state: HubState, commandId: string, now: string, options: DispatchOptions): Promise<HubState> {
  const command = state.outbox.find((item) => item.id === commandId);
  if (!command) throw new Error(`Command not found: ${commandId}`);
  if (command.state !== 'ready') return state;
  if (!validInstant(now)) return failCommand(state, commandId, 'expired_or_invalidated', 'invalid_clock', now);
  const draft = state.drafts.find((item) => item.id === command.draftId);
  if (!draft) return failCommand(state, commandId, 'expired_or_invalidated', 'draft_missing_for_approved_command', now);
  if (draft.status !== 'approved' && draft.status !== 'ready_for_review') return failCommand(state, commandId, 'expired_or_invalidated', 'draft_not_dispatchable', now);
  const account = accountForDraft(state, draft);
  if (!account) return failCommand(state, commandId, 'expired_or_invalidated', 'account_missing_for_approved_command', now);
  const approval = [...state.approvals].filter((item) => item.draftId === command.draftId && item.reviewDigest === command.reviewDigest).sort((a, b) => b.approvedAt.localeCompare(a.approvedAt))[0];
  if (!approval || approval.consumed || !validInstant(approval.approvedAt) || Date.parse(now) < Date.parse(approval.approvedAt)) return failCommand(state, commandId, 'expired_or_invalidated', 'clock_skew_detected', now);
  if (Date.parse(approval.expiresAt) < Date.parse(now)) return failCommand(state, commandId, 'expired_or_invalidated', 'approval_missing_consumed_or_expired', now);
  if (account.capabilities.policyVersion !== command.policyVersion || grantVersionFor(account) !== command.grantVersion) return failCommand(state, commandId, 'expired_or_invalidated', 'policy_or_grants_changed_since_approval', now);
  const currentDigest = await digestReviewPayload(reviewPayloadFromDraft(draft, command.policyVersion, command.grantVersion));
  if (currentDigest !== command.reviewDigest) return failCommand(state, commandId, 'expired_or_invalidated', 'review_payload_changed_since_approval', now);
  if (command.sourceVersions && JSON.stringify(command.sourceVersions) !== JSON.stringify(sourceVersionsFor(state, draft))) return failCommand(state, commandId, 'expired_or_invalidated', 'source_changed_since_approval', now);
  const eligibility = evaluateReplyEligibility(account, command.route, now, latestInboundAt(state, draft));
  if (!eligibility.eligibleNow) return failCommand(state, commandId, 'rejected', `route_recheck_failed:${eligibility.reasonCode}`, now);
  const consumed: HubState = { ...state, approvals: state.approvals.map((item) => item.id === approval.id ? { ...item, consumed: true } : item), outbox: state.outbox.map((item) => item.id === commandId ? { ...item, state: 'dispatching', lastAttemptAt: now } : item) };
  if (command.route === 'native_handoff') {
    if (!options.handoff) return failCommand(consumed, commandId, 'outcome_unknown', 'native_handoff_unavailable', now);
    const handoff = await options.handoff.open(command, draft, account);
    if (handoff.kind === 'opened') return { ...consumed, outbox: consumed.outbox.map((item) => item.id === commandId ? { ...item, state: 'handoff_opened', lastAttemptAt: now } : item) };
    return failCommand(consumed, commandId, 'rejected', handoff.reason, now);
  }
  try {
    const result = await (options.gateway ?? unconfiguredGateway).send(command, draft, account);
    if (result.kind === 'accepted' && result.receipt.trim()) return { ...consumed, outbox: consumed.outbox.map((item) => item.id === commandId ? { ...item, state: 'accepted_by_provider', lastAttemptAt: now, receipt: result.receipt } : item), drafts: consumed.drafts.map((item) => item.id === command.draftId ? { ...item, status: 'sent' } : item) };
    if (result.kind === 'rejected') return failCommand(consumed, commandId, 'rejected', result.reason, now);
    return failCommand(consumed, commandId, 'outcome_unknown', result.kind === 'accepted' ? 'provider_receipt_missing' : result.reason, now);
  } catch {
    return failCommand(consumed, commandId, 'outcome_unknown', 'provider_call_failed', now);
  }
}
