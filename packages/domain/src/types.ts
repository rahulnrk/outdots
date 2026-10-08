export type Provider = 'gmail' | 'outlook' | 'teams' | 'whatsapp' | 'facebook' | 'instagram' | 'linkedin';
export type ContextCompartment = 'personal' | 'business';
export type CoverageState = 'current' | 'partial' | 'stale' | 'unknown';
export type Priority = 'high' | 'normal' | 'low';
export type WorkflowState = 'needs_review' | 'needs_decision' | 'draft_ready' | 'waiting' | 'snoozed' | 'done';
export type Direction = 'inbound' | 'outbound' | 'unknown';
export type SendRoute = 'api' | 'notification_reply' | 'native_handoff' | 'none';
export type WindowState = 'active' | 'expiring' | 'expired' | 'unknown' | 'not_applicable';
export type OutboxState = 'ready' | 'dispatching' | 'accepted_by_provider' | 'rejected' | 'outcome_unknown' | 'expired_or_invalidated' | 'handoff_opened' | 'externally_handled_unverified';

export interface ConnectorCapabilities {
  provider: Provider;
  accountType: 'personal' | 'work' | 'business' | 'page' | 'professional' | 'unknown';
  adapterVersion: string;
  policyVersion: string;
  readHistory: 'full_within_scope' | 'bounded' | 'none';
  inboundVisibility: 'complete_within_scope' | 'partial' | 'unknown';
  outboundVisibility: 'complete_within_scope' | 'partial' | 'unknown';
  sendRoutes: SendRoute[];
  resultVisibility: 'provider_accepted' | 'delivery' | 'read' | 'unverified';
  syncMode: 'cursor' | 'polling' | 'notifications' | 'webhook' | 'manual';
  requiresMessageBodyServer: boolean;
  qualificationStatus: 'proposed' | 'tested' | 'released' | 'disabled';
  knownLimitations: string[];
}

export interface Account {
  id: string;
  displayName: string;
  provider: Provider;
  compartment: ContextCompartment;
  capabilities: ConnectorCapabilities;
  grants: string[];
  selfIdentities?: string[];
  signatureName?: string;
}

export interface SourceRef {
  id: string;
  kind: 'message' | 'observation' | 'fact' | 'user_decision';
  revision: number;
}

export interface MessageRevision {
  id: string;
  accountId: string;
  conversationId: string;
  sender: string;
  recipients: string[];
  direction: Direction;
  subject?: string;
  body: string;
  occurredAt: string;
  revision: number;
  tombstone?: boolean;
}

export interface Observation {
  id: string;
  accountId: string;
  conversationId: string;
  packageId: string;
  senderHint: string;
  textFragments: string[];
  observedAt: string;
  revision: number;
  contentAvailable: boolean;
  truncationState: 'complete' | 'truncated' | 'unknown';
  correlationConfidence: 'high' | 'medium' | 'low';
}

export interface Conversation {
  id: string;
  accountId: string;
  title: string;
  participants: string[];
  compartment: ContextCompartment;
  coverage: CoverageState;
  coverageExplanation: string;
  workflow: WorkflowState;
  aiPriority: Priority;
  latestEventAt: string;
  sourceRefs: SourceRef[];
}

export interface PriorityOverride {
  id: string;
  conversationId: string;
  priority: Priority;
  createdAt: string;
  reason: string;
}

export interface MemoryFact {
  id: string;
  compartment: ContextCompartment;
  subject: string;
  claim: string;
  evidence: SourceRef[];
  confirmed: boolean;
}

export interface ActionItem {
  id: string;
  conversationId: string;
  description: string;
  evidence: SourceRef[];
  dueAt?: string;
  inferred: boolean;
  dismissed?: boolean;
}

export interface Draft {
  id: string;
  accountId: string;
  conversationId: string;
  route: SendRoute;
  recipients: string[];
  subject?: string;
  body: string;
  attachments?: Attachment[];
  evidence: SourceRef[];
  missingDecisions: string[];
  status: 'proposed' | 'stale' | 'ready_for_review' | 'approved' | 'sent' | 'blocked';
  createdAt: string;
}

export interface Attachment {
  id: string;
  name: string;
  sha256: string;
  sizeBytes: number;
}

export interface ReplyEligibility {
  windowState: WindowState;
  closesAt?: string;
  route: SendRoute;
  eligibleNow: boolean;
  reasonCode: string;
  policyVersion: string;
  needsRecheck: boolean;
}

export interface ReviewPayload {
  accountId: string;
  conversationId: string;
  route: SendRoute;
  recipients: string[];
  subject?: string;
  body: string;
  attachments?: Attachment[];
  evidence: SourceRef[];
  policyVersion: string;
  grantVersion: string;
}

export interface Approval {
  id: string;
  draftId: string;
  reviewDigest: string;
  approvedAt: string;
  expiresAt: string;
  consumed: boolean;
  sourceVersions?: SourceRef[];
}

export interface OutboxCommand {
  id: string;
  draftId: string;
  reviewDigest: string;
  policyVersion: string;
  grantVersion: string;
  route: SendRoute;
  state: OutboxState;
  createdAt: string;
  lastAttemptAt?: string;
  receipt?: string;
  failureReason?: string;
  sourceVersions?: SourceRef[];
}

export interface HubState {
  accounts: Account[];
  conversations: Conversation[];
  messages: MessageRevision[];
  observations: Observation[];
  facts: MemoryFact[];
  actionItems: ActionItem[];
  priorityOverrides: PriorityOverride[];
  drafts: Draft[];
  approvals: Approval[];
  outbox: OutboxCommand[];
}

export interface InboxItem {
  conversation: Conversation;
  account: Account;
  priority: Priority;
  prioritySource: 'user' | 'ai';
  actionCount: number;
  latestSnippet: string;
  coverageWarning: string;
}
