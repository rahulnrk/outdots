import type { Draft, HubState, OutboxCommand } from '@mini/domain';

/** Commands that can still lead to a send. Anything else is terminal from the hub's point of view. */
export function isLiveCommand(command: OutboxCommand): boolean {
  return command.state === 'ready' || command.state === 'dispatching';
}

export function patchCommand(state: HubState, commandId: string, patch: Partial<OutboxCommand>): HubState {
  return { ...state, outbox: state.outbox.map((item) => item.id === commandId ? { ...item, ...patch } : item) };
}

/**
 * Invalidate every still-ready command for a conversation. Used when the user edits an approved draft or
 * generates a new one, so an older approval can never send content the user is no longer looking at.
 */
export function invalidateReadyCommands(state: HubState, conversationId: string, reason: string, now: string, exceptDraftId?: string): HubState {
  const draftIds = new Set(state.drafts.filter((draft) => draft.conversationId === conversationId && draft.id !== exceptDraftId).map((draft) => draft.id));
  return {
    ...state,
    outbox: state.outbox.map((command) => draftIds.has(command.draftId) && command.state === 'ready'
      ? { ...command, state: 'expired_or_invalidated', failureReason: reason, lastAttemptAt: now }
      : command)
  };
}

function uniqueDraftId(state: HubState, base: string): string {
  if (!state.drafts.some((draft) => draft.id === base) && !state.outbox.some((command) => command.draftId === base)) return base;
  let index = 2;
  while (state.drafts.some((draft) => draft.id === `${base}-v${index}`)) index += 1;
  return `${base}-v${index}`;
}

/** Add a freshly generated draft as the current version and retire older versions for that conversation. */
export function addDraftVersion(state: HubState, draft: Draft, now: string): { state: HubState; draft: Draft } {
  const next = { ...draft, id: uniqueDraftId(state, draft.id) };
  const invalidated = invalidateReadyCommands(state, draft.conversationId, 'superseded_by_newer_draft', now);
  return {
    draft: next,
    state: {
      ...invalidated,
      drafts: [
        ...invalidated.drafts.map((item) => item.conversationId === draft.conversationId && (item.status === 'proposed' || item.status === 'ready_for_review' || item.status === 'blocked')
          ? { ...item, status: 'stale' as const }
          : item),
        next
      ]
    }
  };
}

/**
 * Edit the body of the current draft. The edit always produces a new draft version that needs a new exact
 * review. Any approval bound to the old text is invalidated immediately (the domain would also refuse it at
 * dispatch because the review digest no longer matches).
 */
export function editDraftBody(state: HubState, draftId: string, body: string, now: string): { state: HubState; draft?: Draft } {
  const original = state.drafts.find((draft) => draft.id === draftId);
  if (!original) return { state };
  if (state.outbox.some((command) => command.draftId === draftId && command.state === 'dispatching')) return { state };
  const invalidated = invalidateReadyCommands(state, original.conversationId, 'review_payload_changed_since_approval', now);
  const edited: Draft = {
    ...original,
    id: uniqueDraftId(invalidated, `${original.id.replace(/-v\d+$/, '')}`),
    body,
    status: original.status === 'blocked' ? 'blocked' : original.missingDecisions.length > 0 ? 'proposed' : 'ready_for_review',
    createdAt: now
  };
  return {
    draft: edited,
    state: {
      ...invalidated,
      drafts: [...invalidated.drafts.map((item) => item.id === draftId && item.status !== 'sent' ? { ...item, status: 'stale' as const } : item), edited]
    }
  };
}

/** Prepare a fresh review of the same content after an approval became unusable (expired, rejected, invalidated). */
export function reviseForNewReview(state: HubState, draftId: string, now: string): { state: HubState; draft?: Draft } {
  const original = state.drafts.find((draft) => draft.id === draftId);
  if (!original) return { state };
  return editDraftBody(state, draftId, original.body, now);
}

/**
 * Apply the result of an asynchronous dispatch onto the *current* state, touching only the records that the
 * dispatch owns (the command, its approval and its draft). Anything the user changed during the await, such as a
 * priority override, survives.
 */
export function mergeDispatchResult(current: HubState, result: HubState, commandId: string): HubState {
  const command = result.outbox.find((item) => item.id === commandId);
  if (!command) return current;
  const approval = result.approvals.find((item) => item.draftId === command.draftId && item.reviewDigest === command.reviewDigest);
  const draft = result.drafts.find((item) => item.id === command.draftId);
  return {
    ...current,
    outbox: current.outbox.map((item) => item.id === commandId ? command : item),
    approvals: approval ? current.approvals.map((item) => item.id === approval.id ? approval : item) : current.approvals,
    drafts: draft ? current.drafts.map((item) => item.id === draft.id && item.status !== 'stale' ? draft : item) : current.drafts
  };
}

export function clearPriorityOverride(state: HubState, conversationId: string): HubState {
  return { ...state, priorityOverrides: state.priorityOverrides.filter((item) => item.conversationId !== conversationId) };
}
