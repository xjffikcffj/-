import assert from 'node:assert/strict';
import { execFile, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { promisify } from 'node:util';
import type { Request as ExpressRequest } from 'express';
import type { Firestore } from 'firebase-admin/firestore';
import { isMainEntry } from '../../server.js';
import { createBackendApp } from '../../server/app.js';
import { loadServerConfig, type ServerConfig } from '../../server/config/env.js';
import type {
  DecodedIdTokenLike,
  IdTokenVerifier,
} from '../../server/firebase/adminApp.js';
import type { R2StoragePort } from '../../server/storage/r2Storage.js';

const execFileAsync = promisify(execFile);
const REPO_ROOT = path.resolve(import.meta.dirname, '..', '..');
const TSX_BIN = path.join(REPO_ROOT, 'node_modules', '.bin', 'tsx');
const ESBUILD_BIN = path.join(REPO_ROOT, 'node_modules', '.bin', 'esbuild');

function getFreePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const addr = srv.address();
      if (!addr || typeof addr === 'string') {
        srv.close(() => reject(new Error('Failed to obtain ephemeral port')));
        return;
      }
      const port = addr.port;
      srv.close((err) => {
        if (err) reject(err);
        else resolve(port);
      });
    });
  });
}

async function spawnAndVerifyHealth(params: {
  command: string;
  args: string[];
  cwd: string;
  port: number;
}): Promise<void> {
  const child = spawn(params.command, params.args, {
    cwd: params.cwd,
    env: {
      ...process.env,
      PORT: String(params.port),
      FIRESTORE_EMULATOR_HOST: '127.0.0.1:18080',
      FIREBASE_PROJECT_ID: 'student-learning-platform-2026',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let stdout = '';
  let stderr = '';
  child.stdout?.on('data', (chunk) => {
    stdout += String(chunk);
  });
  child.stderr?.on('data', (chunk) => {
    stderr += String(chunk);
  });

  try {
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(
          new Error(
            `Timed out waiting for server startup. stdout=${stdout} stderr=${stderr}`,
          ),
        );
      }, 10_000);

      const onData = () => {
        if (stdout.includes(`listening on port ${params.port}`)) {
          clearTimeout(timeout);
          resolve();
        }
      };
      child.stdout?.on('data', onData);
      child.once('exit', (code, signal) => {
        clearTimeout(timeout);
        reject(
          new Error(
            `Server exited prematurely (code=${code}, signal=${signal}). stdout=${stdout} stderr=${stderr}`,
          ),
        );
      });
    });

    const res = await fetch(`http://127.0.0.1:${params.port}/api/health`);
    assert.equal(res.status, 200);
    const body = (await res.json()) as { status: string };
    assert.equal(body.status, 'ok');
  } finally {
    if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGTERM');
      await new Promise<void>((resolve) => {
        const killTimer = setTimeout(() => {
          child.kill('SIGKILL');
          resolve();
        }, 3_000);
        child.once('exit', () => {
          clearTimeout(killTimer);
          resolve();
        });
      });
    }
  }
}

function createFakeFirestoreForRateLimitTests(): Firestore {
  const samplePdf = Buffer.from('%PDF-1.4\n%RateLimitTest\n%%EOF\n', 'utf8');
  const sampleSha = createHash('sha256').update(samplePdf).digest('hex');

  const docsByPath = new Map<string, Record<string, unknown>>([
    [
      'branches/branch-1',
      { name: 'العلمي', order: 1, isPublished: true, isArchived: false },
    ],
    [
      'subjects/subj-1',
      {
        name: 'الرياضيات',
        order: 1,
        isPublished: true,
        isArchived: false,
        branchId: {
          path: 'branches/branch-1',
          id: 'branch-1',
          parent: { id: 'branches' },
        },
      },
    ],
    [
      'sections/sec-1',
      {
        name: 'الملازم',
        order: 1,
        isPublished: true,
        isArchived: false,
        subjectId: {
          path: 'subjects/subj-1',
          id: 'subj-1',
          parent: { id: 'subjects' },
        },
      },
    ],
    [
      'study_files/file-1',
      {
        title: 'ملزمة اختبار',
        order: 1,
        isPublished: true,
        isArchived: false,
        sectionId: {
          path: 'sections/sec-1',
          id: 'sec-1',
          parent: { id: 'sections' },
        },
        activeVersionId: 'ver-1',
        activeVersionNumber: 1,
        mimeType: 'application/pdf',
      },
    ],
    [
      'study_files/file-1/versions/ver-1',
      {
        fileId: {
          path: 'study_files/file-1',
          id: 'file-1',
          parent: { id: 'study_files' },
        },
        versionNumber: 1,
        versionLabel: 'الإصدار 1',
        isPublished: true,
        isArchived: false,
        binaryVerificationStatus: 'VERIFIED',
        r2ObjectKey: `study-files/file-1/versions/ver-1/${sampleSha.slice(0, 16)}_11111111-1111-4111-8111-111111111111_22222222-2222-4222-8222-222222222222.pdf`,
        fileSizeBytes: samplePdf.length,
        checksumSha256: sampleSha,
      },
    ],
  ]);

  const makeSnap = (docPath: string) => {
    const data = docsByPath.get(docPath);
    const id = path.posix.basename(docPath);
    return {
      exists: Boolean(data),
      id,
      ref: { path: docPath, id },
      data: () => data,
    };
  };

  const makeDocRef = (docPath: string): Record<string, unknown> => ({
    path: docPath,
    id: path.posix.basename(docPath),
    async get() {
      return makeSnap(docPath);
    },
    collection(subName: string) {
      return makeCollectionRef(`${docPath}/${subName}`);
    },
  });

  const makeCollectionRef = (colPath: string): Record<string, unknown> => ({
    doc(docId: string) {
      return makeDocRef(`${colPath}/${docId}`);
    },
    where() {
      return this;
    },
    async get() {
      const prefix = `${colPath}/`;
      const matched = Array.from(docsByPath.entries())
        .filter(([p]) => {
          if (!p.startsWith(prefix)) return false;
          const rest = p.slice(prefix.length);
          return !rest.includes('/');
        })
        .map(([p]) => makeSnap(p));
      return { docs: matched };
    },
  });

  return {
    collection(name: string) {
      return makeCollectionRef(name);
    },
    async runTransaction(fn: (tx: unknown) => Promise<unknown>) {
      const tx = {
        async get(target: { get?: () => Promise<unknown> }) {
          return target.get ? target.get() : { docs: [] };
        },
        set() {
          return tx;
        },
        update() {
          return tx;
        },
      };
      return fn(tx);
    },
  } as unknown as Firestore;
}

function createFakeR2Adapter(): R2StoragePort {
  const samplePdf = Buffer.from('%PDF-1.4\n%RateLimitTest\n%%EOF\n', 'utf8');
  return {
    isConfigured: () => true,
    async createPresignedPutUrl() {
      return 'https://r2.example.invalid/signed?X-Amz-Signature=deadbeefsecret';
    },
    async getObjectStreamWithETag() {
      async function* singleChunk() {
        yield samplePdf;
      }
      return {
        stream: singleChunk(),
        etag: 'etag-1',
        contentLength: samplePdf.length,
        contentType: 'application/pdf',
      };
    },
    async copyObjectIfMatch() {
      return { etag: 'etag-copy' };
    },
    async headObject() {
      return {
        exists: true,
        contentLength: samplePdf.length,
        etag: 'etag-1',
        contentType: 'application/pdf',
      };
    },
    async deleteObject() {
      // no-op
    },
  };
}

function createFakeTokenVerifier(): IdTokenVerifier {
  const tokens: Record<string, DecodedIdTokenLike> = {
    'admin-secret-token-1': {
      uid: 'admin-uid-sensitive-1',
      email: 'admin1@example.test',
      role: 'admin',
      firebase: { sign_in_provider: 'password' },
    },
    'admin-secret-token-2': {
      uid: 'admin-uid-sensitive-2',
      email: 'admin2@example.test',
      role: 'owner',
      firebase: { sign_in_provider: 'password' },
    },
    'student-secret-token-1': {
      uid: 'student-uid-1',
      email: 'student1@example.test',
      role: 'student',
      firebase: { sign_in_provider: 'password' },
    },
  };

  return {
    async verifyIdToken(idToken: string) {
      const found = tokens[idToken];
      if (!found) {
        const err = new Error('Invalid token');
        (err as { code?: string }).code = 'auth/invalid-id-token';
        throw err;
      }
      return found;
    },
  };
}

describe('Backend Entrypoint Detection (isMainEntry & compiled server.js)', () => {
  it('importing server.ts does not trigger startBackendServer() automatically', async () => {
    // Reached after top-level import of isMainEntry from ../../server.js;
    // additionally verify in an isolated child process with an importing one-liner.
    const { stdout, stderr } = await execFileAsync(
      TSX_BIN,
      [
        '-e',
        "import('./server.ts').then(() => console.log('IMPORT_ONLY_NO_LISTEN'));",
      ],
      { cwd: REPO_ROOT },
    );
    assert.match(stdout, /IMPORT_ONLY_NO_LISTEN/);
    assert.doesNotMatch(stdout, /listening on port/i);
    assert.doesNotMatch(stderr, /listening on port/i);
  });

  it('detects server.ts and compiled server.js across relative, absolute, extensionless, and rejects unrelated paths', () => {
    const tmpDir = fs.mkdtempSync(
      path.join(os.tmpdir(), 'haqibati-entrypoint-unit-'),
    );
    try {
      const tsPath = path.join(tmpDir, 'server.ts');
      const jsPath = path.join(tmpDir, 'server.js');
      const otherTsPath = path.join(tmpDir, 'other-server.ts');
      fs.writeFileSync(tsPath, '// ts\n');
      fs.writeFileSync(jsPath, '// js\n');
      fs.writeFileSync(otherTsPath, '// other\n');

      const tsModuleUrl = new URL(`file://${tsPath}`).href;
      const jsModuleUrl = new URL(`file://${jsPath}`).href;

      assert.equal(
        isMainEntry({ moduleUrl: tsModuleUrl, entryArg: tsPath, cwd: tmpDir }),
        true,
      );
      assert.equal(
        isMainEntry({
          moduleUrl: tsModuleUrl,
          entryArg: './server.ts',
          cwd: tmpDir,
        }),
        true,
      );
      assert.equal(
        isMainEntry({
          moduleUrl: tsModuleUrl,
          entryArg: 'server',
          cwd: tmpDir,
        }),
        true,
      );

      assert.equal(
        isMainEntry({ moduleUrl: jsModuleUrl, entryArg: jsPath, cwd: tmpDir }),
        true,
      );
      assert.equal(
        isMainEntry({
          moduleUrl: jsModuleUrl,
          entryArg: './server.js',
          cwd: tmpDir,
        }),
        true,
      );
      assert.equal(
        isMainEntry({
          moduleUrl: jsModuleUrl,
          entryArg: 'server.js',
          cwd: tmpDir,
        }),
        true,
      );
      assert.equal(
        isMainEntry({
          moduleUrl: jsModuleUrl,
          entryArg: new URL(`file://${jsPath}`).href,
          cwd: tmpDir,
        }),
        true,
      );

      // Must NOT false-positive on suffix matches or missing argv[1]
      assert.equal(
        isMainEntry({
          moduleUrl: tsModuleUrl,
          entryArg: otherTsPath,
          cwd: tmpDir,
        }),
        false,
      );
      assert.equal(
        isMainEntry({
          moduleUrl: jsModuleUrl,
          entryArg: './other-server.js',
          cwd: tmpDir,
        }),
        false,
      );
      assert.equal(
        isMainEntry({ moduleUrl: jsModuleUrl, entryArg: '', cwd: tmpDir }),
        false,
      );
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('starts server when executing server.ts directly via tsx', async () => {
    const port = await getFreePort();
    await spawnAndVerifyHealth({
      command: TSX_BIN,
      args: ['server.ts'],
      cwd: REPO_ROOT,
      port,
    });
  });

  it('builds server.js cleanly and starts when executed directly via node (relative & absolute)', async () => {
    const buildDir = fs.mkdtempSync(
      path.join(os.tmpdir(), 'haqibati-server-build-'),
    );
    const outFile = path.join(buildDir, 'server.js');
    try {
      fs.symlinkSync(
        path.join(REPO_ROOT, 'node_modules'),
        path.join(buildDir, 'node_modules'),
      );
      fs.writeFileSync(
        path.join(buildDir, 'package.json'),
        '{"type":"module"}\n',
      );
      await execFileAsync(
        ESBUILD_BIN,
        [
          path.join(REPO_ROOT, 'server.ts'),
          '--bundle',
          '--platform=node',
          '--format=esm',
          '--packages=external',
          `--outfile=${outFile}`,
        ],
        { cwd: REPO_ROOT },
      );
      assert.equal(fs.existsSync(outFile), true);

      // Run using relative ./server.js
      const portRel = await getFreePort();
      await spawnAndVerifyHealth({
        command: 'node',
        args: ['./server.js'],
        cwd: buildDir,
        port: portRel,
      });

      // Run using absolute path /.../server.js
      const portAbs = await getFreePort();
      await spawnAndVerifyHealth({
        command: 'node',
        args: [outFile],
        cwd: REPO_ROOT,
        port: portAbs,
      });
    } finally {
      fs.rmSync(buildDir, { recursive: true, force: true });
    }
  });
});

describe('Rate Limiting & Trust Proxy Security', () => {
  it('parses TRUST_PROXY fail-closed and rejects unsafe blanket true', () => {
    assert.equal(loadServerConfig({}).trustProxy, false);
    assert.equal(loadServerConfig({ TRUST_PROXY: 'true' }).trustProxy, false);
    assert.equal(loadServerConfig({ TRUST_PROXY: 'all' }).trustProxy, false);
    assert.equal(loadServerConfig({ TRUST_PROXY: '0' }).trustProxy, false);
    assert.equal(loadServerConfig({ TRUST_PROXY: '-1' }).trustProxy, false);
    assert.equal(loadServerConfig({ TRUST_PROXY: '11' }).trustProxy, false);
    assert.equal(loadServerConfig({ TRUST_PROXY: '192.168.1.1' }).trustProxy, false);
    assert.equal(loadServerConfig({ TRUST_PROXY: 'invalid' }).trustProxy, false);
    assert.equal(loadServerConfig({ TRUST_PROXY: '1' }).trustProxy, 1);
    assert.equal(
      loadServerConfig({ TRUST_PROXY: 'loopback' }).trustProxy,
      'loopback',
    );
  });

  it('extracts real Express req.ip for trustProxy=1 vs ignores X-Forwarded-For when trustProxy=false over real TCP connections', async () => {
    // 1) TRUST_PROXY=1 behind a single trusted proxy (direct socket 127.0.0.1)
    const trustedConfig: ServerConfig = {
      ...loadServerConfig({ TRUST_PROXY: '1' }),
      r2Endpoint: 'https://r2.example.invalid',
      r2BucketName: 'bucket',
      r2AccessKeyId: 'key',
      r2SecretAccessKey: 'secret',
    };
    assert.equal(trustedConfig.trustProxy, 1);

    const trustedApp = createBackendApp({
      config: trustedConfig,
      firestore: createFakeFirestoreForRateLimitTests(),
      idTokenVerifier: createFakeTokenVerifier(),
      r2Storage: createFakeR2Adapter(),
      rateLimits: {
        catalog: { windowMs: 60_000, max: 2 },
        'admin-upload-slot': { windowMs: 60_000, max: 2 },
        'admin-verify': { windowMs: 60_000, max: 2 },
        'delivery-stream': { windowMs: 60_000, max: 2 },
        'delivery-download': { windowMs: 60_000, max: 2 },
      },
    });

    const trustedObservations: Array<{
      remoteAddress: string | undefined;
      xff: string | string[] | undefined;
      expressIp: string | undefined;
    }> = [];

    const trustedServer = http.createServer((req, res) => {
      const remoteAddress = req.socket.remoteAddress;
      const xff = req.headers['x-forwarded-for'];
      res.on('finish', () => {
        trustedObservations.push({
          remoteAddress,
          xff,
          expressIp: (req as unknown as ExpressRequest).ip,
        });
      });
      trustedApp(req as never, res as never);
    });

    await new Promise<void>((resolve) => {
      trustedServer.listen(0, '127.0.0.1', () => resolve());
    });
    const trustedAddr = trustedServer.address() as net.AddressInfo;
    const trustedBaseUrl = `http://127.0.0.1:${trustedAddr.port}`;

    try {
      // Attacker rotates leftmost client-supplied X-Forwarded-For entries while the single
      // trusted proxy hop appends the real peer IP (198.51.100.50) at the rightmost position.
      const r1 = await fetch(`${trustedBaseUrl}/api/catalog/branches`, {
        headers: { 'X-Forwarded-For': '1.1.1.1, 198.51.100.50' },
      });
      assert.equal(r1.status, 200);

      const r2 = await fetch(`${trustedBaseUrl}/api/catalog/branches`, {
        headers: { 'X-Forwarded-For': '2.2.2.2, 198.51.100.50' },
      });
      assert.equal(r2.status, 200);

      const blocked = await fetch(`${trustedBaseUrl}/api/catalog/branches`, {
        headers: { 'X-Forwarded-For': '3.3.3.3, 198.51.100.50' },
      });
      assert.equal(blocked.status, 429);
      const blockedJson = (await blocked.json()) as { error: { code: string } };
      assert.equal(blockedJson.error.code, 'RATE_LIMIT_EXCEEDED');

      // Different rightmost proxy-appended IP over the SAME 127.0.0.1 proxy socket MUST
      // receive an independent rate-limit bucket (proving Express extracted 198.51.100.51, not 127.0.0.1).
      const distinctPeer = await fetch(`${trustedBaseUrl}/api/catalog/branches`, {
        headers: { 'X-Forwarded-For': '3.3.3.3, 198.51.100.51' },
      });
      assert.equal(distinctPeer.status, 200);
      assert.equal(distinctPeer.headers.get('RateLimit-Remaining'), '1');

      assert.equal(trustedObservations.length, 4);
      assert.equal(trustedObservations[0].remoteAddress, '127.0.0.1');
      assert.equal(trustedObservations[0].xff, '1.1.1.1, 198.51.100.50');
      assert.equal(trustedObservations[0].expressIp, '198.51.100.50');
      assert.equal(trustedObservations[1].expressIp, '198.51.100.50');
      assert.equal(trustedObservations[2].expressIp, '198.51.100.50');
      assert.equal(trustedObservations[3].remoteAddress, '127.0.0.1');
      assert.equal(trustedObservations[3].expressIp, '198.51.100.51');
    } finally {
      trustedServer.closeAllConnections?.();
      await new Promise<void>((resolve) => trustedServer.close(() => resolve()));
    }

    // 2) Default TRUST_PROXY=false (no trusted proxy): X-Forwarded-For MUST be ignored completely
    const untrustedConfig: ServerConfig = {
      ...loadServerConfig({}),
      r2Endpoint: 'https://r2.example.invalid',
      r2BucketName: 'bucket',
      r2AccessKeyId: 'key',
      r2SecretAccessKey: 'secret',
    };
    assert.equal(untrustedConfig.trustProxy, false);

    const untrustedApp = createBackendApp({
      config: untrustedConfig,
      firestore: createFakeFirestoreForRateLimitTests(),
      idTokenVerifier: createFakeTokenVerifier(),
      r2Storage: createFakeR2Adapter(),
      rateLimits: {
        catalog: { windowMs: 60_000, max: 2 },
        'admin-upload-slot': { windowMs: 60_000, max: 2 },
        'admin-verify': { windowMs: 60_000, max: 2 },
        'delivery-stream': { windowMs: 60_000, max: 2 },
        'delivery-download': { windowMs: 60_000, max: 2 },
      },
    });

    const untrustedObservations: Array<{
      remoteAddress: string | undefined;
      expressIp: string | undefined;
    }> = [];

    const untrustedServer = http.createServer((req, res) => {
      const remoteAddress = req.socket.remoteAddress;
      res.on('finish', () => {
        untrustedObservations.push({
          remoteAddress,
          expressIp: (req as unknown as ExpressRequest).ip,
        });
      });
      untrustedApp(req as never, res as never);
    });

    await new Promise<void>((resolve) => {
      untrustedServer.listen(0, '127.0.0.1', () => resolve());
    });
    const untrustedAddr = untrustedServer.address() as net.AddressInfo;
    const untrustedBaseUrl = `http://127.0.0.1:${untrustedAddr.port}`;

    try {
      const u1 = await fetch(`${untrustedBaseUrl}/api/catalog/branches`, {
        headers: { 'X-Forwarded-For': '1.1.1.1, 198.51.100.50' },
      });
      assert.equal(u1.status, 200);

      const u2 = await fetch(`${untrustedBaseUrl}/api/catalog/branches`, {
        headers: { 'X-Forwarded-For': '2.2.2.2, 198.51.100.51' },
      });
      assert.equal(u2.status, 200);

      // Even changing the rightmost X-Forwarded-For IP cannot bypass the socket IP limit when trustProxy=false
      const u3 = await fetch(`${untrustedBaseUrl}/api/catalog/branches`, {
        headers: { 'X-Forwarded-For': '3.3.3.3, 198.51.100.52' },
      });
      assert.equal(u3.status, 429);

      assert.equal(untrustedObservations.length, 3);
      for (const obs of untrustedObservations) {
        assert.equal(obs.remoteAddress, '127.0.0.1');
        assert.equal(obs.expressIp, '127.0.0.1');
      }
    } finally {
      untrustedServer.closeAllConnections?.();
      await new Promise<void>((resolve) => untrustedServer.close(() => resolve()));
    }
  });

  it('enforces per-route limits, returns 429 + Retry-After, keeps other routes & /api/health unaffected, blocks X-Forwarded-For spoofing, and leaks no secrets', async () => {
    let currentTimeMs = 1_700_000_000_000;
    const config: ServerConfig = {
      ...loadServerConfig({}),
      trustProxy: false,
      r2Endpoint: 'https://r2.example.invalid',
      r2BucketName: 'bucket',
      r2AccessKeyId: 'key',
      r2SecretAccessKey: 'secret',
    };

    const app = createBackendApp({
      config,
      firestore: createFakeFirestoreForRateLimitTests(),
      idTokenVerifier: createFakeTokenVerifier(),
      r2Storage: createFakeR2Adapter(),
      nowMs: () => currentTimeMs,
      rateLimits: {
        catalog: { windowMs: 60_000, max: 3 },
        'admin-upload-slot': { windowMs: 60_000, max: 2 },
        'admin-verify': { windowMs: 60_000, max: 2 },
        'delivery-stream': { windowMs: 60_000, max: 2 },
        'delivery-download': { windowMs: 60_000, max: 2 },
      },
    });

    const server = await new Promise<ReturnType<typeof app.listen>>((resolve) => {
      const s = app.listen(0, '127.0.0.1', () => resolve(s));
    });
    const addr = server.address() as net.AddressInfo;
    const baseUrl = `http://127.0.0.1:${addr.port}`;

    try {
      // 1. Within-limit requests succeed + expose standard RateLimit headers
      for (let i = 0; i < 3; i += 1) {
        const res = await fetch(`${baseUrl}/api/catalog/branches`, {
          headers: {
            // Rotating fake X-Forwarded-For MUST NOT bypass IP limit when trustProxy=false
            'X-Forwarded-For': `203.0.113.${10 + i}`,
          },
        });
        assert.equal(res.status, 200);
        assert.equal(res.headers.get('RateLimit-Limit'), '3');
        assert.equal(res.headers.get('RateLimit-Remaining'), String(2 - i));
      }

      // 2. Exceeding limit returns 429 + Retry-After even with another spoofed X-Forwarded-For
      const blockedCatalog = await fetch(`${baseUrl}/api/catalog/branches`, {
        headers: { 'X-Forwarded-For': '198.51.100.99' },
      });
      assert.equal(blockedCatalog.status, 429);
      const retryAfter = Number(blockedCatalog.headers.get('Retry-After'));
      assert.equal(Number.isInteger(retryAfter) && retryAfter >= 1, true);
      const blockedJson = (await blockedCatalog.json()) as {
        error: { code: string; message: string; details?: Record<string, unknown> };
      };
      assert.equal(blockedJson.error.code, 'RATE_LIMIT_EXCEEDED');

      // 3. Unprotected route (/api/health) and other limited routes stay completely unaffected
      const healthRes = await fetch(`${baseUrl}/api/health`);
      assert.equal(healthRes.status, 200);

      const streamRes = await fetch(
        `${baseUrl}/api/study-files/file-1/versions/ver-1/stream`,
      );
      assert.equal(streamRes.status, 200);

      const downloadRes = await fetch(
        `${baseUrl}/api/study-files/file-1/versions/ver-1/download`,
        { headers: { Authorization: 'Bearer student-secret-token-1' } },
      );
      assert.equal(downloadRes.status, 200);

      // 4. Exhaust admin-upload-slot for admin 1; check 429 leaks no tokens/UIDs/URLs
      for (let i = 0; i < 2; i += 1) {
        const slotRes = await fetch(
          `${baseUrl}/api/admin/study-files/file-1/versions/ver-1/upload-slot`,
          {
            method: 'POST',
            headers: {
              Authorization: 'Bearer admin-secret-token-1',
              'Content-Type': 'application/json',
            },
            body: JSON.stringify({
              expectedSizeBytes: 32,
              expectedSha256: 'a'.repeat(64),
            }),
          },
        );
        // Handler reaches business validation (409 published/400/201) -> not rate-limited!
        assert.notEqual(slotRes.status, 429);
      }

      const blockedSlot = await fetch(
        `${baseUrl}/api/admin/study-files/file-1/versions/ver-1/upload-slot`,
        {
          method: 'POST',
          headers: {
            Authorization: 'Bearer admin-secret-token-1',
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            expectedSizeBytes: 32,
            expectedSha256: 'a'.repeat(64),
          }),
        },
      );
      assert.equal(blockedSlot.status, 429);
      const blockedSlotText = await blockedSlot.text();
      assert.doesNotMatch(blockedSlotText, /admin-secret-token-1/);
      assert.doesNotMatch(blockedSlotText, /admin-uid-sensitive-1/);
      assert.doesNotMatch(blockedSlotText, /X-Amz-Signature/);
      assert.doesNotMatch(blockedSlotText, /127\.0\.0\.1/);

      // Exhausting admin-upload-slot for admin 1 does NOT block admin-verify or admin 2
      const verifyRes = await fetch(
        `${baseUrl}/api/admin/study-files/file-1/versions/ver-1/verify`,
        {
          method: 'POST',
          headers: {
            Authorization: 'Bearer admin-secret-token-1',
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            uploadSessionId: '11111111-1111-4111-8111-111111111111',
          }),
        },
      );
      assert.notEqual(verifyRes.status, 429);

      const admin2SlotRes = await fetch(
        `${baseUrl}/api/admin/study-files/file-1/versions/ver-1/upload-slot`,
        {
          method: 'POST',
          headers: {
            Authorization: 'Bearer admin-secret-token-2',
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            expectedSizeBytes: 32,
            expectedSha256: 'a'.repeat(64),
          }),
        },
      );
      assert.notEqual(admin2SlotRes.status, 429);

      // 5. Advancing clock past windowMs restores access cleanly
      currentTimeMs += 60_001;
      const recoveredCatalog = await fetch(`${baseUrl}/api/catalog/branches`);
      assert.equal(recoveredCatalog.status, 200);
    } finally {
      server.closeAllConnections?.();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });
});
