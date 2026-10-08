import { describe, expect, it } from 'vitest';
import {
  approveDraft,
  createCapability,
  createDemoState,
  createDraft,
  digestReviewPayload,
  dispatchOutbox,
  effectivePriority,
  evaluateReplyEligibility,
  grantVersionFor,
  retrieveContext,
  reviewPayloadFromDraft,
  setPriorityOverride
} from '../src';

const now = '2026-10-08T12:00:00.000Z';

describe('priority', () => {
  it('lets explicit user priority override AI priority', () => {
    const state = createDemoState();
    const conversation = state.conversations.find((item) => item.id === 'conv-gmail-1')!;
    const overridden = setPriorityOverride(state, conversation.id, 'low', 'Handled by teammate', now);

    expect(effectivePriority(conversation, overridden.priorityOverrides)).toEqual({ priority: 'low', source: 'user' });
  });
});

describe('eligibility', () => {
  it('blocks routes that are not in the capability snapshot', () => {
    const account = {
      id: 'wa-business-demo',
      displayName: 'WhatsApp demo',
      provider: 'whatsapp' as const,
      compartment: 'business' as const,
      capabilities: createCapability('whatsapp'),
      grants: []
    };

    const result = evaluateReplyEligibility(account, 'api', now, '2026-10-07T10:00:00.000Z');

    expect(result.eligibleNow).toBe(false);
    expect(result.reasonCode).toBe('route_not_supported_by_capability_snapshot');
  });

  it('marks a 24-hour customer reply window as expired for supported API routes', () => {
    const account = {
      id: 'meta-demo',
      displayName: 'Meta business route',
      provider: 'instagram' as const,
      compartment: 'business' as const,
      capabilities: createCapability('instagram', { sendRoutes: ['api'] }),
      grants: ['send']
    };

    const result = evaluateReplyEligibility(account, 'api', now, '2026-10-07T10:00:00.000Z');

    expect(result.windowState).toBe('expired');
    expect(result.eligibleNow).toBe(false);
  });
});

describe('drafting and approval', () => {
  it('does not invent missing price/date decisions', () => {
    const state = createDemoState();
    const account = state.accounts.find((item) => item.id === 'acct-gmail-rahul')!;
    const context = retrieveContext(state, 'conv-gmail-1');
    const draft = createDraft(state, account, 'conv-gmail-1', context, { stance: 'accept' }, now);

    expect(draft.status).toBe('proposed');
    expect(draft.missingDecisions).toContain('Confirm the price or counter-price.');
    expect(draft.missingDecisions).toContain('Confirm the delivery date or counter-date.');
  });

  it('changes approval digest when exact payload changes', async () => {
    const state = createDemoState();
    const account = state.accounts[0];
    const context = retrieveContext(state, 'conv-gmail-1');
    const draft = createDraft(state, account, 'conv-gmail-1', context, { stance: 'accept', price: '₹80,000', date: 'Friday' }, now);

    const original = await digestReviewPayload(reviewPayloadFromDraft(draft, account.capabilities.policyVersion, 'grants-v1'));
    const edited = await digestReviewPayload(reviewPayloadFromDraft({ ...draft, body: `${draft.body}\nP.S. edited` }, account.capabilities.policyVersion, 'grants-v1'));

    expect(original).not.toEqual(edited);
  });

  it('consumes approval once and refuses blind duplicate dispatch', async () => {
    const state = createDemoState();
    const account = state.accounts[0];
    const context = retrieveContext(state, 'conv-gmail-1');
    const draft = createDraft(state, account, 'conv-gmail-1', context, { stance: 'accept', price: '₹80,000', date: 'Friday' }, now);
    const { approval, command } = await approveDraft(draft, account, now);
    const readyState = { ...state, drafts: [draft], approvals: [approval], outbox: [command] };
    const gateway = { send: async () => ({ kind: 'accepted' as const, receipt: 'gmail-msg-once' }) };

    const afterFirst = await dispatchOutbox(readyState, command.id, '2026-10-08T12:00:30.000Z', { gateway });
    const afterSecond = await dispatchOutbox(afterFirst, command.id, '2026-10-08T12:00:45.000Z', { gateway });

    expect(afterFirst.outbox[0].state).toBe('accepted_by_provider');
    expect(afterFirst.approvals[0].consumed).toBe(true);
    expect(afterSecond.outbox[0].state).toBe('accepted_by_provider');
    expect(afterSecond.outbox[0].receipt).toBe('gmail-msg-once');
  });

  it('invalidates an approved command if the reviewed payload changes before dispatch', async () => {
    const state = createDemoState();
    const account = state.accounts[0];
    const context = retrieveContext(state, 'conv-gmail-1');
    const draft = createDraft(state, account, 'conv-gmail-1', context, { stance: 'accept', price: '₹80,000', date: 'Friday' }, now);
    const { approval, command } = await approveDraft(draft, account, now);
    const tamperedState = {
      ...state,
      drafts: [{ ...draft, body: `${draft.body}\nP.S. changed after approval` }],
      approvals: [approval],
      outbox: [command]
    };

    const result = await dispatchOutbox(tamperedState, command.id, '2026-10-08T12:00:30.000Z');

    expect(result.outbox[0].state).toBe('expired_or_invalidated');
    expect(result.outbox[0].failureReason).toBe('review_payload_changed_since_approval');
    expect(result.approvals[0].consumed).toBe(false);
  });

  it('does not reuse command identity when a draft is regenerated in the same instant', async () => {
    const state = createDemoState();
    const account = state.accounts[0];
    const context = retrieveContext(state, 'conv-gmail-1');
    const first = createDraft(state, account, 'conv-gmail-1', context, { stance: 'accept', price: '₹80,000', date: 'Friday' }, now);
    const second = createDraft(state, account, 'conv-gmail-1', context, { stance: 'counter', price: '₹90,000', date: 'Monday' }, now);
    const firstApproval = await approveDraft(first, account, now);
    const secondApproval = await approveDraft(second, account, now);

    expect(secondApproval.command.id).not.toBe(firstApproval.command.id);
    expect(secondApproval.approval.id).not.toBe(firstApproval.approval.id);
  });

  it('never reports provider acceptance when no connector is configured', async () => {
    const state = createDemoState();
    const account = state.accounts[0];
    const context = retrieveContext(state, 'conv-gmail-1');
    const draft = createDraft(state, account, 'conv-gmail-1', context, { stance: 'accept', price: '₹80,000', date: 'Friday' }, now);
    const { approval, command } = await approveDraft(draft, account, now);
    const readyState = { ...state, drafts: [draft], approvals: [approval], outbox: [command] };

    const result = await dispatchOutbox(readyState, command.id, '2026-10-08T12:00:30.000Z');

    expect(result.outbox[0].state).toBe('rejected');
    expect(result.outbox[0].failureReason).toBe('no_provider_connector_configured');
    expect(result.outbox[0].receipt).toBeUndefined();
  });

  it('records acceptance only from an injected provider receipt', async () => {
    const state = createDemoState();
    const account = state.accounts[0];
    const context = retrieveContext(state, 'conv-gmail-1');
    const draft = createDraft(state, account, 'conv-gmail-1', context, { stance: 'accept', price: '₹80,000', date: 'Friday' }, now);
    const { approval, command } = await approveDraft(draft, account, now);
    const readyState = { ...state, drafts: [draft], approvals: [approval], outbox: [command] };
    const gateway = { send: async () => ({ kind: 'accepted' as const, receipt: 'gmail-msg-test-id' }) };

    const result = await dispatchOutbox(readyState, command.id, '2026-10-08T12:00:30.000Z', { gateway });

    expect(result.outbox[0].state).toBe('accepted_by_provider');
    expect(result.outbox[0].receipt).toBe('gmail-msg-test-id');
    expect(result.drafts[0].status).toBe('sent');
  });

  it('marks an ambiguous timeout as outcome unknown and never retries it', async () => {
    const state = createDemoState();
    const account = state.accounts[0];
    const context = retrieveContext(state, 'conv-gmail-1');
    const draft = createDraft(state, account, 'conv-gmail-1', context, { stance: 'accept', price: '₹80,000', date: 'Friday' }, now);
    const { approval, command } = await approveDraft(draft, account, now);
    const readyState = { ...state, drafts: [draft], approvals: [approval], outbox: [command] };
    let calls = 0;
    const gateway = { send: async () => { calls += 1; return { kind: 'unknown' as const, reason: 'timeout' }; } };

    const first = await dispatchOutbox(readyState, command.id, '2026-10-08T12:00:30.000Z', { gateway });
    const second = await dispatchOutbox(first, command.id, '2026-10-08T12:00:45.000Z', { gateway });

    expect(first.outbox[0].state).toBe('outcome_unknown');
    expect(first.approvals[0].consumed).toBe(true);
    expect(second.outbox[0].state).toBe('outcome_unknown');
    expect(calls).toBe(1);
  });

  it('blocks dispatch when a send grant is revoked after approval', async () => {
    const state = createDemoState();
    const account = state.accounts[0];
    const context = retrieveContext(state, 'conv-gmail-1');
    const draft = createDraft(state, account, 'conv-gmail-1', context, { stance: 'accept', price: '₹80,000', date: 'Friday' }, now);
    const { approval, command } = await approveDraft(draft, account, now);
    const revokedAccount = { ...account, grants: account.grants.filter((grant) => grant !== 'send') };
    const revokedState = {
      ...state,
      accounts: state.accounts.map((item) => item.id === account.id ? revokedAccount : item),
      drafts: [draft],
      approvals: [approval],
      outbox: [command]
    };
    const gateway = { send: async () => ({ kind: 'accepted' as const, receipt: 'must-not-send' }) };

    const result = await dispatchOutbox(revokedState, command.id, '2026-10-08T12:00:30.000Z', { gateway });

    expect(result.outbox[0].state).toBe('expired_or_invalidated');
    expect(result.outbox[0].failureReason).toBe('policy_or_grants_changed_since_approval');
    expect(result.outbox[0].receipt).toBeUndefined();
    expect(result.approvals[0].consumed).toBe(false);
  });

  it('rechecks current route capability before dispatching', async () => {
    const state = createDemoState();
    const account = state.accounts[0];
    const context = retrieveContext(state, 'conv-gmail-1');
    const draft = createDraft(state, account, 'conv-gmail-1', context, { stance: 'accept', price: '₹80,000', date: 'Friday' }, now);
    const { approval, command } = await approveDraft(draft, account, now);
    const revokedState = {
      ...state,
      accounts: state.accounts.map((item) => item.id === account.id ? { ...item, capabilities: { ...item.capabilities, sendRoutes: [] } } : item),
      drafts: [draft],
      approvals: [approval],
      outbox: [command]
    };

    const result = await dispatchOutbox(revokedState, command.id, '2026-10-08T12:00:30.000Z');

    expect(result.outbox[0].state).toBe('rejected');
    expect(result.outbox[0].failureReason).toBe('route_recheck_failed:route_not_supported_by_capability_snapshot');
    expect(result.approvals[0].consumed).toBe(false);
  });
});
