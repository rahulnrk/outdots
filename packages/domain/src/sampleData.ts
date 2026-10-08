import type { HubState } from './types';
import { createCapability } from './capabilities';

export const demoNow = '2026-10-08T12:00:00.000Z';

export function createDemoState(): HubState {
  return {
    accounts: [
      {
        id: 'acct-gmail-rahul',
        displayName: 'Rahul Gmail',
        provider: 'gmail',
        compartment: 'business',
        capabilities: createCapability('gmail'),
        grants: ['read', 'send']
      },
      {
        id: 'acct-whatsapp-android',
        displayName: 'WhatsApp Android observations',
        provider: 'whatsapp',
        compartment: 'business',
        capabilities: createCapability('whatsapp'),
        grants: ['notification_access']
      }
    ],
    conversations: [
      {
        id: 'conv-gmail-1',
        accountId: 'acct-gmail-rahul',
        title: 'Website launch quote',
        participants: ['Rahul', 'Anika Sharma'],
        compartment: 'business',
        coverage: 'current',
        coverageExplanation: '',
        workflow: 'needs_decision',
        aiPriority: 'high',
        latestEventAt: '2026-10-08T10:20:00.000Z',
        sourceRefs: [{ id: 'msg-gmail-1', kind: 'message', revision: 1 }]
      },
      {
        id: 'conv-wa-1',
        accountId: 'acct-whatsapp-android',
        title: 'WhatsApp lead notification',
        participants: ['Rahul', 'Vikram'],
        compartment: 'business',
        coverage: 'partial',
        coverageExplanation: 'Observed from Android notifications only. Earlier history and outgoing replies may be missing.',
        workflow: 'needs_review',
        aiPriority: 'normal',
        latestEventAt: '2026-10-08T11:35:00.000Z',
        sourceRefs: [{ id: 'obs-wa-1', kind: 'observation', revision: 1 }]
      }
    ],
    messages: [
      {
        id: 'msg-gmail-1',
        accountId: 'acct-gmail-rahul',
        conversationId: 'conv-gmail-1',
        sender: 'Anika Sharma',
        recipients: ['Rahul'],
        direction: 'inbound',
        subject: 'Website launch quote',
        body: 'Can you deliver the landing page by Friday for ₹80,000? We need confirmation today so procurement can release the PO.',
        occurredAt: '2026-10-08T10:20:00.000Z',
        revision: 1
      }
    ],
    observations: [
      {
        id: 'obs-wa-1',
        accountId: 'acct-whatsapp-android',
        conversationId: 'conv-wa-1',
        packageId: 'com.whatsapp',
        senderHint: 'Vikram',
        textFragments: ['Need a quick update on the proposal. Can we talk before 6?'],
        observedAt: '2026-10-08T11:35:00.000Z',
        revision: 1,
        contentAvailable: true,
        truncationState: 'complete',
        correlationConfidence: 'medium'
      }
    ],
    facts: [
      {
        id: 'fact-style-1',
        compartment: 'business',
        subject: 'Rahul writing style',
        claim: 'Keeps client replies short, direct and polite with a clear next step.',
        evidence: [{ id: 'msg-gmail-1', kind: 'message', revision: 1 }],
        confirmed: true
      }
    ],
    actionItems: [
      {
        id: 'action-gmail-1',
        conversationId: 'conv-gmail-1',
        description: 'Confirm price and delivery date before procurement releases PO.',
        evidence: [{ id: 'msg-gmail-1', kind: 'message', revision: 1 }],
        dueAt: '2026-10-08T23:59:00.000Z',
        inferred: true
      },
      {
        id: 'action-wa-1',
        conversationId: 'conv-wa-1',
        description: 'Respond or mark handled elsewhere because outbound state is unavailable.',
        evidence: [{ id: 'obs-wa-1', kind: 'observation', revision: 1 }],
        dueAt: '2026-10-08T12:30:00.000Z',
        inferred: true
      }
    ],
    priorityOverrides: [],
    drafts: [],
    approvals: [],
    outbox: []
  };
}
