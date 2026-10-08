import type { Conversation, HubState, Priority, PriorityOverride } from './types';

const priorityWeight: Record<Priority, number> = { high: 3, normal: 2, low: 1 };

export function effectivePriority(conversation: Conversation, overrides: PriorityOverride[]): { priority: Priority; source: 'user' | 'ai' } {
  const matching = overrides
    .map((override, index) => ({ override, index }))
    .filter(({ override }) => override.conversationId === conversation.id)
    .sort((a, b) => b.override.createdAt.localeCompare(a.override.createdAt) || b.index - a.index);

  if (matching[0]) {
    return { priority: matching[0].override.priority, source: 'user' };
  }

  return { priority: conversation.aiPriority, source: 'ai' };
}

export function comparePriority(a: Priority, b: Priority): number {
  return priorityWeight[b] - priorityWeight[a];
}

export function setPriorityOverride(state: HubState, conversationId: string, priority: Priority, reason: string, now: string): HubState {
  const override: PriorityOverride = {
    id: `prio-${conversationId}-${Date.parse(now)}-${state.priorityOverrides.length + 1}`,
    conversationId,
    priority,
    reason,
    createdAt: now
  };
  return { ...state, priorityOverrides: [...state.priorityOverrides, override] };
}
