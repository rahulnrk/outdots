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
}

function roughTokens(value: string): number {
  return Math.ceil(value.trim().split(/\s+/).filter(Boolean).length * 1.35);
}

export function retrieveContext(state: HubState, conversationId: string, tokenBudget = 650): ContextBundle {
  const conversation = state.conversations.find((item) => item.id === conversationId);
  if (!conversation) throw new Error(`Conversation not found: ${conversationId}`);

  const messages = state.messages
    .filter((message) => message.conversationId === conversationId && !message.tombstone)
    .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt));

  const observations = state.observations
    .filter((observation) => observation.conversationId === conversationId)
    .sort((a, b) => b.observedAt.localeCompare(a.observedAt));

  const facts = state.facts.filter((fact) => fact.compartment === conversation.compartment && fact.confirmed);

  const selectedMessages: MessageRevision[] = [];
  const selectedObservations: Observation[] = [];
  const selectedFacts: MemoryFact[] = [];
  let used = 0;

  for (const message of messages) {
    const cost = roughTokens(`${message.subject ?? ''} ${message.body}`);
    if (used + cost > tokenBudget) break;
    selectedMessages.push(message);
    used += cost;
  }

  for (const observation of observations) {
    const cost = roughTokens(observation.textFragments.join(' '));
    if (used + cost > tokenBudget) break;
    selectedObservations.push(observation);
    used += cost;
  }

  for (const fact of facts) {
    const cost = roughTokens(`${fact.subject}: ${fact.claim}`);
    if (used + cost > tokenBudget) break;
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
    coverageWarning: conversation.coverage === 'current' ? '' : conversation.coverageExplanation,
    tokenBudgetUsed: used
  };
}

export function toMessageRef(message: MessageRevision): SourceRef {
  return { id: message.id, kind: 'message', revision: message.revision };
}

export function toObservationRef(observation: Observation): SourceRef {
  return { id: observation.id, kind: 'observation', revision: observation.revision };
}
