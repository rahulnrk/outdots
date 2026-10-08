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
    const { approval, command } = await approveDraft(draft, account.capabilities.policyVersion, 'grants-v1', now);
    const readyState = { ...state, drafts: [draft], approvals: [approval], outbox: [command] };

    const afterFirst = dispatchOutbox(readyState, command.id, '2026-10-08T12:00:30.000Z');
    const afterSecond = dispatchOutbox(afterFirst, command.id, '2026-10-08T12:00:45.000Z');

    expect(afterFirst.outbox[0].state).toBe('accepted_by_provider');
    expect(afterFirst.approvals[0].consumed).toBe(true);
    expect(afterSecond.outbox[0].state).toBe('accepted_by_provider');
  });
});
