import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';

export interface ReleaseManifest {
  appVersion: string;
  minimumRuntime: string;
  modelPackage: null;
  policyVersion: string;
  signed: false;
}

const manifest: ReleaseManifest = {
  appVersion: '0.1.0-demo',
  minimumRuntime: 'demo-web',
  modelPackage: null,
  policyVersion: 'policy-2026-10-demo',
  signed: false
};

const MAX_BODY_BYTES = 16_384;

function json(response: ServerResponse, status: number, body: unknown) {
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff'
  });
  response.end(JSON.stringify(body, null, 2));
}

function readJsonBody(request: IncomingMessage): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    let tooLarge = false;
    request.on('data', (chunk: Buffer | string) => {
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      size += bytes.byteLength;
      if (size > MAX_BODY_BYTES) {
        tooLarge = true;
        return;
      }
      chunks.push(bytes);
    });
    request.on('end', () => {
      if (tooLarge) {
        reject(new Error('body_too_large'));
        return;
      }
      try {
        const data = Buffer.concat(chunks).toString('utf8');
        resolve(data ? JSON.parse(data) : undefined);
      } catch {
        reject(new Error('invalid_json'));
      }
    });
    request.on('error', reject);
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value));
}
function exactKeys(body: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(body).length === keys.length && keys.every((key) => Object.prototype.hasOwnProperty.call(body, key));
}
function validInstallation(body: unknown): body is { installationId: string } {
  return isRecord(body) && exactKeys(body, ['installationId']) && typeof body.installationId === 'string' && /^inst_[A-Za-z0-9]{16,}$/.test(body.installationId);
}
function validDiagnostics(body: unknown): boolean {
  if (!isRecord(body) || !exactKeys(body, ['schemaVersion', 'appVersion', 'errorCodes', 'counters'])) return false;
  if (body.schemaVersion !== 1 || typeof body.appVersion !== 'string' || body.appVersion.length > 64) return false;
  if (!Array.isArray(body.errorCodes) || body.errorCodes.length > 100 || !body.errorCodes.every((code) => typeof code === 'string' && /^[a-z][a-z0-9_.-]{0,63}$/.test(code))) return false;
  return isRecord(body.counters) && Object.entries(body.counters).every(([key, value]) => /^[a-z][a-z0-9_.-]{0,63}$/.test(key) && typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 1_000_000);
}

export function createPublisherServer() {
  return createServer(async (request, response) => {
    try {
      await handleRequest(request, response);
    } catch (error) {
      if (response.headersSent) return;
      const reason = error instanceof Error ? error.message : 'invalid_request';
      json(response, reason === 'body_too_large' ? 413 : reason === 'unsupported_media_type' ? 415 : 400, { error: reason });
    }
  });
}

async function handleRequest(request: IncomingMessage, response: ServerResponse) {
  const url = new URL(request.url ?? '/', 'http://localhost');
  if (request.method === 'GET' && url.pathname === '/release-manifest') {
    json(response, 200, manifest);
    return;
  }
  if (request.method !== 'POST') {
    json(response, 404, { error: 'not_found' });
    return;
  }
  if (!request.headers['content-type']?.toLowerCase().startsWith('application/json')) throw new Error('unsupported_media_type');

  if (url.pathname === '/entitlements/check') {
    const body = await readJsonBody(request);
    if (!validInstallation(body)) {
      json(response, 400, { error: 'invalid_entitlement_request' });
      return;
    }
    // No billing/entitlement authority is configured. Never fabricate a grant.
    json(response, 503, { error: 'entitlement_provider_unavailable' });
    return;
  }
  if (url.pathname === '/diagnostics') {
    const body = await readJsonBody(request);
    if (!validDiagnostics(body)) {
      json(response, 400, { error: 'diagnostics_must_be_sanitized' });
      return;
    }
    json(response, 202, { accepted: true });
    return;
  }
  json(response, 404, { error: 'not_found', allowed: ['/release-manifest', '/entitlements/check', '/diagnostics'] });
}

export function publisherListenOptions(env: NodeJS.ProcessEnv = process.env): { host: string; port: number } {
  const port = Number(env.PORT ?? 8787);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('invalid_port');
  return { host: env.PUBLISHER_HOST ?? '127.0.0.1', port };
}

if (process.env['MINI_PUBLISHER_LISTEN'] === '1') {
  const options = publisherListenOptions();
  createPublisherServer().listen(options.port, options.host, () => console.log(`Publisher service listening on http://${options.host}:${options.port}`));
}
