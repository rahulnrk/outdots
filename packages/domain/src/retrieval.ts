import type { ContextCompartment, HubState, MemoryFact, MessageRevision, Observation, SourceRef } from './types';

export interface ContextBundle {
  conversationId: string;
  compartment: ContextCompartment;
  messages: MessageRevision[];
  observations: Observation[];
  facts: MemoryFact[];
  evidence: SourceRef[];
  coverageWarning: string;
  tokenBudgetUsed: number;
  omitted: { messages: number; observations: number; facts: number };
}

function roughTokens(value: string): number {
  return Math.ceil(value.trim().split(/\s+/).filter(Boolean).length * 1.35);
}

export function retrieveContext(state: HubState, conversationId: string, tokenBudget = 650): ContextBundle {
  const conversation = state.conversations.find((item) => item.id === conversationId);
  if (!conversation) throw new Error(`Conversation not found: ${conversationId}`);

  const messages = state.messages
    .filter((message) => message.accountId === conversation.accountId && message.conversationId === conversationId && !message.tombstone)
    .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt));

  const observations = state.observations
    .filter((observation) => observation.accountId === conversation.accountId && observation.conversationId === conversationId)
    .sort((a, b) => b.observedAt.localeCompare(a.observedAt));

  const facts = state.facts.filter((fact) => fact.compartment === conversation.compartment && fact.confirmed && fact.evidence.every((ref) => {
    const source = ref.kind === 'message' ? state.messages.find((item) => item.id === ref.id && item.revision === ref.revision)
      : ref.kind === 'observation' ? state.observations.find((item) => item.id === ref.id && item.revision === ref.revision)
        : undefined;
    return !source || !('tombstone' in source) || !source.tombstone;
  }));

  const selectedMessages: MessageRevision[] = [];
  const selectedObservations: Observation[] = [];
  const selectedFacts: MemoryFact[] = [];
  let used = 0;
  const omitted = { messages: 0, observations: 0, facts: 0 };

  for (const message of messages) {
    const cost = roughTokens(`${message.subject ?? ''} ${message.body}`);
    if (used + cost > tokenBudget) { omitted.messages += messages.length - selectedMessages.length; break; }
    selectedMessages.push(message);
    used += cost;
  }

  for (const observation of observations) {
    const cost = roughTokens(observation.textFragments.join(' '));
    if (used + cost > tokenBudget) { omitted.observations += observations.length - selectedObservations.length; break; }
    selectedObservations.push(observation);
    used += cost;
  }

  for (const fact of facts) {
    const cost = roughTokens(`${fact.subject}: ${fact.claim}`);
    if (used + cost > tokenBudget) { omitted.facts += facts.length - selectedFacts.length; break; }
    selectedFacts.push(fact);
    used += cost;
  }

  return {
    conversationId,
    compartment: conversation.compartment,
    messages: selectedMessages.reverse(),
    observations: selectedObservations.reverse(),
    facts: selectedFacts,
    evidence: [...selectedMessages.map(toMessageRef), ...selectedObservations.map(toObservationRef), ...selectedFacts.flatMap((fact) => fact.evidence)],
    coverageWarning: conversation.coverage === 'current' && Object.values(omitted).every((count) => count === 0)
      ? ''
      : [conversation.coverageExplanation, Object.values(omitted).some((count) => count > 0) ? 'Some evidence was omitted because the context budget was reached.' : ''].filter(Boolean).join(' '),
    tokenBudgetUsed: used
    ,omitted
  };
}

export function toMessageRef(message: MessageRevision): SourceRef {
  return { id: message.id, kind: 'message', revision: message.revision };
}

export function toObservationRef(observation: Observation): SourceRef {
  return { id: observation.id, kind: 'observation', revision: observation.revision };
}
