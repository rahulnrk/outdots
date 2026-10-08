import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';

export interface ReleaseManifest {
  appVersion: string;
  minimumRuntime: string;
  modelPackage: {
    id: string;
    version: string;
    sha256: string;
    sizeBytes: number;
  };
  policyVersion: string;
}

const manifest: ReleaseManifest = {
  appVersion: '0.1.0-demo',
  minimumRuntime: 'demo-web',
  modelPackage: {
    id: 'local-drafting-placeholder',
    version: '0.0.0',
    sha256: 'not-a-real-model-package',
    sizeBytes: 0
  },
  policyVersion: 'policy-2026-10-demo'
};

function json(response: ServerResponse, status: number, body: unknown) {
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store'
  });
  response.end(JSON.stringify(body, null, 2));
}

function readJsonBody(request: IncomingMessage): Promise<unknown> {
  return new Promise((resolve, reject) => {
    let data = '';
    request.on('data', (chunk) => {
      data += String(chunk);
      if (data.length > 16_384) {
        request.destroy(new Error('body_too_large'));
      }
    });
    request.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : undefined);
      } catch (error) {
        reject(error);
      }
    });
    request.on('error', reject);
  });
}

function hasPrivateContentShape(body: unknown): boolean {
  if (!body || typeof body !== 'object') return false;
  const text = JSON.stringify(body).toLowerCase();
  return ['messagebody', 'message_body', 'subject', 'prompt', 'recipient', 'contact', 'memory', 'inbox'].some((key) => text.includes(key));
}

export function createPublisherServer() {
  return createServer(async (request, response) => {
    const url = new URL(request.url ?? '/', 'http://localhost');

    if (request.method === 'GET' && url.pathname === '/release-manifest') {
      json(response, 200, manifest);
      return;
    }

    if (request.method === 'POST' && url.pathname === '/entitlements/check') {
      const body = await readJsonBody(request);
      if (hasPrivateContentShape(body)) {
        json(response, 400, { error: 'private_content_not_accepted' });
        return;
      }
      json(response, 200, {
        installationId: 'demo-installation',
        product: 'founder-pilot',
        expiresAt: '2026-12-31T23:59:59.000Z',
        graceDays: 14
      });
      return;
    }

    if (request.method === 'POST' && url.pathname === '/diagnostics') {
      const body = await readJsonBody(request);
      if (hasPrivateContentShape(body)) {
        json(response, 400, { error: 'diagnostics_must_be_sanitized' });
        return;
      }
      json(response, 202, { accepted: true });
      return;
    }

    json(response, 404, { error: 'not_found', allowed: ['/release-manifest', '/entitlements/check', '/diagnostics'] });
  });
}

if (process.env['MINI_PUBLISHER_LISTEN'] === '1') {
  const port = Number(process.env['PORT'] ?? 8787);
  createPublisherServer().listen(port, () => {
    console.log(`Publisher service listening on http://127.0.0.1:${port}`);
  });
}
