import type { Account, Draft, GatewayResult, OutboxCommand, ProviderGateway } from '@mini/domain';

export type FixtureMode = 'accept' | 'reject' | 'timeout';

export interface FixtureRecord {
  commandId: string;
  draftId: string;
  provider: string;
  recipients: string[];
  body: string;
  receipt: string;
}

/**
 * Simulated provider used by the browser demo. It never touches a network.
 * "timeout" models a request that reached the provider but whose response was lost,
 * so the hub must reconcile instead of resending.
 */
export class FixtureProvider {
  readonly records: FixtureRecord[] = [];
  requestCount = 0;

  constructor(public mode: FixtureMode = 'accept', public delayMs = 400) {}

  gateway(): ProviderGateway {
    return {
      send: async (command: OutboxCommand, draft: Draft, account: Account): Promise<GatewayResult> => {
        this.requestCount += 1;
        const mode = this.mode;
        if (this.delayMs > 0) await new Promise((resolve) => setTimeout(resolve, this.delayMs));
        if (mode === 'reject') return { kind: 'rejected', reason: 'fixture_provider_rejected' };
        const receipt = `fixture-${account.provider}-${this.records.length + 1}`;
        this.records.push({ commandId: command.id, draftId: draft.id, provider: account.provider, recipients: draft.recipients, body: draft.body, receipt });
        if (mode === 'timeout') return { kind: 'unknown', reason: 'fixture_provider_timeout' };
        return { kind: 'accepted', receipt };
      }
    };
  }

  /** Look the command up on the simulated provider side. Undefined means no record was found. */
  lookup(commandId: string): FixtureRecord | undefined {
    return this.records.find((record) => record.commandId === commandId);
  }
}

export function fixtureModeFromUrl(search: string): FixtureMode {
  const value = new URLSearchParams(search).get('fixture');
  return value === 'reject' || value === 'timeout' ? value : 'accept';
}

export function fixtureDelayFromUrl(search: string): number {
  const value = Number(new URLSearchParams(search).get('delay'));
  return Number.isFinite(value) && value >= 0 && new URLSearchParams(search).has('delay') ? value : 400;
}
