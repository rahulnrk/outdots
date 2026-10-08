import type { ConnectorCapabilities, Provider } from './types';

export function createCapability(provider: Provider, overrides: Partial<ConnectorCapabilities> = {}): ConnectorCapabilities {
  const base: ConnectorCapabilities = {
    provider,
    accountType: 'personal',
    adapterVersion: 'demo-0.1',
    policyVersion: 'policy-2026-10-demo',
    readHistory: 'bounded',
    inboundVisibility: 'unknown',
    outboundVisibility: 'unknown',
    sendRoutes: ['native_handoff'],
    resultVisibility: 'unverified',
    syncMode: 'manual',
    requiresMessageBodyServer: false,
    qualificationStatus: 'proposed',
    knownLimitations: []
  };

  if (provider === 'gmail') {
    return {
      ...base,
      readHistory: 'full_within_scope',
      inboundVisibility: 'complete_within_scope',
      outboundVisibility: 'complete_within_scope',
      sendRoutes: ['api'],
      resultVisibility: 'provider_accepted',
      syncMode: 'cursor',
      qualificationStatus: 'tested',
      knownLimitations: ['Demo data only. Real Gmail API OAuth and review are not configured.'],
      ...overrides
    };
  }

  if (provider === 'whatsapp') {
    return {
      ...base,
      readHistory: 'none',
      inboundVisibility: 'partial',
      outboundVisibility: 'unknown',
      sendRoutes: ['native_handoff'],
      resultVisibility: 'unverified',
      syncMode: 'notifications',
      qualificationStatus: 'tested',
      knownLimitations: [
        'Personal WhatsApp is represented by opt-in notification observations only.',
        'Outgoing activity may be invisible, so handled-elsewhere correction remains explicit.',
        'Native handoff is not a send receipt.'
      ],
      ...overrides
    };
  }

  return { ...base, ...overrides };
}
