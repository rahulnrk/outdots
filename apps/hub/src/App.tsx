import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import {
  approveDraft,
  buildInbox,
  createDemoState,
  createDraft,
  dispatchOutbox,
  effectivePriority,
  evaluateReplyEligibility,
  retrieveContext,
  setPriorityOverride,
  type Draft,
  type DraftIntent,
  type HubState,
  type InboxItem,
  type OutboxCommand,
  type Priority,
  type ReplyEligibility
} from '@mini/domain';
import { systemClock, type Clock } from './clock';
import { FixtureProvider, fixtureDelayFromUrl, fixtureModeFromUrl, type FixtureMode } from './fixtureProvider';
import {
  addDraftVersion,
  clearPriorityOverride,
  editDraftBody,
  isLiveCommand,
  mergeDispatchResult,
  patchCommand,
  reviseForNewReview
} from './hubState';
import {
  draftStatusLabel,
  eligibilityLabel,
  eligibilityReasonText,
  formatClock,
  formatTime,
  outcomeCopy,
  providerName,
  reasonText,
  routeLabel,
  unknownsFor,
  windowLabel
} from './presentation';

interface ReviewSnapshot {
  draftId: string;
  eligibility: ReplyEligibility;
  checkedAt: string;
}

function emptyState(): HubState {
  return { ...createDemoState(), conversations: [], messages: [], observations: [], actionItems: [] };
}

function initialState(): HubState {
  const scenario = typeof window === 'undefined' ? '' : new URLSearchParams(window.location.search).get('scenario');
  return scenario === 'empty' ? emptyState() : createDemoState();
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

const coverageLabels: Record<string, string> = {
  current: 'Current within scope',
  partial: 'Partial coverage',
  stale: 'Stale',
  unknown: 'Coverage unknown'
};

const workflowLabels: Record<string, string> = {
  needs_review: 'Needs review',
  needs_decision: 'Needs your decision',
  draft_ready: 'Draft ready',
  waiting: 'Waiting',
  snoozed: 'Snoozed',
  done: 'Done'
};

export function App({ clock = systemClock, provider }: { clock?: Clock; provider?: FixtureProvider } = {}) {
  const [state, setState] = useState<HubState>(initialState);
  // stateRef is the single source of truth for event handlers. It is updated synchronously on every commit,
  // so an async handler that resumes after an await always sees the newest state, never a stale closure.
  const stateRef = useRef(state);
  const commit = useCallback((update: (current: HubState) => HubState) => {
    const next = update(stateRef.current);
    stateRef.current = next;
    setState(next);
  }, []);

  const providerRef = useRef<FixtureProvider | undefined>(undefined);
  if (!providerRef.current) {
    const search = typeof window === 'undefined' ? '' : window.location.search;
    providerRef.current = provider ?? new FixtureProvider(fixtureModeFromUrl(search), fixtureDelayFromUrl(search));
  }
  const fixture = providerRef.current;
  const [fixtureMode, setFixtureMode] = useState<FixtureMode>(fixture.mode);
  const [providerRequests, setProviderRequests] = useState(0);

  // Synchronous re-entrancy guard. React re-renders asynchronously, so a disabled attribute alone does not stop
  // a fast double click from running the handler twice.
  const inFlight = useRef(new Set<string>());
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const beginBusy = (key: string): boolean => {
    if (inFlight.current.has(key)) return false;
    inFlight.current.add(key);
    setBusy((current) => ({ ...current, [key]: true }));
    return true;
  };
  const endBusy = (key: string) => {
    inFlight.current.delete(key);
    setBusy((current) => {
      const next = { ...current };
      delete next[key];
      return next;
    });
  };

  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const inbox = useMemo(() => buildInbox(state), [state]);
  const [selectedId, setSelectedId] = useState(() => inbox[0]?.conversation.id ?? '');
  const selected: InboxItem | undefined = inbox.find((item) => item.conversation.id === selectedId) ?? inbox[0];
  const conversationId = selected?.conversation.id;

  const [intent, setIntent] = useState<DraftIntent>({ stance: 'accept', price: '₹80,000', date: 'Friday' });
  const [review, setReview] = useState<ReviewSnapshot | null>(null);
  const [editing, setEditing] = useState<{ draftId: string; body: string } | null>(null);

  const reviewHeadingRef = useRef<HTMLHeadingElement>(null);
  const outcomeRef = useRef<HTMLDivElement>(null);
  const draftHeadingRef = useRef<HTMLHeadingElement>(null);
  const pendingFocus = useRef<'review' | 'outcome' | 'draft' | null>(null);

  useEffect(() => {
    const target = pendingFocus.current;
    if (!target) return;
    pendingFocus.current = null;
    const element = target === 'review' ? reviewHeadingRef.current : target === 'outcome' ? outcomeRef.current : draftHeadingRef.current;
    element?.focus();
  });

  const context = useMemo(() => {
    if (!conversationId) return undefined;
    try {
      return retrieveContext(state, conversationId);
    } catch {
      return undefined;
    }
  }, [state, conversationId]);

  const conversationDrafts = state.drafts.filter((draft) => draft.conversationId === conversationId);
  const latestDraft = conversationDrafts[conversationDrafts.length - 1];
  const latestCommand = latestDraft ? state.outbox.find((command) => command.draftId === latestDraft.id) : undefined;
  const draftIds = new Set(conversationDrafts.map((draft) => draft.id));
  const conversationCommands = state.outbox.filter((command) => draftIds.has(command.draftId));
  const liveCommand = conversationCommands.find(isLiveCommand);
  const dispatchingCommand = conversationCommands.find((command) => command.state === 'dispatching');
  const latestInbound = conversationId ? state.messages
    .filter((message) => message.conversationId === conversationId && message.direction === 'inbound')
    .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))[0] : undefined;
  const route = selected?.account.capabilities.sendRoutes.includes('api') ? 'api' : 'native_handoff';
  const eligibility = selected ? evaluateReplyEligibility(selected.account, route, clock(), latestInbound?.occurredAt) : undefined;
  const priorityInfo = selected ? effectivePriority(selected.conversation, state.priorityOverrides) : undefined;
  const actionItems = state.actionItems.filter((item) => item.conversationId === conversationId && !item.dismissed);
  const reviewDraft = review ? state.drafts.find((draft) => draft.id === review.draftId) : undefined;
  const reviewOpen = Boolean(review && reviewDraft && latestDraft && reviewDraft.id === latestDraft.id && reviewDraft.status === 'ready_for_review' && !latestCommand);

  function selectConversation(id: string) {
    setSelectedId(id);
    setReview(null);
    setEditing(null);
    setError('');
    setNotice('');
  }

  function generateDraft() {
    const current = stateRef.current;
    const item = buildInbox(current).find((entry) => entry.conversation.id === conversationId);
    if (!item) return;
    if (current.outbox.some((command) => command.state === 'dispatching' && current.drafts.some((draft) => draft.id === command.draftId && draft.conversationId === item.conversation.id))) {
      setError('A send is in progress for this conversation. Wait for its outcome before drafting again.');
      return;
    }
    try {
      const now = clock();
      const bundle = retrieveContext(current, item.conversation.id);
      const proposal = createDraft(current, item.account, item.conversation.id, bundle, intent, now);
      const hadReady = current.outbox.some((command) => command.state === 'ready' && current.drafts.some((draft) => draft.id === command.draftId && draft.conversationId === item.conversation.id));
      commit((latest) => addDraftVersion(latest, proposal, now).state);
      setReview(null);
      setEditing(null);
      setError('');
      setNotice(hadReady ? 'New draft created. The earlier approval was cancelled because it no longer matches what you are reviewing.' : 'Draft created. Review it before approving.');
      pendingFocus.current = 'draft';
    } catch (cause) {
      setError(`Could not create a draft: ${errorMessage(cause)}`);
    }
  }

  function openReview() {
    if (!latestDraft || !selected) return;
    const current = stateRef.current;
    const draft = current.drafts.find((item) => item.id === latestDraft.id);
    if (!draft || draft.status !== 'ready_for_review') return;
    const account = current.accounts.find((item) => item.id === draft.accountId);
    if (!account) {
      setError('The sending account for this draft is missing.');
      return;
    }
    const inbound = current.messages
      .filter((message) => message.conversationId === draft.conversationId && message.direction === 'inbound')
      .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))[0];
    const checkedAt = clock();
    setReview({ draftId: draft.id, eligibility: evaluateReplyEligibility(account, draft.route, checkedAt, inbound?.occurredAt), checkedAt });
    setEditing(null);
    setNotice('');
    pendingFocus.current = 'review';
  }

  async function approveFromReview() {
    if (!review) return;
    const draftId = review.draftId;
    const key = `approve:${draftId}`;
    if (!beginBusy(key)) return;
    try {
      const snapshot = stateRef.current;
      const draft = snapshot.drafts.find((item) => item.id === draftId);
      const account = draft && snapshot.accounts.find((item) => item.id === draft.accountId);
      if (!draft || !account) throw new Error('The reviewed draft is no longer available.');
      if (snapshot.outbox.some((command) => command.draftId === draftId)) return; // already approved, never queue twice
      if (!review.eligibility.eligibleNow) throw new Error(`Route is blocked: ${eligibilityReasonText(review.eligibility.reasonCode)}`);
      const { approval, command } = await approveDraft(draft, account, clock(), snapshot);
      let applied = false;
      commit((current) => {
        const stillCurrent = current.drafts.find((item) => item.id === draftId);
        // The user may have edited or regenerated the draft while the digest was computed. Never approve text
        // that is no longer the reviewed text.
        if (!stillCurrent || stillCurrent.status !== 'ready_for_review' || stillCurrent.body !== draft.body) return current;
        if (current.outbox.some((item) => item.draftId === draftId)) return current;
        applied = true;
        return {
          ...current,
          approvals: [...current.approvals, approval],
          outbox: [...current.outbox, command],
          drafts: current.drafts.map((item) => item.id === draftId ? { ...item, status: 'approved' } : item)
        };
      });
      if (!applied) {
        setNotice('The draft changed while approving, so nothing was approved. Review the current version.');
      } else {
        setNotice('');
      }
      setReview(null);
      setError('');
      pendingFocus.current = 'outcome';
    } catch (cause) {
      setError(`Approval failed: ${errorMessage(cause)}`);
    } finally {
      endBusy(key);
    }
  }

  async function dispatch(commandId: string) {
    const key = `dispatch:${commandId}`;
    if (!beginBusy(key)) return;
    const snapshot = stateRef.current;
    const command = snapshot.outbox.find((item) => item.id === commandId);
    if (!command || command.state !== 'ready') {
      endBusy(key);
      return;
    }
    // Show the in-flight state, but hand the domain the pre-dispatch snapshot so its own checks run on the
    // approved command exactly once.
    commit((current) => patchCommand(current, commandId, { state: 'dispatching', lastAttemptAt: clock() }));
    try {
      const result = await dispatchOutbox(snapshot, commandId, clock(), { gateway: fixture.gateway() });
      commit((current) => mergeDispatchResult(current, result, commandId));
      setError('');
    } catch (cause) {
      // The request may have reached the provider before the failure. Treat as unknown, never as not-sent.
      const now = clock();
      commit((current) => ({
        ...patchCommand(current, commandId, { state: 'outcome_unknown', lastAttemptAt: now, failureReason: `dispatch_error:${errorMessage(cause)}` }),
        approvals: current.approvals.map((item) => item.draftId === command.draftId && item.reviewDigest === command.reviewDigest ? { ...item, consumed: true } : item)
      }));
      setError(`Send attempt failed: ${errorMessage(cause)}`);
    } finally {
      setProviderRequests(fixture.requestCount);
      endBusy(key);
      pendingFocus.current = 'outcome';
    }
  }

  async function reconcile(commandId: string) {
    const key = `reconcile:${commandId}`;
    if (!beginBusy(key)) return;
    try {
      await new Promise((resolve) => setTimeout(resolve, Math.min(fixture.delayMs, 300)));
      const record = fixture.lookup(commandId);
      const now = clock();
      commit((current) => {
        const command = current.outbox.find((item) => item.id === commandId);
        if (!command || command.state !== 'outcome_unknown') return current;
        if (record) {
          return {
            ...patchCommand(current, commandId, { state: 'accepted_by_provider', receipt: record.receipt, failureReason: undefined, lastAttemptAt: now }),
            drafts: current.drafts.map((draft) => draft.id === command.draftId ? { ...draft, status: 'sent' } : draft)
          };
        }
        return patchCommand(current, commandId, { failureReason: 'reconcile_no_record_found', lastAttemptAt: now });
      });
      setNotice(record
        ? `Reconciled at ${formatClock(now)}: the simulated provider has a record (${record.receipt}). Marked accepted by provider.`
        : `Checked at ${formatClock(now)}: the simulated provider has no record yet. The outcome is still unknown. Do not resend.`);
    } catch (cause) {
      setError(`Reconcile failed: ${errorMessage(cause)}`);
    } finally {
      endBusy(key);
    }
  }

  function markHandledElsewhere(commandId: string) {
    commit((current) => {
      const command = current.outbox.find((item) => item.id === commandId);
      if (!command || command.state !== 'handoff_opened') return current;
      return patchCommand(current, commandId, { state: 'externally_handled_unverified', lastAttemptAt: clock() });
    });
  }

  function discardApproval(commandId: string) {
    commit((current) => {
      const command = current.outbox.find((item) => item.id === commandId);
      if (!command || command.state !== 'ready') return current;
      return patchCommand(current, commandId, { state: 'expired_or_invalidated', failureReason: 'discarded_by_user', lastAttemptAt: clock() });
    });
    setNotice('Approval discarded. Nothing was sent.');
  }

  function prepareNewReview(draftId: string) {
    const now = clock();
    let created: Draft | undefined;
    commit((current) => {
      const result = reviseForNewReview(current, draftId, now);
      created = result.draft;
      return result.state;
    });
    if (created) {
      setNotice('A fresh copy is ready. Review it again before approving.');
      pendingFocus.current = 'draft';
    }
  }

  function saveEdit() {
    if (!editing) return;
    const now = clock();
    const current = stateRef.current;
    const hadReady = current.outbox.some((command) => command.draftId === editing.draftId && command.state === 'ready');
    if (current.outbox.some((command) => command.draftId === editing.draftId && command.state === 'dispatching')) {
      setError('This draft is being sent. It cannot be edited now.');
      return;
    }
    commit((latest) => editDraftBody(latest, editing.draftId, editing.body, now).state);
    setEditing(null);
    setReview(null);
    setNotice(hadReady
      ? 'Draft edited after approval. The earlier approval no longer matches and cannot be sent. Review the edited version to approve it.'
      : 'Draft edited. Review the edited version before approving.');
    pendingFocus.current = 'draft';
  }

  function overridePriority(priority: Priority) {
    if (!conversationId) return;
    commit((current) => setPriorityOverride(current, conversationId, priority, 'User marked from hub UI', clock()));
  }

  function resetPriority() {
    if (!conversationId) return;
    commit((current) => clearPriorityOverride(current, conversationId));
  }

  function changeFixtureMode(mode: FixtureMode) {
    fixture.mode = mode;
    setFixtureMode(mode);
  }

  const providerLabel = selected ? providerName(selected.account.provider) : '';
  const isHandoff = latestDraft?.route === 'native_handoff';
  const sendLabel = isHandoff ? `Open ${providerLabel} handoff (simulated)` : `Send via ${providerLabel} (simulated)`;
  const generateDisabled = !selected || !context || Boolean(dispatchingCommand);

  return (
    <div className="shell">
      <a className="skip-link" href="#main">Skip to conversation</a>
      <header className="app-header">
        <div>
          <p className="eyebrow">Private local-first communication hub</p>
          <h1>Attention hub</h1>
          <p className="muted">See what was observed, what the hub inferred, what needs your action, and what it cannot know.</p>
        </div>
        <div className="demo-banner" role="note" aria-label="Demo build notice">
          <strong>Demo build: synthetic data, simulated sending</strong>
          <p>No real accounts are connected. Gmail sending and WhatsApp handoff are simulated by a local fixture in this browser. No private inbox, prompt or memory endpoint exists on the publisher side.</p>
          <label className="inline-label">Simulated provider behavior
            <select value={fixtureMode} onChange={(event) => changeFixtureMode(event.target.value as FixtureMode)}>
              <option value="accept">Accepts the message</option>
              <option value="reject">Rejects the message</option>
              <option value="timeout">Times out (outcome unknown)</option>
            </select>
          </label>
          <p className="muted small" data-testid="provider-request-count">Simulated provider requests received: {providerRequests}</p>
        </div>
      </header>

      {error && (
        <div className="banner banner-danger" role="alert">
          <span>{error}</span>
          <button type="button" className="ghost" onClick={() => setError('')}>Dismiss</button>
        </div>
      )}
      <div className="sr-status" role="status" aria-live="polite">{notice && <p className="banner banner-info">{notice}</p>}</div>

      <div className="layout">
        <nav className="inbox-panel" aria-labelledby="inbox-heading">
          <div className="panel-heading">
            <h2 id="inbox-heading">Attention inbox</h2>
            <span className="count" aria-label={`${inbox.length} conversations`}>{inbox.length}</span>
          </div>
          {inbox.length === 0 ? (
            <div className="empty">
              <strong>No conversations yet</strong>
              <p>Connect an account or enable notification observation to start. Nothing has been observed in this session.</p>
            </div>
          ) : (
            <ul className="thread-list">
              {inbox.map((item) => {
                const isSelected = item.conversation.id === selected?.conversation.id;
                return (
                  <li key={item.conversation.id}>
                    <button
                      type="button"
                      className={`thread-card ${isSelected ? 'selected' : ''}`}
                      aria-current={isSelected ? 'true' : undefined}
                      onClick={() => selectConversation(item.conversation.id)}
                    >
                      <span className="thread-topline">
                        <span className={`badge provider-${item.account.provider}`}>{providerName(item.account.provider)}</span>
                        <span className={`badge priority-${item.priority}`}>
                          {item.priority} priority · {item.prioritySource === 'user' ? 'set by you' : 'AI suggested'}
                        </span>
                      </span>
                      <strong className="thread-title">{item.conversation.title}</strong>
                      <span className="thread-snippet">{item.latestSnippet}</span>
                      <span className="thread-meta">
                        <span className={`badge coverage-${item.conversation.coverage}`}>{coverageLabels[item.conversation.coverage]}</span>
                        <span>{workflowLabels[item.conversation.workflow]}</span>
                        {item.actionCount > 0 && <span>{item.actionCount} to do</span>}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </nav>

        <main id="main" className="detail-panel" tabIndex={-1}>
          {!selected ? (
            <div className="card empty">
              <h2>Nothing selected</h2>
              <p>When conversations arrive they appear in the attention inbox.</p>
            </div>
          ) : !context ? (
            <div className="card banner-danger" role="alert">
              <h2>Could not load this conversation</h2>
              <p>The local evidence for this conversation is missing. Other conversations are unaffected.</p>
            </div>
          ) : (
            <>
              <section className="detail-header" aria-labelledby="conversation-title">
                <div>
                  <p className="eyebrow light">{selected.account.displayName}</p>
                  <h2 id="conversation-title">{selected.conversation.title}</h2>
                  <p className="header-meta">
                    <span className={`badge coverage-${selected.conversation.coverage}`}>{coverageLabels[selected.conversation.coverage]}</span>
                    <span>Workflow: {workflowLabels[selected.conversation.workflow]}</span>
                  </p>
                </div>
                <fieldset className="priority-controls">
                  <legend>Priority</legend>
                  <p className="priority-summary" data-testid="priority-summary">
                    {priorityInfo?.source === 'user'
                      ? <>Your override: <strong>{priorityInfo.priority}</strong> (AI suggested {selected.conversation.aiPriority})</>
                      : <>AI suggested: <strong>{selected.conversation.aiPriority}</strong>. You have not overridden it.</>}
                  </p>
                  <div className="button-row">
                    {(['high', 'normal', 'low'] as Priority[]).map((priority) => (
                      <button
                        key={priority}
                        type="button"
                        aria-pressed={priorityInfo?.source === 'user' && priorityInfo.priority === priority}
                        onClick={() => overridePriority(priority)}
                      >Mark {priority}</button>
                    ))}
                    {priorityInfo?.source === 'user' && (
                      <button type="button" className="ghost-light" onClick={resetPriority}>Reset to AI priority</button>
                    )}
                  </div>
                </fieldset>
              </section>

              {selected.conversation.coverage !== 'current' && (
                <section className="coverage-callout" aria-labelledby="coverage-heading">
                  <h3 id="coverage-heading">{coverageLabels[selected.conversation.coverage]}: the hub may be missing messages</h3>
                  <p>{selected.conversation.coverageExplanation}</p>
                </section>
              )}

              <div className="grid three">
                <section className="card" aria-labelledby="observed-heading">
                  <h3 id="observed-heading">Observed</h3>
                  <p className="muted small">Exactly what the hub received from the source.</p>
                  {context.messages.length === 0 && context.observations.length === 0 && <p className="empty-inline">No content was observed for this conversation.</p>}
                  {context.messages.map((message) => (
                    <div key={message.id} className="evidence">
                      <span className="evidence-meta">{formatTime(message.occurredAt)} · {providerLabel} message · revision {message.revision}</span>
                      <strong>{message.sender}</strong>
                      {message.subject && <span className="muted small">Subject: {message.subject}</span>}
                      <p>{message.body}</p>
                    </div>
                  ))}
                  {context.observations.map((observation) => (
                    <div key={observation.id} className="evidence partial">
                      <span className="evidence-meta">{formatTime(observation.observedAt)} · Android notification observation · revision {observation.revision}</span>
                      <strong>{observation.senderHint} <span className="muted small">(sender name from notification)</span></strong>
                      <p>{observation.contentAvailable ? observation.textFragments.join(' ') : 'Content hidden by the notification.'}</p>
                      <span className="muted small">Text {observation.truncationState === 'complete' ? 'appears complete' : `may be ${observation.truncationState}`} · thread match confidence {observation.correlationConfidence}</span>
                    </div>
                  ))}
                </section>

                <section className="card" aria-labelledby="inferred-heading">
                  <h3 id="inferred-heading">Inferred by the hub</h3>
                  <p className="muted small">Suggestions. You decide.</p>
                  <ul className="plain-list">
                    <li><span className="tag">Priority</span> AI suggested {selected.conversation.aiPriority}{priorityInfo?.source === 'user' ? `, your override ${priorityInfo.priority} applies` : ''}.</li>
                    {actionItems.map((item) => (
                      <li key={item.id}><span className="tag">{item.inferred ? 'Inferred to-do' : 'To-do'}</span> {item.description}{item.dueAt ? ` Due ${formatTime(item.dueAt)}.` : ''}</li>
                    ))}
                    {context.facts.map((fact) => (
                      <li key={fact.id}><span className="tag">Confirmed memory</span> {fact.claim}</li>
                    ))}
                  </ul>
                </section>

                <section className="card" aria-labelledby="unknown-heading">
                  <h3 id="unknown-heading">What the hub cannot know</h3>
                  <ul className="plain-list" data-testid="unknowns">
                    {unknownsFor(selected.account, context.observations).map((item) => <li key={item}>{item}</li>)}
                  </ul>
                  <details>
                    <summary>Connector capability snapshot</summary>
                    <dl>
                      <dt>History</dt><dd>{selected.account.capabilities.readHistory.replaceAll('_', ' ')}</dd>
                      <dt>Incoming visibility</dt><dd>{selected.account.capabilities.inboundVisibility.replaceAll('_', ' ')}</dd>
                      <dt>Outgoing visibility</dt><dd>{selected.account.capabilities.outboundVisibility.replaceAll('_', ' ')}</dd>
                      <dt>Result visibility</dt><dd>{selected.account.capabilities.resultVisibility.replaceAll('_', ' ')}</dd>
                    </dl>
                    <ul className="compact-list">
                      {selected.account.capabilities.knownLimitations.map((item) => <li key={item}>{item}</li>)}
                    </ul>
                  </details>
                </section>
              </div>

              <section className="card" aria-labelledby="route-heading">
                <h3 id="route-heading">How you can reply</h3>
                {eligibility && (
                  <dl>
                    <dt>Route</dt><dd>{routeLabel(route)}</dd>
                    <dt>Reply eligibility</dt><dd>{eligibilityLabel(eligibility)}: {eligibilityReasonText(eligibility.reasonCode)}</dd>
                    <dt>Reply window</dt><dd>{windowLabel(eligibility.windowState)}</dd>
                  </dl>
                )}
                {route === 'native_handoff' && <p className="muted small">The hub cannot send on {providerLabel}. It can open {providerLabel} with your reviewed text and you finish there.</p>}
              </section>

              <section className="card composer" aria-labelledby="decision-heading">
                <div>
                  <h3 id="decision-heading">Your decision before drafting</h3>
                  <p className="muted small">The local drafting engine will not invent a price, date or commitment. You supply them.</p>
                </div>
                <div className="form-grid">
                  <label>Decision
                    <select value={intent.stance ?? ''} onChange={(event) => setIntent((current) => ({ ...current, stance: (event.target.value || undefined) as DraftIntent['stance'] }))}>
                      <option value="">Not decided</option>
                      <option value="accept">Accept</option>
                      <option value="counter">Counter</option>
                      <option value="ask_clarification">Ask clarification</option>
                    </select>
                  </label>
                  <label>Price
                    <input value={intent.price ?? ''} onChange={(event) => setIntent((current) => ({ ...current, price: event.target.value }))} placeholder="₹80,000" />
                  </label>
                  <label>Date
                    <input value={intent.date ?? ''} onChange={(event) => setIntent((current) => ({ ...current, date: event.target.value }))} placeholder="Friday" />
                  </label>
                  <label>Optional note
                    <input value={intent.note ?? ''} onChange={(event) => setIntent((current) => ({ ...current, note: event.target.value }))} placeholder="Add a constraint or next step" />
                  </label>
                </div>
                <div className="button-row">
                  <button type="button" className="primary" disabled={generateDisabled} onClick={generateDraft}>
                    {latestDraft ? 'Generate a new draft' : 'Generate evidence-grounded draft'}
                  </button>
                  {dispatchingCommand && <span className="muted small">Waiting for the current send to finish.</span>}
                </div>
              </section>

              {!latestDraft ? (
                <section className="card empty" aria-labelledby="draft-empty-heading">
                  <h3 id="draft-empty-heading">No draft yet</h3>
                  <p>Choose your decision above and generate a draft. Nothing is sent without your exact review and approval.</p>
                </section>
              ) : (
                <section className="card draft-card" aria-labelledby="draft-heading">
                  <div className="draft-header">
                    <div>
                      <h3 id="draft-heading" ref={draftHeadingRef} tabIndex={-1}>Draft</h3>
                      <p className="muted small">Version created {formatClock(latestDraft.createdAt)}</p>
                    </div>
                    <span className={`badge status-${latestDraft.status}`} data-testid="draft-status">{draftStatusLabel(latestDraft.status)}</span>
                  </div>

                  {latestDraft.missingDecisions.length > 0 && (
                    <div className="missing">
                      <strong>{latestDraft.status === 'blocked' ? 'Blocked' : 'Needs your decision before review'}</strong>
                      <ul>{latestDraft.missingDecisions.map((item) => <li key={item}>{item}</li>)}</ul>
                    </div>
                  )}

                  {editing && editing.draftId === latestDraft.id ? (
                    <div className="edit-area">
                      <label>Edit draft text
                        <textarea rows={8} value={editing.body} onChange={(event) => setEditing({ draftId: latestDraft.id, body: event.target.value })} />
                      </label>
                      {latestCommand?.state === 'ready' && <p className="warning-text">Saving will cancel the current approval. You will need to review and approve again.</p>}
                      <div className="button-row">
                        <button type="button" className="primary" disabled={editing.body.trim().length === 0} onClick={saveEdit}>Save edit</button>
                        <button type="button" onClick={() => setEditing(null)}>Cancel</button>
                      </div>
                    </div>
                  ) : (
                    <pre className="draft-body" data-testid="draft-body">{latestDraft.body}</pre>
                  )}

                  {!editing && !reviewOpen && (
                    <div className="button-row">
                      {!latestCommand && (
                        <button type="button" className="primary" disabled={latestDraft.status !== 'ready_for_review'} onClick={openReview}>Review exact payload</button>
                      )}
                      <button
                        type="button"
                        disabled={Boolean(latestCommand && !(latestCommand.state === 'ready')) || latestDraft.status === 'stale'}
                        onClick={() => setEditing({ draftId: latestDraft.id, body: latestDraft.body })}
                      >Edit text</button>
                      {latestDraft.status !== 'ready_for_review' && !latestCommand && (
                        <span className="muted small">{latestDraft.status === 'blocked' ? 'This draft cannot be approved on this route.' : 'Resolve the decisions above, then generate a new draft.'}</span>
                      )}
                    </div>
                  )}

                  {reviewOpen && review && reviewDraft && selected && (
                    <section className="review-panel" aria-labelledby="review-heading">
                      <h4 id="review-heading" ref={reviewHeadingRef} tabIndex={-1}>Exact review: this is precisely what will be approved</h4>
                      <dl className="review" data-testid="exact-review">
                        <dt>Account</dt><dd>{selected.account.displayName} ({providerLabel})</dd>
                        <dt>Recipients</dt><dd>{reviewDraft.recipients.length ? reviewDraft.recipients.join(', ') : 'None'}</dd>
                        {reviewDraft.subject !== undefined && (<><dt>Subject</dt><dd>{reviewDraft.subject}</dd></>)}
                        <dt>Body</dt><dd><pre className="draft-body">{reviewDraft.body}</pre></dd>
                        <dt>Attachments</dt><dd>None</dd>
                        <dt>Route</dt><dd>{routeLabel(reviewDraft.route)}{reviewDraft.route === 'native_handoff' ? '. The hub will not send; you finish in the app.' : '. Simulated in this demo.'}</dd>
                        <dt>Evidence versions</dt>
                        <dd>
                          <ul className="plain-list">
                            {reviewDraft.evidence.map((ref) => <li key={`${ref.kind}:${ref.id}:${ref.revision}`}>{ref.kind} {ref.id}, revision {ref.revision}</li>)}
                          </ul>
                        </dd>
                        <dt>Reply eligibility</dt>
                        <dd data-testid="review-eligibility">
                          {eligibilityLabel(review.eligibility)}: {eligibilityReasonText(review.eligibility.reasonCode)} Window: {windowLabel(review.eligibility.windowState)}. Policy {review.eligibility.policyVersion}. Checked at {formatClock(review.checkedAt)}{review.eligibility.needsRecheck ? ', rechecked again at send time' : ''}.
                        </dd>
                      </dl>
                      <p className="muted small">Approval is valid once, for two minutes. Any edit cancels it.</p>
                      <div className="button-row">
                        <button
                          type="button"
                          className="primary"
                          disabled={Boolean(busy[`approve:${reviewDraft.id}`]) || !review.eligibility.eligibleNow}
                          aria-busy={Boolean(busy[`approve:${reviewDraft.id}`])}
                          onClick={approveFromReview}
                        >{busy[`approve:${reviewDraft.id}`] ? 'Approving…' : 'Approve this exact payload'}</button>
                        <button type="button" onClick={() => { setReview(null); pendingFocus.current = 'draft'; }}>Back</button>
                      </div>
                    </section>
                  )}

                  {latestCommand && (
                    <CommandOutcome
                      command={latestCommand}
                      provider={selected.account.provider}
                      outcomeRef={outcomeRef}
                      busy={busy}
                      sendLabel={sendLabel}
                      onDispatch={dispatch}
                      onReconcile={reconcile}
                      onMarkHandled={markHandledElsewhere}
                      onDiscard={discardApproval}
                      onNewReview={prepareNewReview}
                    />
                  )}
                </section>
              )}

              {conversationCommands.length > 0 && (
                <section className="card" aria-labelledby="history-heading">
                  <h3 id="history-heading">Outbox history for this conversation</h3>
                  <ol className="history" data-testid="outbox-history">
                    {conversationCommands.map((command) => {
                      const copy = outcomeCopy(command, selected.account.provider, true);
                      return (
                        <li key={command.id} className={`tone-${copy.tone}`}>
                          <strong>{copy.title}</strong>
                          <span className="muted small"> · approved {formatClock(command.createdAt)}{command.lastAttemptAt ? ` · last update ${formatClock(command.lastAttemptAt)}` : ''}</span>
                          {command.failureReason && <span className="muted small"> · {reasonText(command.failureReason)}</span>}
                        </li>
                      );
                    })}
                  </ol>
                  {liveCommand && liveCommand.id !== latestCommand?.id && <p className="warning-text">An older approval is still active.</p>}
                </section>
              )}
            </>
          )}
        </main>
      </div>
    </div>
  );
}

interface CommandOutcomeProps {
  command: OutboxCommand;
  provider: string;
  outcomeRef: RefObject<HTMLDivElement | null>;
  busy: Record<string, boolean>;
  sendLabel: string;
  onDispatch: (id: string) => void;
  onReconcile: (id: string) => void;
  onMarkHandled: (id: string) => void;
  onDiscard: (id: string) => void;
  onNewReview: (draftId: string) => void;
}

function CommandOutcome({ command, provider, outcomeRef, busy, sendLabel, onDispatch, onReconcile, onMarkHandled, onDiscard, onNewReview }: CommandOutcomeProps) {
  const copy = outcomeCopy(command, provider, true);
  const dispatching = command.state === 'dispatching' || Boolean(busy[`dispatch:${command.id}`]);
  const reconciling = Boolean(busy[`reconcile:${command.id}`]);
  const name = providerName(provider);
  return (
    <div className={`outcome tone-${copy.tone}`} ref={outcomeRef} tabIndex={-1} data-testid="outcome" data-state={command.state} aria-labelledby={`outcome-title-${command.id}`}>
      <h4 id={`outcome-title-${command.id}`} data-testid="outcome-title">{copy.title}</h4>
      <p data-testid="outcome-detail">{copy.detail}</p>
      <div className="button-row">
        {(command.state === 'ready' || command.state === 'dispatching') && (
          <>
            <button type="button" className="primary" disabled={command.state !== 'ready' || dispatching} aria-busy={dispatching} onClick={() => onDispatch(command.id)}>
              {dispatching ? 'Sending…' : sendLabel}
            </button>
            <button type="button" disabled={dispatching} onClick={() => onDiscard(command.id)}>Discard approval</button>
          </>
        )}
        {command.state === 'outcome_unknown' && (
          <button type="button" className="primary" disabled={reconciling} aria-busy={reconciling} onClick={() => onReconcile(command.id)}>
            {reconciling ? 'Checking…' : `Reconcile with ${name} (simulated)`}
          </button>
        )}
        {command.state === 'handoff_opened' && (
          <button type="button" onClick={() => onMarkHandled(command.id)}>I finished it in {name}</button>
        )}
        {(command.state === 'rejected' || command.state === 'expired_or_invalidated') && command.failureReason !== 'review_payload_changed_since_approval' && command.failureReason !== 'superseded_by_newer_draft' && (
          <button type="button" onClick={() => onNewReview(command.draftId)}>Review again</button>
        )}
      </div>
    </div>
  );
}
