import type { HubState, InboxItem } from './types';
import { comparePriority, effectivePriority } from './priority';

export function buildInbox(state: HubState): InboxItem[] {
  return state.conversations
    .map((conversation) => {
      const account = state.accounts.find((item) => item.id === conversation.accountId);
      if (!account) throw new Error(`Account missing for conversation ${conversation.id}`);
      const priority = effectivePriority(conversation, state.priorityOverrides);
      const latestMessage = state.messages
        .filter((message) => message.conversationId === conversation.id)
        .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))[0];
      const latestObservation = state.observations
        .filter((observation) => observation.conversationId === conversation.id)
        .sort((a, b) => b.observedAt.localeCompare(a.observedAt))[0];
      const latestSnippet = latestMessage?.body ?? latestObservation?.textFragments.join(' ') ?? 'No available content';

      return {
        conversation,
        account,
        priority: priority.priority,
        prioritySource: priority.source,
        actionCount: state.actionItems.filter((item) => item.conversationId === conversation.id && !item.dismissed).length,
        latestSnippet,
        coverageWarning: conversation.coverage === 'current' ? '' : conversation.coverageExplanation
      };
    })
    .sort((a, b) => comparePriority(a.priority, b.priority) || b.conversation.latestEventAt.localeCompare(a.conversation.latestEventAt));
}

export function upsertDraft(state: HubState, draftId: string, draftFactory: () => HubState['drafts'][number]): HubState {
  const nextDraft = draftFactory();
  const existing = state.drafts.some((draft) => draft.id === draftId);
  return {
    ...state,
    drafts: existing ? state.drafts.map((draft) => draft.id === draftId ? nextDraft : draft) : [...state.drafts, nextDraft]
  };
}
