import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import { request } from 'node:http';
import { createPublisherServer, publisherListenOptions } from '../src/server';

/** Security review tests for the publisher boundary (docs/SECURITY_REVIEW.md, PUB-*). */

let server: Server;
let base: string;

beforeAll(async () => {
  server = createPublisherServer();
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  base = `http://127.0.0.1:${port}`;
});

afterAll(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

async function post(path: string, body: unknown, contentType = 'application/json') {
  const response = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'content-type': contentType },
    body: typeof body === 'string' ? body : JSON.stringify(body)
  });
  return { status: response.status, json: await response.json().catch(() => undefined) as Record<string, unknown> | undefined };
}

describe('PUB-01 no fabricated entitlement', () => {
  it('does not grant an entitlement when no billing provider is configured', async () => {
    const result = await post('/entitlements/check', { installationId: 'inst_0123456789abcdef' });
    expect(result.status).not.toBe(200);
    expect(result.json?.['expiresAt']).toBeUndefined();
  });
});

describe('PUB-02 private content cannot be smuggled through allowed endpoints', () => {
  const smuggled = [
    { installationId: 'inst_0123456789abcdef', body: 'Can you deliver by Friday for 80,000?' },
    { installationId: 'inst_0123456789abcdef', text: 'hello from my inbox' },
    { installationId: 'inst_0123456789abcdef', notes: 'Vikram wants a call before 6' },
    { installationId: 'inst_0123456789abcdef', blob: 'Q2FuIHlvdSBkZWxpdmVyIGJ5IEZyaWRheT8=' }
  ];

  for (const payload of smuggled) {
    it(`rejects unknown entitlement field ${Object.keys(payload)[1]}`, async () => {
      const result = await post('/entitlements/check', payload);
      expect(result.status).toBe(400);
    });
  }

  it('rejects diagnostics with free-text values hidden in an allowed-looking field', async () => {
    const result = await post('/diagnostics', {
      schemaVersion: 1,
      appVersion: '0.1.0',
      errorCodes: ['Anika Sharma asked to deliver landing page by Friday for 80000']
    });
    expect(result.status).toBe(400);
  });

  it('rejects diagnostics with unknown top-level fields', async () => {
    const result = await post('/diagnostics', { schemaVersion: 1, appVersion: '0.1.0', errorCodes: [], threadDump: 'x' });
    expect(result.status).toBe(400);
  });

  it('accepts a sanitized aggregate diagnostics payload', async () => {
    const result = await post('/diagnostics', { schemaVersion: 1, appVersion: '0.1.0', errorCodes: ['sync.cursor_expired'], counters: { 'draft.generated': 3 } });
    expect(result.status).toBe(202);
  });

  it('requires a JSON content type', async () => {
    const result = await post('/diagnostics', 'schemaVersion=1', 'text/plain');
    expect(result.status).toBe(415);
  });
});

describe('PUB-03 body size limit is enforced in bytes and answered', () => {
  it('returns 413 for a multi-byte payload over the byte limit', async () => {
    const big = JSON.stringify({ schemaVersion: 1, appVersion: '0.1.0', errorCodes: [], pad: '₹'.repeat(6000) });
    const status = await new Promise<number>((resolve, reject) => {
      const req = request(`${base}/diagnostics`, { method: 'POST', headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(big) } }, (res) => {
        res.resume();
        resolve(res.statusCode ?? 0);
      });
      req.on('error', reject);
      req.end(big);
    });
    expect(status).toBe(413);
  });
});

describe('PUB-04 listen defaults', () => {
  it('binds to loopback unless a host is explicitly configured', () => {
    expect(publisherListenOptions({}).host).toBe('127.0.0.1');
    expect(publisherListenOptions({ PUBLISHER_HOST: '0.0.0.0', PORT: '9000' })).toEqual({ host: '0.0.0.0', port: 9000 });
  });

  it('rejects an invalid port instead of listening on a random one', () => {
    expect(() => publisherListenOptions({ PORT: 'abc' })).toThrow();
  });
});

describe('PUB-05 manifest truthfulness', () => {
  it('declares that the demo manifest is unsigned and carries no model artifact', async () => {
    const response = await fetch(`${base}/release-manifest`);
    const manifest = await response.json() as Record<string, unknown>;
    expect(manifest['signed']).toBe(false);
    expect(response.headers.get('x-content-type-options')).toBe('nosniff');
  });
});
