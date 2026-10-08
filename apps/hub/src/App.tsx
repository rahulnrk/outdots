import { useMemo, useState } from 'react';
import {
  approveDraft,
  buildInbox,
  createDemoState,
  createDraft,
  demoNow,
  dispatchOutbox,
  evaluateReplyEligibility,
  retrieveContext,
  setPriorityOverride,
  type DraftIntent,
  type HubState,
  type Priority
} from '@mini/domain';

function formatTime(value: string): string {
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', day: '2-digit', month: 'short' }).format(new Date(value));
}

export function App() {
  const [state, setState] = useState<HubState>(() => createDemoState());
  const inbox = useMemo(() => buildInbox(state), [state]);
  const [selectedId, setSelectedId] = useState(inbox[0]?.conversation.id ?? '');
  const selected = inbox.find((item) => item.conversation.id === selectedId) ?? inbox[0];
  const [intent, setIntent] = useState<DraftIntent>({ stance: 'accept', price: '₹80,000', date: 'Friday' });
  const context = selected ? retrieveContext(state, selected.conversation.id) : undefined;
  const latestDraft = [...state.drafts].reverse().find((draft) => draft.conversationId === selected?.conversation.id);
  const latestCommand = latestDraft ? state.outbox.find((command) => command.draftId === latestDraft.id) : undefined;
  const latestInbound = selected ? state.messages
    .filter((message) => message.conversationId === selected.conversation.id && message.direction === 'inbound')
    .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))[0] : undefined;
  const route = selected?.account.capabilities.sendRoutes.includes('api') ? 'api' : 'native_handoff';
  const eligibility = selected ? evaluateReplyEligibility(selected.account, route, demoNow, latestInbound?.occurredAt) : undefined;

  function generateDraft() {
    if (!selected || !context) return;
    const draft = createDraft(state, selected.account, selected.conversation.id, context, intent, demoNow);
    setState((current) => ({ ...current, drafts: [...current.drafts.filter((item) => item.id !== draft.id), draft] }));
  }

  async function approveAndQueue() {
    if (!selected || !latestDraft) return;
    const { approval, command } = await approveDraft(latestDraft, selected.account, demoNow);
    setState((current) => ({
      ...current,
      approvals: [...current.approvals, approval],
      outbox: [...current.outbox, command],
      drafts: current.drafts.map((draft) => draft.id === latestDraft.id ? { ...draft, status: 'approved' } : draft)
    }));
  }

  async function dispatch() {
    if (!latestCommand) return;
    setState(await dispatchOutbox(state, latestCommand.id, '2026-10-08T12:00:30.000Z'));
  }

  function overridePriority(priority: Priority) {
    if (!selected) return;
    setState((current) => setPriorityOverride(current, selected.conversation.id, priority, 'User marked from hub UI', demoNow));
  }

  return (
    <main className="shell">
      <section className="hero">
        <div>
          <p className="eyebrow">Private local-first communication hub</p>
          <h1>Review, decide, draft and send without losing source boundaries.</h1>
          <p className="hero-copy">This build proves the core architecture from the markdown: Gmail API-style evidence and WhatsApp notification observations share one workflow while keeping coverage, approval and action routes honest.</p>
        </div>
        <div className="status-card">
          <span className="status-dot" />
          <strong>Publisher boundary</strong>
          <p>No private inbox, prompt or memory endpoint exists in this implementation.</p>
        </div>
      </section>

      <section className="layout">
        <aside className="inbox-panel">
          <div className="panel-heading">
            <span>Attention inbox</span>
            <strong>{inbox.length}</strong>
          </div>
          {inbox.map((item) => (
            <button key={item.conversation.id} className={`thread-card ${item.conversation.id === selected?.conversation.id ? 'selected' : ''}`} onClick={() => setSelectedId(item.conversation.id)}>
              <div className="thread-topline">
                <span className={`provider provider-${item.account.provider}`}>{item.account.provider}</span>
                <span className={`priority priority-${item.priority}`}>{item.priority} · {item.prioritySource}</span>
              </div>
              <strong>{item.conversation.title}</strong>
              <p>{item.latestSnippet}</p>
              {item.coverageWarning && <span className="warning">{item.coverageWarning}</span>}
            </button>
          ))}
        </aside>

        {selected && context && (
          <section className="detail-panel">
            <header className="detail-header">
              <div>
                <p className="eyebrow">{selected.account.displayName}</p>
                <h2>{selected.conversation.title}</h2>
                <p className="muted">Coverage: {selected.conversation.coverage}. Workflow: {selected.conversation.workflow.replace('_', ' ')}.</p>
              </div>
              <div className="priority-controls">
                <span>User priority</span>
                {(['high', 'normal', 'low'] as Priority[]).map((priority) => (
                  <button key={priority} onClick={() => overridePriority(priority)}>{priority}</button>
                ))}
              </div>
            </header>

            <div className="grid two">
              <article className="card">
                <h3>Evidence bundle</h3>
                {context.coverageWarning && <p className="warning block">{context.coverageWarning}</p>}
                {context.messages.map((message) => (
                  <div key={message.id} className="evidence">
                    <span>{formatTime(message.occurredAt)} · message rev {message.revision}</span>
                    <strong>{message.sender}</strong>
                    <p>{message.body}</p>
                  </div>
                ))}
                {context.observations.map((observation) => (
                  <div key={observation.id} className="evidence partial">
                    <span>{formatTime(observation.observedAt)} · observation rev {observation.revision}</span>
                    <strong>{observation.senderHint}</strong>
                    <p>{observation.textFragments.join(' ')}</p>
                  </div>
                ))}
                {context.facts.map((fact) => <p key={fact.id} className="fact">Confirmed memory: {fact.claim}</p>)}
              </article>

              <article className="card">
                <h3>Route and eligibility</h3>
                <dl>
                  <dt>Selected route</dt><dd>{route}</dd>
                  <dt>Eligibility</dt><dd>{eligibility?.eligibleNow ? 'Allowed now' : 'Blocked'}</dd>
                  <dt>Reason</dt><dd>{eligibility?.reasonCode}</dd>
                  <dt>Result visibility</dt><dd>{selected.account.capabilities.resultVisibility}</dd>
                </dl>
                <h4>Capability snapshot</h4>
                <ul className="compact-list">
                  {selected.account.capabilities.knownLimitations.map((item) => <li key={item}>{item}</li>)}
                </ul>
              </article>
            </div>

            <article className="card composer">
              <div>
                <h3>User decision before drafting</h3>
                <p className="muted">The local drafting engine must not invent price, date or business commitment.</p>
              </div>
              <div className="form-grid">
                <label>Decision
                  <select value={intent.stance ?? ''} onChange={(event) => setIntent((current) => ({ ...current, stance: event.target.value as DraftIntent['stance'] }))}>
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
              <button className="primary" onClick={generateDraft}>Generate evidence-grounded draft</button>
            </article>

            {latestDraft && (
              <article className="card draft-card">
                <div className="draft-header">
                  <div>
                    <h3>Exact review payload</h3>
                    <p className="muted">Approval binds to route, recipients, body and evidence revisions.</p>
                  </div>
                  <span className={`status status-${latestDraft.status}`}>{latestDraft.status.replaceAll('_', ' ')}</span>
                </div>
                {latestDraft.missingDecisions.length > 0 && (
                  <div className="missing">
                    <strong>Needs Rahul decision</strong>
                    <ul>{latestDraft.missingDecisions.map((item) => <li key={item}>{item}</li>)}</ul>
                  </div>
                )}
                <dl className="review">
                  <dt>Recipients</dt><dd>{latestDraft.recipients.join(', ')}</dd>
                  <dt>Route</dt><dd>{latestDraft.route}</dd>
                  <dt>Evidence</dt><dd>{latestDraft.evidence.map((ref) => `${ref.kind}:${ref.id}@${ref.revision}`).join(', ')}</dd>
                </dl>
                <pre>{latestDraft.body}</pre>
                <div className="actions">
                  <button disabled={latestDraft.status !== 'ready_for_review'} onClick={approveAndQueue}>Approve exact payload</button>
                  <button disabled={!latestCommand || latestCommand.state !== 'ready'} onClick={dispatch}>{latestDraft.route === 'native_handoff' ? 'Open source app handoff' : 'Dispatch Gmail send'}</button>
                  {latestCommand && <span className="muted">Outbox: {latestCommand.state.replaceAll('_', ' ')}</span>}
                </div>
                {latestCommand?.failureReason && <p className="warning block">Dispatch blocked: {latestCommand.failureReason}</p>}
              </article>
            )}
          </section>
        )}
      </section>
    </main>
  );
}
