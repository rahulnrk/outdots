import type { Account, Draft, HubState, SendRoute } from './types';
import type { ContextBundle } from './retrieval';
import { evaluateReplyEligibility } from './eligibility';

export interface DraftIntent {
  stance?: 'accept' | 'counter' | 'ask_clarification';
  price?: string;
  date?: string;
  note?: string;
}

function lastInboundSender(context: ContextBundle): string {
  const message = [...context.messages].reverse().find((item) => item.direction === 'inbound');
  if (message) return message.sender;
  const observation = [...context.observations].reverse()[0];
  return observation?.senderHint ?? 'there';
}

function safeGreetingName(value: string): string {
  if (/[\r\n]|ignore previous|wire payment|evil\.example|\baccount\s+\d/i.test(value)) return 'there';
  const cleaned = value.replace(/[^A-Za-zÀ-ÖØ-öø-ÿ .'-]/g, '').trim();
  return cleaned && cleaned.length <= 80 ? cleaned.split(/\s+/)[0] : 'there';
}

function buildBody(context: ContextBundle, intent: DraftIntent, signatureName: string): { body: string; missingDecisions: string[] } {
  const missing: string[] = [];
  const name = safeGreetingName(lastInboundSender(context));
  const observedRequest = [...context.messages.map((m) => m.body), ...context.observations.flatMap((o) => o.textFragments)].join(' ');
  const mentionsPrice = /₹|rs\.?|price|cost|budget|80,?000/i.test(observedRequest);
  const mentionsDate = /friday|monday|tuesday|wednesday|thursday|saturday|sunday|tomorrow|today|date|deliver/i.test(observedRequest);

  if (!intent.stance) missing.push('Choose whether to accept, counter or ask a clarification.');
  if ((intent.stance === 'accept' || intent.stance === 'counter') && !intent.price) missing.push('Confirm the price or counter-price.');
  if ((intent.stance === 'accept' || intent.stance === 'counter') && !intent.date) missing.push('Confirm the delivery date or counter-date.');
  if (mentionsPrice && intent.stance !== 'ask_clarification' && !intent.price) missing.push('Confirm the price or counter-price.');
  if (mentionsDate && intent.stance !== 'ask_clarification' && !intent.date) missing.push('Confirm the delivery date or counter-date.');

  if (missing.length > 0) {
    return {
      body: `Hi ${name},\n\nI am reviewing this and will confirm shortly.\n\nThanks,\n${signatureName}`,
      missingDecisions: missing
    };
  }

  if (intent.stance === 'ask_clarification') {
    return {
      body: `Hi ${name},\n\nThanks for the message. Could you please confirm the exact requirement and timeline so I can respond accurately?${intent.note ? `\n\n${intent.note}` : ''}\n\nThanks,\n${signatureName}`,
      missingDecisions: []
    };
  }

  if (intent.stance === 'counter') {
    return {
      body: `Hi ${name},\n\nThanks for checking. I can do this for ${intent.price} with delivery by ${intent.date}. Please confirm if that works for you.${intent.note ? `\n\n${intent.note}` : ''}\n\nThanks,\n${signatureName}`,
      missingDecisions: []
    };
  }

  return {
    body: `Hi ${name},\n\nConfirmed. I can proceed at ${intent.price} with delivery by ${intent.date}.${intent.note ? `\n\n${intent.note}` : ''}\n\nThanks,\n${signatureName}`,
    missingDecisions: []
  };
}

function shortDigest(value: string): string {
  let hash = 5381;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 33) ^ value.charCodeAt(index);
  }
  return (hash >>> 0).toString(36);
}

export function createDraft(state: HubState, account: Account, conversationId: string, context: ContextBundle, intent: DraftIntent, now: string): Draft {
  const conversation = state.conversations.find((item) => item.id === conversationId);
  if (!conversation || conversation.accountId !== account.id) throw new Error('Account does not own conversation.');
  if (context.conversationId !== conversationId) throw new Error('Context belongs to another conversation.');
  const route: SendRoute = account.capabilities.sendRoutes.includes('api') ? 'api' : 'native_handoff';
  const lastInbound = [...state.messages]
    .filter((message) => message.conversationId === conversationId && message.direction === 'inbound')
    .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))[0];

  const eligibility = evaluateReplyEligibility(account, route, now, lastInbound?.occurredAt);
  const signatureName = account.signatureName ?? account.selfIdentities?.[0] ?? account.displayName;
  const { body, missingDecisions } = buildBody(context, intent, signatureName);
  const selfIdentities = new Set([...(account.selfIdentities ?? []), account.displayName]);

  return {
    id: `draft-${conversationId}-${Date.parse(now)}-${shortDigest(`${JSON.stringify(intent)}|${body}`)}`,
    accountId: account.id,
    conversationId,
    route,
    recipients: conversation.participants.filter((participant) => !selfIdentities.has(participant)),
    subject: state.messages.find((message) => message.conversationId === conversationId)?.subject,
    body,
    evidence: context.evidence,
    missingDecisions: eligibility.eligibleNow ? missingDecisions : [...missingDecisions, `Route blocked: ${eligibility.reasonCode}`],
    status: !eligibility.eligibleNow ? 'blocked' : missingDecisions.length === 0 ? 'ready_for_review' : 'proposed',
    createdAt: now
  };
}
