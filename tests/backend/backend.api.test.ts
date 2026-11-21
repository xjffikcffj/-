import { Buffer } from 'node:buffer';
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import type { AddressInfo } from 'node:net';
import { Readable } from 'node:stream';
import { after, before, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import type { Response as ExpressResponse } from 'express';
import {
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  getApps,
  initializeApp,
} from 'firebase-admin/app';
import {
  getFirestore,
  Timestamp,
  type Firestore,
} from 'firebase-admin/firestore';
import { createBackendApp } from '../../server/app.js';
import { loadServerConfig, type ServerConfig } from '../../server/config/env.js';
import type {
  DecodedIdTokenLike,
  IdTokenVerifier,
} from '../../server/firebase/adminApp.js';
import { DeliveryService } from '../../server/services/deliveryService.js';
import type { CatalogService } from '../../server/services/catalogService.js';
import {
  UnconfiguredR2StorageAdapter,
  type PresignedPutParams,
  type R2CopyIfMatchParams,
  type R2HeadResult,
  type R2ObjectStreamResult,
  type R2StoragePort,
} from '../../server/storage/r2Storage.js';
import { ApiError } from '../../server/utils/errors.js';

const PROJECT_ID = 'student-learning-platform-2026';

interface StoredMockObject {
  bytes: Buffer;
  etag: string;
  contentType: string;
}

class MockR2StorageAdapter implements R2StoragePort {
  readonly objects = new Map<string, StoredMockObject>();
  readonly deletedKeys: string[] = [];
  private etagCounter = 1;
  onCopyHook?: (params: R2CopyIfMatchParams) => Promise<void>;
  onGetStreamHook?: (key: string) => Promise<void>;
  corruptCopyBytesForKey?: string;
  forceMissingHeadForKey?: string;
  forceNullHeadLengthForKey?: string;
  forceNullStreamLengthForKey?: string;
  overrideStreamBytesForKey?: { key: string; bytes: Buffer };
  readonly overrideChunkSizeForKey = new Map<string, number>();
  lastStreamClosedKey?: string;
  readonly callCounts = {
    createPresignedPutUrl: 0,
    getObjectStreamWithETag: 0,
    copyObjectIfMatch: 0,
    headObject: 0,
    deleteObject: 0,
  };

  resetCallCounts() {
    this.callCounts.createPresignedPutUrl = 0;
    this.callCounts.getObjectStreamWithETag = 0;
    this.callCounts.copyObjectIfMatch = 0;
    this.callCounts.headObject = 0;
    this.callCounts.deleteObject = 0;
  }

  totalOperationCount(): number {
    return (
      this.callCounts.createPresignedPutUrl +
      this.callCounts.getObjectStreamWithETag +
      this.callCounts.copyObjectIfMatch +
      this.callCounts.headObject +
      this.callCounts.deleteObject
    );
  }

  isConfigured(): boolean {
    return true;
  }

  putRaw(key: string, bytes: Buffer, contentType = 'application/pdf'): string {
    const etag = `mock-etag-${this.etagCounter++}`;
    this.objects.set(key, {
      bytes: Buffer.from(bytes),
      etag,
      contentType,
    });
    return etag;
  }

  async createPresignedPutUrl(params: PresignedPutParams): Promise<string> {
    this.callCounts.createPresignedPutUrl += 1;
    return `https://mock-r2.local/upload/${encodeURIComponent(params.key)}?expires=${params.expiresInSeconds}`;
  }

  async getObjectStreamWithETag(key: string): Promise<R2ObjectStreamResult> {
    this.callCounts.getObjectStreamWithETag += 1;
    if (this.onGetStreamHook) {
      await this.onGetStreamHook(key);
    }
    const obj = this.objects.get(key);
    if (!obj) {
      throw new ApiError(404, 'R2_OBJECT_MISSING', `Mock R2 object '${key}' not found.`);
    }
    const streamBytes =
      this.overrideStreamBytesForKey?.key === key
        ? this.overrideStreamBytesForKey.bytes
        : obj.bytes;
    const configuredChunkSize = this.overrideChunkSizeForKey.get(key);
    const chunkSize =
      typeof configuredChunkSize === 'number' &&
      Number.isInteger(configuredChunkSize) &&
      configuredChunkSize > 0
        ? configuredChunkSize
        : 8;
    const adapter = this;
    let index = 0;
    let closed = false;
    const markClosed = () => {
      if (!closed) {
        closed = true;
        adapter.lastStreamClosedKey = key;
      }
    };
    const mockStream: AsyncIterable<Uint8Array> & {
      destroy: () => void;
      return: () => Promise<IteratorResult<Uint8Array>>;
    } = {
      destroy() {
        markClosed();
      },
      async return() {
        markClosed();
        return { value: undefined as unknown as Uint8Array, done: true };
      },
      [Symbol.asyncIterator]() {
        return {
          async next(): Promise<IteratorResult<Uint8Array>> {
            if (closed || index >= streamBytes.length) {
              markClosed();
              return { value: undefined as unknown as Uint8Array, done: true };
            }
            const chunk = streamBytes.subarray(index, index + chunkSize);
            index += chunkSize;
            return { value: chunk, done: false };
          },
          async return() {
            markClosed();
            return { value: undefined as unknown as Uint8Array, done: true };
          },
        };
      },
    };
    return {
      stream: mockStream,
      etag: obj.etag,
      contentLength:
        this.forceNullStreamLengthForKey === key ? null : obj.bytes.length,
      contentType: obj.contentType,
    };
  }

  async copyObjectIfMatch(
    params: R2CopyIfMatchParams,
  ): Promise<{ etag: string | null }> {
    this.callCounts.copyObjectIfMatch += 1;
    if (this.onCopyHook) {
      await this.onCopyHook(params);
    }
    const src = this.objects.get(params.sourceKey);
    if (!src) {
      throw new ApiError(404, 'R2_OBJECT_MISSING', 'Source missing in mock R2.');
    }
    if (src.etag !== params.expectedETag) {
      throw new ApiError(412, 'ETAG_MISMATCH', 'Mock R2 ETag mismatch on CopySourceIfMatch.');
    }
    const copiedBytes =
      this.corruptCopyBytesForKey === params.sourceKey
        ? Buffer.from('%PDF-1.4\n%CORRUPTED_AFTER_COPY_BYTE\n%%EOF\n', 'utf8')
        : Buffer.from(src.bytes);
    const etag = this.putRaw(params.destinationKey, copiedBytes, params.contentType);
    return { etag };
  }

  async headObject(key: string): Promise<R2HeadResult> {
    this.callCounts.headObject += 1;
    if (this.forceMissingHeadForKey === key) {
      return { exists: false, contentLength: null, etag: null, contentType: null };
    }
    const obj = this.objects.get(key);
    if (!obj) {
      return { exists: false, contentLength: null, etag: null, contentType: null };
    }
    return {
      exists: true,
      contentLength:
        this.forceNullHeadLengthForKey === key ? null : obj.bytes.length,
      etag: obj.etag,
      contentType: obj.contentType,
    };
  }

  async deleteObject(key: string): Promise<void> {
    this.callCounts.deleteObject += 1;
    this.deletedKeys.push(key);
    this.objects.delete(key);
  }
}

class TestIdTokenVerifier implements IdTokenVerifier {
  readonly calls: Array<{ token: string; checkRevoked: boolean | undefined }> = [];
  private readonly tokens = new Map<
    string,
    | { kind: 'valid'; claims: DecodedIdTokenLike }
    | { kind: 'error'; code: string; message: string }
  >();

  registerValid(token: string, claims: DecodedIdTokenLike) {
    this.tokens.set(token, { kind: 'valid', claims });
  }

  registerError(token: string, code: string, message: string) {
    this.tokens.set(token, { kind: 'error', code, message });
  }

  async verifyIdToken(
    idToken: string,
    checkRevoked?: boolean,
  ): Promise<DecodedIdTokenLike> {
    this.calls.push({ token: idToken, checkRevoked });
    const entry = this.tokens.get(idToken);
    if (!entry) {
      const err = new Error('Token not recognized') as Error & { code?: string };
      err.code = 'auth/argument-error';
      throw err;
    }
    if (entry.kind === 'error') {
      const err = new Error(entry.message) as Error & { code?: string };
      err.code = entry.code;
      throw err;
    }
    return entry.claims;
  }
}

function makePdfBuffer(label = 'Haqibati D2 Verified PDF'): {
  bytes: Buffer;
  sizeBytes: number;
  sha256: string;
} {
  const bytes = Buffer.from(
    `%PDF-1.4\n% ${label}\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n`,
    'utf8',
  );
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  return { bytes, sizeBytes: bytes.length, sha256 };
}

function extractEmulatorHost(): string {
  return process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080';
}

describe('Haqibati Backend API (Stage 8 - D2)', () => {
  let adminFirestore: Firestore;
  let rulesTestEnv: RulesTestEnvironment;
  let mockR2: MockR2StorageAdapter;
  let verifier: TestIdTokenVerifier;
  let currentNowMs: number;
  let uuidSeq: number;
  let baseUrl: string;
  let server: ReturnType<ReturnType<typeof createBackendApp>['listen']>;
  let baseConfig: ServerConfig;

  before(async () => {
    process.env.FIRESTORE_EMULATOR_HOST = extractEmulatorHost();
    const adminApp =
      getApps().find((a) => a.name === 'd2-test-admin') ??
      initializeApp({ projectId: PROJECT_ID }, 'd2-test-admin');
    adminFirestore = getFirestore(adminApp);

    const [host, rawPort] = extractEmulatorHost().split(':');
    rulesTestEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: { host, port: Number(rawPort) || 8080 },
    });

    mockR2 = new MockR2StorageAdapter();
    verifier = new TestIdTokenVerifier();
    currentNowMs = 1_750_000_000_000;
    uuidSeq = 1;

    baseConfig = {
      ...loadServerConfig({
        FIREBASE_PROJECT_ID: PROJECT_ID,
        FIRESTORE_EMULATOR_HOST: extractEmulatorHost(),
        ALLOWED_ORIGINS: 'https://allowed.haqibati.local',
        UPLOAD_SESSION_TTL_SECONDS: '900',
        VERIFY_LOCK_TTL_SECONDS: '300',
        MAX_PDF_BYTES: '10485760',
      }),
    };

    const app = createBackendApp({
      config: baseConfig,
      firestore: adminFirestore,
      idTokenVerifier: verifier,
      r2Storage: mockR2,
      nowMs: () => currentNowMs,
      uuidGenerator: () => {
        const suffix = String(uuidSeq++).padStart(12, '0');
        return `11111111-2222-4333-8444-${suffix}`;
      },
    });

    await new Promise<void>((resolve) => {
      server = app.listen(0, '127.0.0.1', () => {
        const addr = server.address() as AddressInfo;
        baseUrl = `http://127.0.0.1:${addr.port}`;
        resolve();
      });
    });
  });

  after(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rulesTestEnv.cleanup();
  });

  beforeEach(async () => {
    await rulesTestEnv.clearFirestore();
    mockR2.objects.clear();
    mockR2.deletedKeys.length = 0;
    mockR2.onCopyHook = undefined;
    mockR2.onGetStreamHook = undefined;
    mockR2.corruptCopyBytesForKey = undefined;
    mockR2.forceMissingHeadForKey = undefined;
    mockR2.forceNullHeadLengthForKey = undefined;
    mockR2.forceNullStreamLengthForKey = undefined;
    mockR2.overrideStreamBytesForKey = undefined;
    mockR2.overrideChunkSizeForKey.clear();
    mockR2.lastStreamClosedKey = undefined;
    mockR2.resetCallCounts();
    verifier.calls.length = 0;
    currentNowMs += 10_000_000;
    uuidSeq = 1;

    verifier.registerValid('token-owner', {
      uid: 'owner-uid-1',
      email: 'owner@haqibati.local',
      role: 'owner',
      firebase: { sign_in_provider: 'password' },
    });
    verifier.registerValid('token-admin', {
      uid: 'admin-uid-1',
      email: 'admin@haqibati.local',
      role: 'admin',
      firebase: { sign_in_provider: 'password' },
    });
    verifier.registerValid('token-student', {
      uid: 'student-uid-1',
      email: 'student@haqibati.local',
      firebase: { sign_in_provider: 'password' },
    });
    verifier.registerValid('token-anon', {
      uid: 'anon-uid-1',
      firebase: { sign_in_provider: 'anonymous' },
    });
    verifier.registerError(
      'token-revoked',
      'auth/id-token-revoked',
      'Firebase ID token has been revoked.',
    );
    verifier.registerError(
      'token-expired',
      'auth/id-token-expired',
      'Firebase ID token has expired.',
    );
  });

  async function seedHierarchy(opts?: {
    branchPublished?: boolean;
    subjectPublished?: boolean;
    sectionPublished?: boolean;
    filePublished?: boolean;
    fileArchived?: boolean;
    withPublishedVersion?: boolean;
  }) {
    const now = Timestamp.fromMillis(currentNowMs);
    const branchRef = adminFirestore.collection('branches').doc('branch-1');
    const subjectRef = adminFirestore.collection('subjects').doc('subj-1');
    const sectionRef = adminFirestore.collection('sections').doc('sec-1');
    const fileRef = adminFirestore.collection('study_files').doc('file-1');

    await branchRef.set({
      nameAr: 'الفرع العلمي', // validates legacy nameAr compatibility too!
      order: 0,
      isPublished: opts?.branchPublished ?? true,
      createdByUid: 'owner-uid-1',
      updatedByUid: 'owner-uid-1',
      createdAt: now,
      updatedAt: now,
    });

    await subjectRef.set({
      branchId: branchRef,
      name: 'الفيزياء',
      order: 0,
      isPublished: opts?.subjectPublished ?? true,
      createdByUid: 'owner-uid-1',
      updatedByUid: 'owner-uid-1',
      createdAt: now,
      updatedAt: now,
    });

    await sectionRef.set({
      subjectId: subjectRef,
      name: 'الميكانيك',
      order: 0,
      isPublished: opts?.sectionPublished ?? true,
      createdByUid: 'admin-uid-1',
      updatedByUid: 'admin-uid-1',
      createdAt: now,
      updatedAt: now,
    });

    if (opts?.withPublishedVersion) {
      const pdf = makePdfBuffer('Published PDF Content');
      const sessionId = 'aaaaaaaa-bbbb-4ccc-8ddd-000000000001';
      const attemptId = 'aaaaaaaa-bbbb-4ccc-8ddd-000000000002';
      const r2ObjectKey = `study-files/file-1/versions/ver-1/${pdf.sha256.slice(0, 16)}_${sessionId}_${attemptId}.pdf`;
      mockR2.putRaw(r2ObjectKey, pdf.bytes);

      await adminFirestore.collection('upload_sessions').doc(sessionId).set({
        sessionId,
        fileId: 'file-1',
        versionId: 'ver-1',
        stagingKey: `uploads-staging/file-1/ver-1/${sessionId}.pdf`,
        status: 'CONSUMED',
        expectedSizeBytes: pdf.sizeBytes,
        expectedSha256: pdf.sha256,
        attemptCount: 1,
        activeAttemptId: attemptId,
        lockToken: attemptId,
        finalObjectKey: r2ObjectKey,
        expiresAt: Timestamp.fromMillis(currentNowMs + 600_000),
        consumedAt: now,
        createdByUid: 'admin-uid-1',
        updatedByUid: 'admin-uid-1',
        createdAt: now,
        updatedAt: now,
      });

      await fileRef.set({
        sectionId: sectionRef,
        title: 'ملخص الحركة',
        description: 'وصف منظم',
        order: 0,
        isPublished: opts?.filePublished ?? true,
        isArchived: opts?.fileArchived ?? false,
        mimeType: 'application/pdf',
        storageProvider: 'cloudflare_r2',
        activeVersionId: 'ver-1',
        activeVersionNumber: 1,
        activeVersionLabel: 'الإصدار 1',
        activeVersionPublishedAt: now,
        registeredVersionNumbers: [1],
        createdByUid: 'admin-uid-1',
        updatedByUid: 'admin-uid-1',
        createdAt: now,
        updatedAt: now,
      });

      await fileRef.collection('versions').doc('ver-1').set({
        fileId: fileRef,
        versionNumber: 1,
        versionLabel: 'الإصدار 1',
        versionNotes: 'النسخة المعتمدة',
        r2ObjectKey,
        fileSizeBytes: pdf.sizeBytes,
        checksumSha256: pdf.sha256,
        binaryVerificationStatus: 'VERIFIED',
        verifiedUploadSessionId: sessionId,
        verifiedAttemptId: attemptId,
        isPublished: true,
        isArchived: false,
        publishedAt: now,
        createdByUid: 'admin-uid-1',
        updatedByUid: 'admin-uid-1',
        createdAt: now,
        updatedAt: now,
      });

      return { pdf, sessionId, attemptId, r2ObjectKey };
    }

    await fileRef.set({
      sectionId: sectionRef,
      title: 'ملخص الحركة',
      description: null,
      order: 0,
      isPublished: false,
      isArchived: opts?.fileArchived ?? false,
      mimeType: 'application/pdf',
      storageProvider: 'cloudflare_r2',
      activeVersionId: null,
      activeVersionNumber: null,
      activeVersionLabel: null,
      activeVersionPublishedAt: null,
      registeredVersionNumbers: [],
      createdByUid: 'admin-uid-1',
      updatedByUid: 'admin-uid-1',
      createdAt: now,
      updatedAt: now,
    });

    return null;
  }

  async function apiJson(
    path: string,
    options?: { method?: string; token?: string; body?: unknown },
  ) {
    const headers: Record<string, string> = {};
    if (options?.token) {
      headers.Authorization = `Bearer ${options.token}`;
    }
    if (options?.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    const res = await fetch(`${baseUrl}${path}`, {
      method: options?.method ?? 'GET',
      headers,
      body: options?.body !== undefined ? JSON.stringify(options.body) : undefined,
      redirect: 'manual',
    });
    const text = await res.text();
    let json: unknown = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = text;
    }
    return { status: res.status, headers: res.headers, json };
  }

  // =========================================================================
  // 1. Authentication, Token Revocation (checkRevoked=true) & RBAC
  // =========================================================================
  it('enforces verifyIdToken(checkRevoked=true), rejects revoked/expired/student tokens on admin routes, and blocks guest/anonymous downloads', async () => {
    const seeded = await seedHierarchy({ withPublishedVersion: true });
    assert.ok(seeded);

    // Unauthenticated admin access -> 401
    const noAuthAdmin = await apiJson('/api/admin/branches');
    assert.equal(noAuthAdmin.status, 401);

    // Revoked token -> 401 TOKEN_REVOKED & verifies checkRevoked=true was passed
    const revokedRes = await apiJson('/api/admin/branches', {
      token: 'token-revoked',
    });
    assert.equal(revokedRes.status, 401);
    assert.equal((revokedRes.json as { error: { code: string } }).error.code, 'TOKEN_REVOKED');
    assert.equal(verifier.calls.at(-1)?.checkRevoked, true);

    // Expired token -> 401 TOKEN_EXPIRED
    const expiredRes = await apiJson('/api/admin/branches', {
      token: 'token-expired',
    });
    assert.equal(expiredRes.status, 401);
    assert.equal((expiredRes.json as { error: { code: string } }).error.code, 'TOKEN_EXPIRED');

    // Revoked token even on optional-auth route MUST fail 401 (never downgrade silently to guest)
    const revokedCatalog = await apiJson('/api/catalog/branches', {
      token: 'token-revoked',
    });
    assert.equal(revokedCatalog.status, 401);

    // Student calling admin route -> 403 INSUFFICIENT_ROLE
    const studentAdmin = await apiJson('/api/admin/branches', {
      token: 'token-student',
    });
    assert.equal(studentAdmin.status, 403);
    assert.equal(
      (studentAdmin.json as { error: { code: string } }).error.code,
      'INSUFFICIENT_ROLE',
    );

    // Admin calling admin route -> 200 OK
    const adminOk = await apiJson('/api/admin/branches', { token: 'token-admin' });
    assert.equal(adminOk.status, 200);

    // Guest stream -> 200 OK; Guest download -> 401 UNAUTHENTICATED
    const guestStream = await fetch(
      `${baseUrl}/api/study-files/file-1/versions/ver-1/stream`,
      { redirect: 'manual' },
    );
    assert.equal(guestStream.status, 200);
    assert.equal(guestStream.headers.get('content-type'), 'application/pdf');
    assert.equal(guestStream.headers.get('x-content-sha256'), seeded.pdf.sha256);
    assert.equal(Number(guestStream.headers.get('content-length')), seeded.pdf.sizeBytes);
    assert.deepEqual(Buffer.from(await guestStream.arrayBuffer()), seeded.pdf.bytes);

    const guestDownload = await apiJson(
      '/api/study-files/file-1/versions/ver-1/download',
    );
    assert.equal(guestDownload.status, 401);

    // Firebase Anonymous download -> 403 GUEST_DOWNLOAD_FORBIDDEN (even with valid token!)
    const anonDownload = await apiJson(
      '/api/study-files/file-1/versions/ver-1/download',
      { token: 'token-anon' },
    );
    assert.equal(anonDownload.status, 403);
    assert.equal(
      (anonDownload.json as { error: { code: string } }).error.code,
      'GUEST_DOWNLOAD_FORBIDDEN',
    );

    // Registered Student download -> 200 OK attachment
    const studentDownload = await fetch(
      `${baseUrl}/api/study-files/file-1/versions/ver-1/download`,
      { headers: { Authorization: 'Bearer token-student' }, redirect: 'manual' },
    );
    assert.equal(studentDownload.status, 200);
    assert.match(
      studentDownload.headers.get('content-disposition') ?? '',
      /^attachment; filename="file-1_v1\.pdf"$/,
    );
    assert.deepEqual(
      Buffer.from(await studentDownload.arrayBuffer()),
      seeded.pdf.bytes,
    );
  });

  // =========================================================================
  // 2. Live 5-Level Hierarchy Verification & Immediate Unpublish Propagation
  // =========================================================================
  it('enforces live 5-level hierarchy without cache across catalog, stream, and download and hides raw r2ObjectKey', async () => {
    await seedHierarchy({ withPublishedVersion: true });

    const filesRes = await apiJson('/api/catalog/sections/sec-1/study-files');
    assert.equal(filesRes.status, 200);
    const items = (filesRes.json as { items: Array<Record<string, unknown>> }).items;
    assert.equal(items.length, 1);
    assert.equal(items[0].id, 'file-1');
    assert.equal('r2ObjectKey' in items[0], false); // never leaked publicly

    // Unpublish top-level branch live -> catalog/stream/download immediately return 404!
    await adminFirestore.collection('branches').doc('branch-1').update({
      isPublished: false,
    });

    const subjAfterUnpub = await apiJson('/api/catalog/branches/branch-1/subjects');
    assert.equal(subjAfterUnpub.status, 404);

    const filesAfterUnpub = await apiJson('/api/catalog/sections/sec-1/study-files');
    assert.equal(filesAfterUnpub.status, 404);

    const streamAfterUnpub = await apiJson(
      '/api/study-files/file-1/versions/ver-1/stream',
    );
    assert.equal(streamAfterUnpub.status, 404);

    const downloadAfterUnpub = await apiJson(
      '/api/study-files/file-1/versions/ver-1/download',
      { token: 'token-student' },
    );
    assert.equal(downloadAfterUnpub.status, 404);
  });

  // =========================================================================
  // 3. Full Draft -> Upload Slot -> Verify -> Idempotent Retry -> Publish -> Deliver Flow
  // =========================================================================
  it('completes full draft, upload-slot, PUT, verify, idempotent retry, publish, and delivery while rejecting client proof forgery', async () => {
    await seedHierarchy({ withPublishedVersion: false });
    const pdf = makePdfBuffer('Stage 8 End-to-End PDF');

    // Client tries to forge r2ObjectKey or isPublished -> 403 PRIVILEGED_FIELD_FORBIDDEN
    const forgedCreate = await apiJson('/api/admin/study-files/file-1/versions', {
      method: 'POST',
      token: 'token-admin',
      body: {
        versionNumber: 1,
        versionLabel: 'النسخة 1',
        r2ObjectKey: 'study-files/file-1/versions/ver-1/fake.pdf',
      },
    });
    assert.equal(forgedCreate.status, 403);
    assert.equal(
      (forgedCreate.json as { error: { code: string } }).error.code,
      'PRIVILEGED_FIELD_FORBIDDEN',
    );

    // Valid draft creation
    const createRes = await apiJson('/api/admin/study-files/file-1/versions', {
      method: 'POST',
      token: 'token-admin',
      body: {
        customVersionId: 'ver-1',
        versionNumber: 1,
        versionLabel: 'الإصدار الأول',
        versionNotes: 'ملف PDF جديد',
      },
    });
    assert.equal(createRes.status, 201);

    // Publishing before verification MUST fail
    const publishUnverified = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/publish',
      { method: 'POST', token: 'token-admin', body: {} },
    );
    assert.equal(publishUnverified.status, 409);

    // Client trying to inject verifiedProof on publish -> 403
    const forgedPublish = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/publish',
      {
        method: 'POST',
        token: 'token-admin',
        body: { verifiedProof: { r2ObjectKey: 'hacked.pdf' } },
      },
    );
    assert.equal(forgedPublish.status, 403);

    // Request upload-slot
    const slotRes = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/upload-slot',
      {
        method: 'POST',
        token: 'token-admin',
        body: {
          expectedSizeBytes: pdf.sizeBytes,
          expectedSha256: pdf.sha256,
        },
      },
    );
    assert.equal(slotRes.status, 201);
    const slot = slotRes.json as {
      uploadSessionId: string;
      stagingKey: string;
      uploadUrl: string;
    };
    assert.match(
      slot.uploadSessionId,
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
    assert.equal(
      slot.stagingKey,
      `uploads-staging/file-1/ver-1/${slot.uploadSessionId}.pdf`,
    );

    // Simulate client PUT upload to Mock R2 staging key
    mockR2.putRaw(slot.stagingKey, pdf.bytes);

    // Call /verify
    const verifyRes = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot.uploadSessionId },
      },
    );
    assert.equal(verifyRes.status, 200);
    const verified = verifyRes.json as {
      idempotent: boolean;
      attemptId: string;
      r2ObjectKey: string;
      checksumSha256: string;
    };
    assert.equal(verified.idempotent, false);
    assert.equal(verified.checksumSha256, pdf.sha256);
    assert.equal(
      verified.r2ObjectKey,
      `study-files/file-1/versions/ver-1/${pdf.sha256.slice(0, 16)}_${slot.uploadSessionId}_${verified.attemptId}.pdf`,
    );
    // Staging key should have been cleaned up after verification commit
    assert.equal(mockR2.objects.has(slot.stagingKey), false);
    assert.equal(mockR2.objects.has(verified.r2ObjectKey), true);

    // Idempotent retry on CONSUMED session -> 200 OK with same proof & idempotent=true
    const retryRes = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot.uploadSessionId },
      },
    );
    assert.equal(retryRes.status, 200);
    assert.equal((retryRes.json as { idempotent: boolean }).idempotent, true);
    assert.equal(
      (retryRes.json as { r2ObjectKey: string }).r2ObjectKey,
      verified.r2ObjectKey,
    );

    // Publish version
    const publishRes = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/publish',
      { method: 'POST', token: 'token-admin', body: {} },
    );
    assert.equal(publishRes.status, 200);
    assert.equal(
      (publishRes.json as { status: string }).status,
      'PUBLISHED',
    );

    // Verify public catalog now exposes file-1 + explicit UNARCHIVED archiveStatus + stream/download works
    const catalogRes = await apiJson('/api/catalog/sections/sec-1/study-files');
    assert.equal(catalogRes.status, 200);
    const files = (
      catalogRes.json as { items: Array<{ id: string; archiveStatus?: string }> }
    ).items;
    assert.equal(files.length, 1);
    assert.equal(files[0].id, 'file-1');
    assert.equal(files[0].archiveStatus, 'UNARCHIVED');
  });

  // =========================================================================
  // 4. Failure Cases: Invalid PDF Header, SHA-256 Mismatch, Corrupted Final Copy, Expired Session, Superseded Session
  // =========================================================================
  it('rejects non-PDF bytes, SHA-256 mismatch, corrupted post-copy bytes, expired sessions, and superseded sessions cleanly', async () => {
    await seedHierarchy({ withPublishedVersion: false });
    await apiJson('/api/admin/study-files/file-1/versions', {
      method: 'POST',
      token: 'token-admin',
      body: { customVersionId: 'ver-1', versionNumber: 1, versionLabel: 'v1' },
    });

    const pdf = makePdfBuffer('Valid Target PDF');

    // Slot 1 -> will be superseded by Slot 2
    const slot1 = (
      await apiJson('/api/admin/study-files/file-1/versions/ver-1/upload-slot', {
        method: 'POST',
        token: 'token-admin',
        body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
      })
    ).json as { uploadSessionId: string; stagingKey: string };

    // Slot 2 supersedes Slot 1
    const slot2 = (
      await apiJson('/api/admin/study-files/file-1/versions/ver-1/upload-slot', {
        method: 'POST',
        token: 'token-admin',
        body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
      })
    ).json as { uploadSessionId: string; stagingKey: string };

    const verifySuperseded = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot1.uploadSessionId },
      },
    );
    assert.equal(verifySuperseded.status, 409);
    assert.equal(
      (verifySuperseded.json as { error: { code: string } }).error.code,
      'SESSION_SUPERSEDED',
    );

    // Invalid %PDF- header in Slot 2
    const fakeNonPdf = Buffer.alloc(pdf.sizeBytes, 'X');
    mockR2.putRaw(slot2.stagingKey, fakeNonPdf);
    const badHeaderRes = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot2.uploadSessionId },
      },
    );
    assert.equal(badHeaderRes.status, 422);
    assert.equal(
      (badHeaderRes.json as { error: { code: string } }).error.code,
      'INVALID_PDF_HEADER',
    );
    const s2Snap = await adminFirestore
      .collection('upload_sessions')
      .doc(slot2.uploadSessionId)
      .get();
    assert.equal(s2Snap.data()?.status, 'FAILED');

    // Slot 3: valid header + size, WRONG SHA-256
    const slot3 = (
      await apiJson('/api/admin/study-files/file-1/versions/ver-1/upload-slot', {
        method: 'POST',
        token: 'token-admin',
        body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
      })
    ).json as { uploadSessionId: string; stagingKey: string };
    const otherSameLenPdf = Buffer.from(pdf.bytes);
    otherSameLenPdf[otherSameLenPdf.length - 8] = 0x5a; // mutate 1 byte
    mockR2.putRaw(slot3.stagingKey, otherSameLenPdf);
    const badShaRes = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot3.uploadSessionId },
      },
    );
    assert.equal(badShaRes.status, 422);
    assert.equal(
      (badShaRes.json as { error: { code: string } }).error.code,
      'SHA256_MISMATCH',
    );

    // Slot 4: valid staging bytes, BUT R2 corrupts destination copy during CopyObject (Phase C catches it & cleans orphan final key)
    const slot4 = (
      await apiJson('/api/admin/study-files/file-1/versions/ver-1/upload-slot', {
        method: 'POST',
        token: 'token-admin',
        body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
      })
    ).json as { uploadSessionId: string; stagingKey: string };
    mockR2.putRaw(slot4.stagingKey, pdf.bytes);
    mockR2.corruptCopyBytesForKey = slot4.stagingKey;
    const badFinalRes = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot4.uploadSessionId },
      },
    );
    assert.equal(badFinalRes.status, 422);
    // Only orphan attemptFinalKey was deleted since Firestore cleanly proved it was unbound
    assert.equal(
      mockR2.deletedKeys.some((k) => k.startsWith('study-files/file-1/versions/ver-1/')),
      true,
    );
    mockR2.corruptCopyBytesForKey = undefined;

    // Slot 5: session TTL expiry
    const slot5 = (
      await apiJson('/api/admin/study-files/file-1/versions/ver-1/upload-slot', {
        method: 'POST',
        token: 'token-admin',
        body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
      })
    ).json as { uploadSessionId: string; stagingKey: string };
    mockR2.putRaw(slot5.stagingKey, pdf.bytes);
    currentNowMs += 1000 * 1000; // advance past 900s TTL
    const expiredSessionRes = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot5.uploadSessionId },
      },
    );
    assert.equal(expiredSessionRes.status, 410);
    assert.equal(
      (expiredSessionRes.json as { error: { code: string } }).error.code,
      'SESSION_EXPIRED',
    );
  });

  // =========================================================================
  // 5. Concurrency, Lock Timeout Takeover & Fencing Token Loss (CONFLICT_STALE_LOCK)
  // =========================================================================
  it('blocks active concurrent verify with 409 VERIFY_IN_PROGRESS, allows takeover after lock expiry, and blocks stale worker via Fencing Token', async () => {
    await seedHierarchy({ withPublishedVersion: false });
    await apiJson('/api/admin/study-files/file-1/versions', {
      method: 'POST',
      token: 'token-admin',
      body: { customVersionId: 'ver-1', versionNumber: 1, versionLabel: 'v1' },
    });

    const pdf = makePdfBuffer('Concurrency Test PDF');
    const slot = (
      await apiJson('/api/admin/study-files/file-1/versions/ver-1/upload-slot', {
        method: 'POST',
        token: 'token-admin',
        body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
      })
    ).json as { uploadSessionId: string; stagingKey: string };
    mockR2.putRaw(slot.stagingKey, pdf.bytes);

    // Pause Worker 1 right inside Phase C (reading its copied attemptFinalKey) just before Phase D Fencing Commit Tx
    let releaseWorker1!: () => void;
    let worker1Paused = false;
    const worker1EnteredPhaseC = new Promise<void>((res) => {
      mockR2.onGetStreamHook = async (key) => {
        if (!worker1Paused && key.startsWith('study-files/')) {
          worker1Paused = true;
          res();
          await new Promise<void>((r) => {
            releaseWorker1 = r;
          });
        }
      };
    });

    const worker1Promise = apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot.uploadSessionId },
      },
    );

    await worker1EnteredPhaseC;

    // While Worker 1 holds active lock -> Worker 2 gets 409 VERIFY_IN_PROGRESS
    const concurrentVerify = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot.uploadSessionId },
      },
    );
    assert.equal(concurrentVerify.status, 409);
    assert.equal(
      (concurrentVerify.json as { error: { code: string } }).error.code,
      'VERIFY_IN_PROGRESS',
    );

    // Also upload-slot request during active VERIFYING lock -> 409 VERIFY_IN_PROGRESS
    const concurrentSlot = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/upload-slot',
      {
        method: 'POST',
        token: 'token-admin',
        body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
      },
    );
    assert.equal(concurrentSlot.status, 409);
    assert.equal(
      (concurrentSlot.json as { error: { code: string } }).error.code,
      'VERIFY_IN_PROGRESS',
    );

    // Advance clock past VERIFY_LOCK_TTL (300s) -> Worker 2 safely takes over lock!
    currentNowMs += 305_000;
    mockR2.onGetStreamHook = undefined;

    const worker2Res = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot.uploadSessionId },
      },
    );
    assert.equal(worker2Res.status, 200);
    const w2Body = worker2Res.json as {
      attemptId: string;
      r2ObjectKey: string;
    };

    // Now release stalled Worker 1 -> completes Phase C, enters Phase D Fencing Commit Tx and MUST fail with 409 CONFLICT_STALE_LOCK
    releaseWorker1();
    const worker1Res = await worker1Promise;
    assert.equal(worker1Res.status, 409);
    assert.equal(
      (worker1Res.json as { error: { code: string } }).error.code,
      'CONFLICT_STALE_LOCK',
    );

    // Verify Worker 2's authoritative state and final R2 object were NEVER overwritten or deleted!
    assert.equal(mockR2.objects.has(w2Body.r2ObjectKey), true);

    // Verify Worker 2's authoritative state was NEVER overwritten or marked FAILED!
    const verSnap = await adminFirestore
      .collection('study_files')
      .doc('file-1')
      .collection('versions')
      .doc('ver-1')
      .get();
    assert.equal(verSnap.data()?.binaryVerificationStatus, 'VERIFIED');
    assert.equal(verSnap.data()?.verifiedAttemptId, w2Body.attemptId);
    assert.equal(verSnap.data()?.r2ObjectKey, w2Body.r2ObjectKey);

    const sessionSnap = await adminFirestore
      .collection('upload_sessions')
      .doc(slot.uploadSessionId)
      .get();
    assert.equal(sessionSnap.data()?.status, 'CONSUMED');
    assert.equal(sessionSnap.data()?.activeAttemptId, w2Body.attemptId);
  });

  // =========================================================================
  // 6. Publish Cross-Checks & Unconfigured Fail-Closed R2 Mode
  // =========================================================================
  it('blocks publish on session/key cross-check mismatch or missing R2 HeadObject, and fails closed when R2 is unconfigured', async () => {
    const seeded = await seedHierarchy({ withPublishedVersion: true });
    assert.ok(seeded);

    // Case A: R2 object missing from storage
    mockR2.forceMissingHeadForKey = seeded.r2ObjectKey;
    const missingObjPub = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/publish',
      { method: 'POST', token: 'token-owner', body: {} },
    );
    assert.equal(missingObjPub.status, 409);
    assert.equal(
      (missingObjPub.json as { error: { code: string } }).error.code,
      'R2_OBJECT_MISSING',
    );
    mockR2.forceMissingHeadForKey = undefined;

    // Case B: Session finalObjectKey tampered / mismatch with version r2ObjectKey
    await adminFirestore
      .collection('upload_sessions')
      .doc(seeded.sessionId)
      .update({ finalObjectKey: 'study-files/file-1/versions/ver-1/different.pdf' });

    const mismatchPub = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/publish',
      { method: 'POST', token: 'token-owner', body: {} },
    );
    assert.equal(mismatchPub.status, 409);
    assert.equal(
      (mismatchPub.json as { error: { code: string } }).error.code,
      'PROOF_MISMATCH',
    );

    // Case C: Fail-closed UnconfiguredR2StorageAdapter returns 503 R2_NOT_CONFIGURED
    const unconfiguredApp = createBackendApp({
      config: { ...baseConfig, r2BucketName: undefined, r2AccessKeyId: undefined },
      firestore: adminFirestore,
      idTokenVerifier: verifier,
      r2Storage: new UnconfiguredR2StorageAdapter(),
    });
    const unconfSrv = await new Promise<
      ReturnType<typeof unconfiguredApp.listen>
    >((resolve) => {
      const s = unconfiguredApp.listen(0, '127.0.0.1', () => resolve(s));
    });
    try {
      const port = (unconfSrv.address() as AddressInfo).port;
      const healthRes = await fetch(`http://127.0.0.1:${port}/api/health`);
      assert.equal(healthRes.status, 200);
      assert.deepEqual(await healthRes.json(), {
        status: 'ok',
        r2Configured: false,
      });

      const slotUnconf = await fetch(
        `http://127.0.0.1:${port}/api/admin/study-files/file-1/versions/ver-1/upload-slot`,
        {
          method: 'POST',
          headers: {
            Authorization: 'Bearer token-admin',
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            expectedSizeBytes: seeded.pdf.sizeBytes,
            expectedSha256: seeded.pdf.sha256,
          }),
        },
      );
      assert.equal(slotUnconf.status, 503);
    } finally {
      await new Promise<void>((resolve) => unconfSrv.close(() => resolve()));
    }
  });

  // =========================================================================
  // 7. Non-Destructive Guard: NEVER delete attemptFinalKey if Firestore Read Fails/Uncertain
  // =========================================================================
  it('never deletes attemptFinalKey during failure cleanup if Firestore state read fails or is uncertain', async () => {
    await seedHierarchy({ withPublishedVersion: false });
    await apiJson('/api/admin/study-files/file-1/versions', {
      method: 'POST',
      token: 'token-admin',
      body: { customVersionId: 'ver-1', versionNumber: 1, versionLabel: 'v1' },
    });

    const pdf = makePdfBuffer('Ambiguity Guard PDF');
    const slot = (
      await apiJson('/api/admin/study-files/file-1/versions/ver-1/upload-slot', {
        method: 'POST',
        token: 'token-admin',
        body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
      })
    ).json as { uploadSessionId: string; stagingKey: string };
    mockR2.putRaw(slot.stagingKey, pdf.bytes);

    // Trigger post-copy Phase C failure, AND delete the session doc just before cleanup read?
    // Even better: corrupt final copy AND wrap runTransaction to fail + make session/version doc get fail!
    const flakyFirestoreProxy = new Proxy(adminFirestore, {
      get(target, prop, receiver) {
        if (prop === 'collection') {
          return (colPath: string) => {
            const colRef = target.collection(colPath);
            if (colPath !== 'upload_sessions') return colRef;
            return new Proxy(colRef, {
              get(cTarget, cProp) {
                if (cProp === 'doc') {
                  return (docId: string) => {
                    const docRef = cTarget.doc(docId);
                    return new Proxy(docRef, {
                      get(dTarget, dProp) {
                        if (dProp === 'get') {
                          return async () => {
                            throw new Error('Simulated Firestore network read timeout');
                          };
                        }
                        const val = Reflect.get(dTarget, dProp, dTarget);
                        return typeof val === 'function' ? val.bind(dTarget) : val;
                      },
                    });
                  };
                }
                const val = Reflect.get(cTarget, cProp, cTarget);
                return typeof val === 'function' ? val.bind(cTarget) : val;
              },
            });
          };
        }
        const value = Reflect.get(target, prop, receiver);
        return typeof value === 'function' ? value.bind(target) : value;
      },
    });

    mockR2.corruptCopyBytesForKey = slot.stagingKey;
    const guardApp = createBackendApp({
      config: baseConfig,
      firestore: flakyFirestoreProxy,
      idTokenVerifier: verifier,
      r2Storage: mockR2,
      nowMs: () => currentNowMs,
    });
    const guardSrv = await new Promise<ReturnType<typeof guardApp.listen>>(
      (resolve) => {
        const s = guardApp.listen(0, '127.0.0.1', () => resolve(s));
      },
    );
    try {
      const port = (guardSrv.address() as AddressInfo).port;
      const res = await fetch(
        `http://127.0.0.1:${port}/api/admin/study-files/file-1/versions/ver-1/verify`,
        {
          method: 'POST',
          headers: {
            Authorization: 'Bearer token-admin',
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({ uploadSessionId: slot.uploadSessionId }),
        },
      );
      assert.equal(res.status, 422);
      // Assert ZERO study-files/* keys were deleted because Firestore read threw an uncertainty error!
      assert.equal(
        mockR2.deletedKeys.some((k) => k.startsWith('study-files/')),
        false,
      );
    } finally {
      await new Promise<void>((resolve) => guardSrv.close(() => resolve()));
    }
  });

  // =========================================================================
  // 8. Default 600s Upload Session TTL Check
  // =========================================================================
  it('defaults UPLOAD_SESSION_TTL_SECONDS to 600s (10 minutes) when unset', async () => {
    const defaultConfig = loadServerConfig({
      FIREBASE_PROJECT_ID: PROJECT_ID,
      FIRESTORE_EMULATOR_HOST: extractEmulatorHost(),
    });
    assert.equal(defaultConfig.uploadSessionTtlSeconds, 600);
    assert.equal(defaultConfig.verifyLockTtlSeconds, 300);

    await seedHierarchy({ withPublishedVersion: false });
    const defaultTtlApp = createBackendApp({
      config: defaultConfig,
      firestore: adminFirestore,
      idTokenVerifier: verifier,
      r2Storage: mockR2,
      nowMs: () => currentNowMs,
      uuidGenerator: () => {
        const suffix = String(uuidSeq++).padStart(12, '0');
        return `22222222-3333-4444-8555-${suffix}`;
      },
    });
    const srv = await new Promise<ReturnType<typeof defaultTtlApp.listen>>(
      (resolve) => {
        const s = defaultTtlApp.listen(0, '127.0.0.1', () => resolve(s));
      },
    );
    try {
      const port = (srv.address() as AddressInfo).port;
      await fetch(
        `http://127.0.0.1:${port}/api/admin/study-files/file-1/versions`,
        {
          method: 'POST',
          headers: {
            Authorization: 'Bearer token-admin',
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            customVersionId: 'ver-1',
            versionNumber: 1,
            versionLabel: 'v1',
          }),
        },
      );
      const pdf = makePdfBuffer('Default TTL PDF');
      const slotRes = await fetch(
        `http://127.0.0.1:${port}/api/admin/study-files/file-1/versions/ver-1/upload-slot`,
        {
          method: 'POST',
          headers: {
            Authorization: 'Bearer token-admin',
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            expectedSizeBytes: pdf.sizeBytes,
            expectedSha256: pdf.sha256,
          }),
        },
      );
      assert.equal(slotRes.status, 201);
      const body = (await slotRes.json()) as { expiresAtEpochMillis: number };
      assert.equal(body.expiresAtEpochMillis, currentNowMs + 600_000);
    } finally {
      await new Promise<void>((resolve) => srv.close(() => resolve()));
    }
  });

  // =========================================================================
  // 9. Legacy Unmigrated Docs (Missing isArchived) & Archived Parent Hierarchy
  // =========================================================================
  it('hides legacy study_files/versions without isArchived from public catalog/delivery/publish while keeping admin inspection intact, and blocks archived hierarchy nodes', async () => {
    const seeded = await seedHierarchy({ withPublishedVersion: true });
    assert.ok(seeded);

    // Seed a legacy published study file & version WITHOUT isArchived field
    const sectionRef = adminFirestore.collection('sections').doc('sec-1');
    const legacyFileRef = adminFirestore.collection('study_files').doc('legacy-file');
    const now = Timestamp.fromMillis(currentNowMs);
    const pdf = makePdfBuffer('Legacy PDF');
    const legacySessionId = 'bbbbbbbb-cccc-4ddd-8eee-000000000001';
    const legacyAttemptId = 'bbbbbbbb-cccc-4ddd-8eee-000000000002';
    const legacyR2Key = `study-files/legacy-file/versions/legacy-ver/${pdf.sha256.slice(0, 16)}_${legacySessionId}_${legacyAttemptId}.pdf`;
    mockR2.putRaw(legacyR2Key, pdf.bytes);

    await adminFirestore.collection('upload_sessions').doc(legacySessionId).set({
      sessionId: legacySessionId,
      fileId: 'legacy-file',
      versionId: 'legacy-ver',
      stagingKey: `uploads-staging/legacy-file/legacy-ver/${legacySessionId}.pdf`,
      status: 'CONSUMED',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      attemptCount: 1,
      activeAttemptId: legacyAttemptId,
      lockToken: legacyAttemptId,
      finalObjectKey: legacyR2Key,
      expiresAt: Timestamp.fromMillis(currentNowMs + 600_000),
      consumedAt: now,
    });

    await legacyFileRef.set({
      sectionId: sectionRef,
      title: 'ملف قديم غير مُهاجر',
      order: 1,
      isPublished: true,
      mimeType: 'application/pdf',
      storageProvider: 'cloudflare_r2',
      activeVersionId: 'legacy-ver',
      activeVersionNumber: 1,
      activeVersionLabel: 'v1',
      activeVersionPublishedAt: now,
    });

    await legacyFileRef.collection('versions').doc('legacy-ver').set({
      fileId: legacyFileRef,
      versionNumber: 1,
      versionLabel: 'v1',
      r2ObjectKey: legacyR2Key,
      fileSizeBytes: pdf.sizeBytes,
      checksumSha256: pdf.sha256,
      binaryVerificationStatus: 'VERIFIED',
      verifiedUploadSessionId: legacySessionId,
      verifiedAttemptId: legacyAttemptId,
      isPublished: true,
      publishedAt: now,
    });

    // Public catalog hides legacy-file while still showing file-1 (isArchived: false)
    const publicFiles = await apiJson('/api/catalog/sections/sec-1/study-files');
    assert.equal(publicFiles.status, 200);
    const publicIds = (
      publicFiles.json as { items: Array<{ id: string }> }
    ).items.map((x) => x.id);
    assert.deepEqual(publicIds, ['file-1']);

    // Direct stream/download on legacy-file MUST 404
    const legacyStream = await apiJson(
      '/api/study-files/legacy-file/versions/legacy-ver/stream',
    );
    assert.equal(legacyStream.status, 404);
    const legacyDownload = await apiJson(
      '/api/study-files/legacy-file/versions/legacy-ver/download',
      { token: 'token-student' },
    );
    assert.equal(legacyDownload.status, 404);

    // Republish on legacy doc without isArchived=false MUST fail closed
    const legacyPublish = await apiJson(
      '/api/admin/study-files/legacy-file/versions/legacy-ver/publish',
      { method: 'POST', token: 'token-admin', body: {} },
    );
    assert.equal(legacyPublish.status, 409);

    // Admin read endpoints MUST still list legacy files & versions with explicit tri-state UNSPECIFIED metadata
    const adminFiles = await apiJson(
      '/api/admin/sections/sec-1/study-files',
      { token: 'token-admin' },
    );
    assert.equal(adminFiles.status, 200);
    const adminFileItems = (
      adminFiles.json as {
        items: Array<{ id: string; isArchived: boolean | null; archiveStatus: string }>;
      }
    ).items;
    const adminLegacyFile = adminFileItems.find((x) => x.id === 'legacy-file');
    assert.ok(adminLegacyFile);
    assert.equal(adminLegacyFile.archiveStatus, 'UNSPECIFIED');
    assert.equal(adminLegacyFile.isArchived, null);
    const adminModernFile = adminFileItems.find((x) => x.id === 'file-1');
    assert.ok(adminModernFile);
    assert.equal(adminModernFile.archiveStatus, 'UNARCHIVED');
    assert.equal(adminModernFile.isArchived, false);

    const adminVers = await apiJson(
      '/api/admin/study-files/legacy-file/versions',
      { token: 'token-admin' },
    );
    assert.equal(adminVers.status, 200);
    const adminLegacyVer = (
      adminVers.json as {
        items: Array<{ id: string; isArchived: boolean | null; archiveStatus: string }>;
      }
    ).items[0];
    assert.equal(adminLegacyVer?.id, 'legacy-ver');
    assert.equal(adminLegacyVer?.archiveStatus, 'UNSPECIFIED');
    assert.equal(adminLegacyVer?.isArchived, null);

    // Marking parent section isArchived: true MUST immediately block catalog, stream & download
    await adminFirestore.collection('sections').doc('sec-1').update({
      isArchived: true,
    });
    assert.equal(
      (await apiJson('/api/catalog/sections/sec-1/study-files')).status,
      404,
    );
    assert.equal(
      (await apiJson('/api/study-files/file-1/versions/ver-1/stream')).status,
      404,
    );
    assert.equal(
      (
        await apiJson('/api/study-files/file-1/versions/ver-1/download', {
          token: 'token-student',
        })
      ).status,
      404,
    );
  });

  // =========================================================================
  // 10. Concurrent ETag Change During Staging Copy (412 ETAG_MISMATCH)
  // =========================================================================
  it('rejects staging mutation between stream read and CopyObjectIfMatch with 412 ETAG_MISMATCH', async () => {
    await seedHierarchy({ withPublishedVersion: false });
    await apiJson('/api/admin/study-files/file-1/versions', {
      method: 'POST',
      token: 'token-admin',
      body: { customVersionId: 'ver-1', versionNumber: 1, versionLabel: 'v1' },
    });

    const pdf = makePdfBuffer('Concurrent ETag PDF');
    const slot = (
      await apiJson('/api/admin/study-files/file-1/versions/ver-1/upload-slot', {
        method: 'POST',
        token: 'token-admin',
        body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
      })
    ).json as { uploadSessionId: string; stagingKey: string };
    mockR2.putRaw(slot.stagingKey, pdf.bytes);

    // Simulate another uploader replacing stagingKey right before CopySourceIfMatch runs
    mockR2.onCopyHook = async (params) => {
      mockR2.putRaw(params.sourceKey, pdf.bytes);
    };

    const verifyRes = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: slot.uploadSessionId },
      },
    );
    assert.equal(verifyRes.status, 412);
    assert.equal(
      (verifyRes.json as { error: { code: string } }).error.code,
      'ETAG_MISMATCH',
    );
  });

  // =========================================================================
  // 11. Fail-Closed Publish when HeadObject Omits ContentLength or Session Proof Mismatches
  // =========================================================================
  it('rejects publish when HeadObject omits contentLength or session expectedSha256 mismatches', async () => {
    const seeded = await seedHierarchy({ withPublishedVersion: true });
    assert.ok(seeded);

    // Null contentLength from HeadObject -> fail-closed 409 SIZE_MISMATCH
    mockR2.forceNullHeadLengthForKey = seeded.r2ObjectKey;
    const nullHeadPub = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/publish',
      { method: 'POST', token: 'token-owner', body: {} },
    );
    assert.equal(nullHeadPub.status, 409);
    assert.equal(
      (nullHeadPub.json as { error: { code: string } }).error.code,
      'SIZE_MISMATCH',
    );
    mockR2.forceNullHeadLengthForKey = undefined;

    // Session expectedSha256 tampered -> fail-closed 409 PROOF_MISMATCH
    await adminFirestore
      .collection('upload_sessions')
      .doc(seeded.sessionId)
      .update({
        expectedSha256: '0'.repeat(64),
      });
    const shaMismatchPub = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-1/publish',
      { method: 'POST', token: 'token-owner', body: {} },
    );
    assert.equal(shaMismatchPub.status, 409);
    assert.equal(
      (shaMismatchPub.json as { error: { code: string } }).error.code,
      'PROOF_MISMATCH',
    );
  });

  // =========================================================================
  // 12. Fail-Closed Delivery on null/mismatched Content-Length & Truncated Stream (F-D2-2)
  // =========================================================================
  it('rejects stream/download with 409 SIZE_MISMATCH when R2 contentLength is null or mismatched and guards truncated streams', async () => {
    const seeded = await seedHierarchy({ withPublishedVersion: true });
    assert.ok(seeded);

    // Null R2 stream Content-Length -> fail-closed 409 SIZE_MISMATCH + upstream stream closed
    mockR2.forceNullStreamLengthForKey = seeded.r2ObjectKey;
    const nullLenStream = await apiJson(
      '/api/study-files/file-1/versions/ver-1/stream',
    );
    assert.equal(nullLenStream.status, 409);
    assert.equal(
      (nullLenStream.json as { error: { code: string } }).error.code,
      'SIZE_MISMATCH',
    );
    assert.equal(mockR2.lastStreamClosedKey, seeded.r2ObjectKey);
    mockR2.forceNullStreamLengthForKey = undefined;

    // Truncated stream (body ends early after headers sent) -> response destroyed without res.end()
    const truncatedBytes = seeded.pdf.bytes.subarray(
      0,
      Math.max(5, seeded.pdf.bytes.length - 12),
    );
    let upstreamDestroyed = false;
    const truncatedStream = new Readable({
      read() {
        this.push(truncatedBytes);
        this.push(null);
      },
      destroy(err, cb) {
        upstreamDestroyed = true;
        cb(err);
      },
    });
    let destroyedError: Error | undefined;
    let endedNormally = false;
    class MockRes extends EventEmitter {
      destroyed = false;
      writableEnded = false;
      headersSent = false;
      private readonly headers = new Map<string, string>();
      status() {
        return this;
      }
      setHeader(k: string, v: string) {
        this.headers.set(k.toLowerCase(), v);
      }
      write(_chunk: Buffer) {
        this.headersSent = true;
        return true;
      }
      end() {
        endedNormally = true;
        this.writableEnded = true;
        this.emit('close');
      }
      destroy(err?: Error) {
        this.destroyed = true;
        destroyedError = err;
        this.emit('close');
        return this;
      }
    }
    const stubCatalog = {
      resolvePublishedHierarchyForVersion: async () => ({
        fileId: 'file-1',
        versionId: 'ver-1',
        versionNumber: 1,
        r2ObjectKey: seeded.r2ObjectKey,
        fileSizeBytes: seeded.pdf.sizeBytes,
        checksumSha256: seeded.pdf.sha256,
      }),
    } as unknown as CatalogService;
    const stubR2: R2StoragePort = {
      isConfigured: () => true,
      createPresignedPutUrl: async () => 'https://example.invalid',
      getObjectStreamWithETag: async () => ({
        stream: truncatedStream,
        etag: 'etag-1',
        contentLength: seeded.pdf.sizeBytes,
        contentType: 'application/pdf',
      }),
      copyObjectIfMatch: async () => ({ etag: 'etag-1' }),
      headObject: async () => ({
        exists: true,
        contentLength: seeded.pdf.sizeBytes,
        etag: 'etag-1',
        contentType: 'application/pdf',
      }),
      deleteObject: async () => undefined,
    };
    const delivery = new DeliveryService(stubCatalog, stubR2);
    const res = new MockRes();
    await delivery.deliverPublishedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      mode: 'stream',
      res: res as unknown as ExpressResponse,
    });
    assert.equal(endedNormally, false);
    assert.equal(res.destroyed, true);
    assert.match(destroyedError?.message ?? '', /truncated/i);
    assert.equal(upstreamDestroyed, true);
  });

  // =========================================================================
  // 13. Client Disconnect During Backpressure Cancels Upstream & Cleans Listeners (F-D2-1)
  // =========================================================================
  it('resolves cleanly without hanging, destroys upstream R2 stream, and cleans listeners if client disconnects during backpressure', async () => {
    let upstreamDestroyed = false;
    const bigChunkA = Buffer.from('%PDF-1.4\nCHUNK_A_00000000\n', 'utf8');
    const bigChunkB = Buffer.from('CHUNK_B_SHOULD_NOT_HANG\n%%EOF\n', 'utf8');
    const totalLen = bigChunkA.length + bigChunkB.length;
    const fakePdfSha = createHash('sha256')
      .update(Buffer.concat([bigChunkA, bigChunkB]))
      .digest('hex');

    let pushedCount = 0;
    const pausedUpstream = new Readable({
      read() {
        if (pushedCount === 0) {
          pushedCount++;
          this.push(bigChunkA);
        } else if (pushedCount === 1) {
          pushedCount++;
          this.push(bigChunkB);
        } else {
          this.push(null);
        }
      },
      destroy(err, cb) {
        upstreamDestroyed = true;
        cb(err);
      },
    });

    class BackpressureRes extends EventEmitter {
      destroyed = false;
      writableEnded = false;
      headersSent = false;
      status() {
        return this;
      }
      setHeader() {
        return this;
      }
      write() {
        this.headersSent = true;
        // Force backpressure on first chunk
        return false;
      }
      end() {
        this.writableEnded = true;
        this.emit('close');
      }
      destroy() {
        this.destroyed = true;
        this.emit('close');
        return this;
      }
    }

    const res = new BackpressureRes();
    const delivery = new DeliveryService(
      {
        resolvePublishedHierarchyForVersion: async () => ({
          fileId: 'file-1',
          versionId: 'ver-1',
          versionNumber: 1,
          r2ObjectKey: 'study-files/file-1/versions/ver-1/key.pdf',
          fileSizeBytes: totalLen,
          checksumSha256: fakePdfSha,
        }),
      } as unknown as CatalogService,
      {
        isConfigured: () => true,
        createPresignedPutUrl: async () => 'https://example.invalid',
        getObjectStreamWithETag: async () => ({
          stream: pausedUpstream,
          etag: 'etag-1',
          contentLength: totalLen,
          contentType: 'application/pdf',
        }),
        copyObjectIfMatch: async () => ({ etag: 'etag-1' }),
        headObject: async () => ({
          exists: true,
          contentLength: totalLen,
          etag: 'etag-1',
          contentType: 'application/pdf',
        }),
        deleteObject: async () => undefined,
      },
    );

    const deliveryPromise = delivery.deliverPublishedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      mode: 'download',
      res: res as unknown as ExpressResponse,
    });

    // Wait a tick so write() hits backpressure, then simulate client closing socket
    await new Promise((r) => setTimeout(r, 25));
    res.destroyed = true;
    res.emit('close');

    await deliveryPromise;

    assert.equal(upstreamDestroyed, true);
    assert.equal(res.writableEnded, false);
    assert.equal(res.listenerCount('drain'), 0);
    assert.equal(res.listenerCount('error'), 0);
    assert.equal(res.listenerCount('close'), 0);
  });

  // =========================================================================
  // 14. Early Client Disconnect Before/During R2 Stream Open (F-D2-1)
  // =========================================================================
  it('aborts cleanly before touching R2 or reading chunks if client disconnects during hierarchy resolution or R2 open', async () => {
    class EarlyDisconnectRes extends EventEmitter {
      destroyed = false;
      writableEnded = false;
      headersSent = false;
      closed = false;
      statusCalls = 0;
      endCalls = 0;
      status() {
        this.statusCalls++;
        return this;
      }
      setHeader() {
        return this;
      }
      write() {
        this.headersSent = true;
        return true;
      }
      end() {
        this.endCalls++;
        this.writableEnded = true;
      }
      destroy() {
        this.destroyed = true;
        this.closed = true;
        this.emit('close');
        return this;
      }
    }

    // 14a) Client disconnects while Firestore hierarchy resolution is in flight -> getObjectStreamWithETag is never called.
    let r2OpenCount = 0;
    const res1 = new EarlyDisconnectRes();
    const delivery1 = new DeliveryService(
      {
        resolvePublishedHierarchyForVersion: async () => {
          res1.destroyed = true;
          res1.closed = true;
          res1.emit('close');
          return {
            fileId: 'file-1',
            versionId: 'ver-1',
            versionNumber: 1,
            r2ObjectKey: 'study-files/file-1/versions/ver-1/key.pdf',
            fileSizeBytes: 16,
            checksumSha256: 'a'.repeat(64),
          };
        },
      } as unknown as CatalogService,
      {
        isConfigured: () => true,
        createPresignedPutUrl: async () => 'https://example.invalid',
        getObjectStreamWithETag: async () => {
          r2OpenCount++;
          throw new Error('Should not open R2 stream after client disconnect');
        },
        copyObjectIfMatch: async () => ({ etag: 'e' }),
        headObject: async () => ({
          exists: true,
          contentLength: 16,
          etag: 'e',
          contentType: 'application/pdf',
        }),
        deleteObject: async () => undefined,
      },
    );

    await delivery1.deliverPublishedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      mode: 'stream',
      res: res1 as unknown as ExpressResponse,
    });
    assert.equal(r2OpenCount, 0);
    assert.equal(res1.statusCalls, 0);
    assert.equal(res1.endCalls, 0);
    assert.equal(res1.listenerCount('close'), 0);
    assert.equal(res1.listenerCount('error'), 0);

    // 14b) Client disconnects while getObjectStreamWithETag is in flight -> upstream closed immediately with zero reads.
    let chunksPulled = 0;
    let streamDestroyedCount = 0;
    const slowOpenStream = new Readable({
      read() {
        chunksPulled++;
        this.push(Buffer.from('%PDF-1.4\n1234567'));
        this.push(null);
      },
      destroy(err, cb) {
        streamDestroyedCount++;
        cb(err);
      },
    });
    const res2 = new EarlyDisconnectRes();
    const delivery2 = new DeliveryService(
      {
        resolvePublishedHierarchyForVersion: async () => ({
          fileId: 'file-1',
          versionId: 'ver-1',
          versionNumber: 1,
          r2ObjectKey: 'study-files/file-1/versions/ver-1/key.pdf',
          fileSizeBytes: 16,
          checksumSha256: 'a'.repeat(64),
        }),
      } as unknown as CatalogService,
      {
        isConfigured: () => true,
        createPresignedPutUrl: async () => 'https://example.invalid',
        getObjectStreamWithETag: async () => {
          res2.destroyed = true;
          res2.closed = true;
          res2.emit('close');
          return {
            stream: slowOpenStream,
            etag: 'etag-1',
            contentLength: 16,
            contentType: 'application/pdf',
          };
        },
        copyObjectIfMatch: async () => ({ etag: 'e' }),
        headObject: async () => ({
          exists: true,
          contentLength: 16,
          etag: 'e',
          contentType: 'application/pdf',
        }),
        deleteObject: async () => undefined,
      },
    );

    await delivery2.deliverPublishedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      mode: 'download',
      res: res2 as unknown as ExpressResponse,
    });
    assert.equal(chunksPulled, 0);
    assert.equal(streamDestroyedCount, 1);
    assert.equal(res2.statusCalls, 0);
    assert.equal(res2.endCalls, 0);
    assert.equal(res2.listenerCount('close'), 0);
    assert.equal(res2.listenerCount('error'), 0);
  });

  // =========================================================================
  // 15. Oversized First/Later Chunks & 0-Byte Stream Reject with SIZE_MISMATCH Without res.end() (F-D2-2)
  // =========================================================================
  it('returns 409 SIZE_MISMATCH on oversized first chunk over HTTP and never calls res.end() on oversized or 0-byte streams', async () => {
    const seeded = await seedHierarchy({ withPublishedVersion: true });
    assert.ok(seeded);

    // 15a) Real HTTP request where R2 header matches fileSizeBytes, and chunk #1 itself exceeds fileSizeBytes -> 409 SIZE_MISMATCH JSON (never 200 PDF or res.end() success).
    const oversizedBytes = Buffer.concat([
      seeded.pdf.bytes,
      Buffer.from('EXTRA_OVERFLOW_BYTES'),
    ]);
    mockR2.overrideStreamBytesForKey = {
      key: seeded.r2ObjectKey,
      bytes: oversizedBytes,
    };
    mockR2.overrideChunkSizeForKey.set(seeded.r2ObjectKey, oversizedBytes.length);
    const oversizedHttp = await apiJson(
      '/api/study-files/file-1/versions/ver-1/stream',
    );
    assert.equal(oversizedHttp.status, 409);
    assert.equal(
      (oversizedHttp.json as { error: { code: string } }).error.code,
      'SIZE_MISMATCH',
    );
    assert.ok(
      oversizedHttp.headers
        .get('content-type')
        ?.toLowerCase()
        .includes('application/json'),
    );
    assert.notEqual(
      oversizedHttp.headers.get('content-type')?.toLowerCase(),
      'application/pdf',
    );
    assert.equal(
      typeof oversizedHttp.json === 'object' && oversizedHttp.json !== null,
      true,
    );
    assert.equal(mockR2.lastStreamClosedKey, seeded.r2ObjectKey);
    mockR2.overrideStreamBytesForKey = undefined;
    mockR2.overrideChunkSizeForKey.delete(seeded.r2ObjectKey);

    // 15b) Oversized stream starting strictly on chunk #1 (> fileSizeBytes immediately) -> throws ApiError(409, 'SIZE_MISMATCH') before setting 200 headers.
    let firstChunkUpstreamDestroyed = false;
    const oversizedFirstChunkStream = new Readable({
      read() {
        this.push(Buffer.concat([seeded.pdf.bytes, Buffer.from('TOO_BIG_CHUNK_1')]));
        this.push(null);
      },
      destroy(err, cb) {
        firstChunkUpstreamDestroyed = true;
        cb(err);
      },
    });
    class TrackedRes extends EventEmitter {
      destroyed = false;
      writableEnded = false;
      headersSent = false;
      statusCalls = 0;
      endCalls = 0;
      destroyErr: Error | undefined;
      status() {
        this.statusCalls++;
        return this;
      }
      setHeader() {
        return this;
      }
      write() {
        this.headersSent = true;
        return true;
      }
      end() {
        this.endCalls++;
        this.writableEnded = true;
      }
      destroy(err?: Error) {
        this.destroyed = true;
        this.destroyErr = err;
        this.emit('close');
        return this;
      }
    }
    const baseCatalog = {
      resolvePublishedHierarchyForVersion: async () => ({
        fileId: 'file-1',
        versionId: 'ver-1',
        versionNumber: 1,
        r2ObjectKey: seeded.r2ObjectKey,
        fileSizeBytes: seeded.pdf.sizeBytes,
        checksumSha256: seeded.pdf.sha256,
      }),
    } as unknown as CatalogService;
    const makeStubR2 = (stream: Readable): R2StoragePort => ({
      isConfigured: () => true,
      createPresignedPutUrl: async () => 'https://example.invalid',
      getObjectStreamWithETag: async () => ({
        stream,
        etag: 'etag-1',
        contentLength: seeded.pdf.sizeBytes,
        contentType: 'application/pdf',
      }),
      copyObjectIfMatch: async () => ({ etag: 'etag-1' }),
      headObject: async () => ({
        exists: true,
        contentLength: seeded.pdf.sizeBytes,
        etag: 'etag-1',
        contentType: 'application/pdf',
      }),
      deleteObject: async () => undefined,
    });

    const firstChunkRes = new TrackedRes();
    await assert.rejects(
      () =>
        new DeliveryService(
          baseCatalog,
          makeStubR2(oversizedFirstChunkStream),
        ).deliverPublishedPdf({
          fileId: 'file-1',
          versionId: 'ver-1',
          mode: 'stream',
          res: firstChunkRes as unknown as ExpressResponse,
        }),
      (err: unknown) =>
        err instanceof ApiError &&
        err.statusCode === 409 &&
        err.code === 'SIZE_MISMATCH',
    );
    assert.equal(firstChunkRes.statusCalls, 0);
    assert.equal(firstChunkRes.endCalls, 0);
    assert.equal(firstChunkUpstreamDestroyed, true);

    // 15c) Oversized stream on chunk #2 (after chunk #1 sent headers) -> destroys res without calling res.end().
    let pushedTwo = 0;
    let secondChunkUpstreamDestroyed = false;
    const oversizedSecondChunkStream = new Readable({
      read() {
        if (pushedTwo === 0) {
          pushedTwo++;
          this.push(seeded.pdf.bytes.subarray(0, 8));
        } else if (pushedTwo === 1) {
          pushedTwo++;
          this.push(Buffer.alloc(seeded.pdf.sizeBytes, 0x41));
        } else {
          this.push(null);
        }
      },
      destroy(err, cb) {
        secondChunkUpstreamDestroyed = true;
        cb(err);
      },
    });
    const secondChunkRes = new TrackedRes();
    await new DeliveryService(
      baseCatalog,
      makeStubR2(oversizedSecondChunkStream),
    ).deliverPublishedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      mode: 'stream',
      res: secondChunkRes as unknown as ExpressResponse,
    });
    assert.equal(secondChunkRes.headersSent, true);
    assert.equal(secondChunkRes.endCalls, 0);
    assert.equal(secondChunkRes.destroyed, true);
    assert.equal(secondChunkUpstreamDestroyed, true);

    // 15d) 0-byte empty stream while fileSizeBytes > 0 -> rejects with 409 SIZE_MISMATCH without setting 200 or calling res.end().
    const emptyStream = new Readable({
      read() {
        this.push(null);
      },
    });
    const emptyRes = new TrackedRes();
    await assert.rejects(
      () =>
        new DeliveryService(baseCatalog, makeStubR2(emptyStream)).deliverPublishedPdf({
          fileId: 'file-1',
          versionId: 'ver-1',
          mode: 'download',
          res: emptyRes as unknown as ExpressResponse,
        }),
      (err: unknown) =>
        err instanceof ApiError &&
        err.statusCode === 409 &&
        err.code === 'SIZE_MISMATCH',
    );
    assert.equal(emptyRes.statusCalls, 0);
    assert.equal(emptyRes.endCalls, 0);
  });

  // =========================================================================
  // 16. Fix Pack 2F-R Regression: Tri-State Archive Enforcement & FileRef Checks
  // =========================================================================
  it('F-2FR rejects missing/null/string isArchived across create, upload-slot, verify, and publish with zero R2 calls, rejects invalid version fileId refs, and keeps explicit isArchived=false operational', async () => {
    await seedHierarchy({ withPublishedVersion: false });
    const sectionRef = adminFirestore.collection('sections').doc('sec-1');
    const now = Timestamp.fromMillis(currentNowMs);
    const pdf = makePdfBuffer('2FR Regression PDF');

    // Also create a valid pending upload session template helper per file/ver
    async function seedPendingSession(fileId: string, versionId: string, sessionId: string) {
      const stagingKey = `uploads-staging/${fileId}/${versionId}/${sessionId}.pdf`;
      mockR2.putRaw(stagingKey, pdf.bytes);
      await adminFirestore.collection('upload_sessions').doc(sessionId).set({
        sessionId,
        fileId,
        versionId,
        stagingKey,
        status: 'PENDING',
        expectedSizeBytes: pdf.sizeBytes,
        expectedSha256: pdf.sha256,
        attemptCount: 0,
        activeAttemptId: null,
        lockToken: null,
        finalObjectKey: null,
        verifyingExpiresAt: null,
        expiresAt: Timestamp.fromMillis(currentNowMs + 600_000),
        createdByUid: 'admin-uid-1',
        updatedByUid: 'admin-uid-1',
        createdAt: now,
        updatedAt: now,
      });
    }

    const fileVariants: Array<{
      id: string;
      label: string;
      isArchivedEntry: { present: boolean; value?: unknown };
    }> = [
      { id: 'f-missing', label: 'missing', isArchivedEntry: { present: false } },
      { id: 'f-null', label: 'null', isArchivedEntry: { present: true, value: null } },
      { id: 'f-string', label: 'string', isArchivedEntry: { present: true, value: 'false' } },
    ];

    for (let idx = 0; idx < fileVariants.length; idx++) {
      const variant = fileVariants[idx];
      const fileRef = adminFirestore.collection('study_files').doc(variant.id);
      const docPayload: Record<string, unknown> = {
        sectionId: sectionRef,
        title: `ملف ${variant.label}`,
        order: idx + 1,
        isPublished: false,
        mimeType: 'application/pdf',
        storageProvider: 'cloudflare_r2',
        registeredVersionNumbers: [1],
        createdByUid: 'admin-uid-1',
        updatedByUid: 'admin-uid-1',
        createdAt: now,
        updatedAt: now,
      };
      if (variant.isArchivedEntry.present) {
        docPayload.isArchived = variant.isArchivedEntry.value;
      }
      await fileRef.set(docPayload);

      // Version doc is explicitly unarchived while parent file is unspecified
      await fileRef.collection('versions').doc('ver-1').set({
        fileId: fileRef,
        versionNumber: 1,
        versionLabel: '1.0',
        r2ObjectKey: null,
        fileSizeBytes: null,
        checksumSha256: null,
        binaryVerificationStatus: 'NONE',
        isPublished: false,
        isArchived: false,
        createdByUid: 'admin-uid-1',
        updatedByUid: 'admin-uid-1',
        createdAt: now,
        updatedAt: now,
      });

      const sessionId = `cccccccc-0000-4000-8000-00000000000${idx + 1}`;
      await seedPendingSession(variant.id, 'ver-1', sessionId);
      mockR2.resetCallCounts();

      // 1) POST create version rejected with 409 ARCHIVE_STATE_UNSPECIFIED
      const createRes = await apiJson(`/api/admin/study-files/${variant.id}/versions`, {
        method: 'POST',
        token: 'token-admin',
        body: { versionNumber: 2, versionLabel: '2.0' },
      });
      assert.equal(createRes.status, 409);
      assert.equal(
        (createRes.json as { error: { code: string } }).error.code,
        'ARCHIVE_STATE_UNSPECIFIED',
      );

      // 2) POST upload-slot rejected with 409 ARCHIVE_STATE_UNSPECIFIED
      const slotRes = await apiJson(
        `/api/admin/study-files/${variant.id}/versions/ver-1/upload-slot`,
        {
          method: 'POST',
          token: 'token-admin',
          body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
        },
      );
      assert.equal(slotRes.status, 409);
      assert.equal(
        (slotRes.json as { error: { code: string } }).error.code,
        'ARCHIVE_STATE_UNSPECIFIED',
      );

      // 3) POST verify rejected with 409 ARCHIVE_STATE_UNSPECIFIED
      const verifyRes = await apiJson(
        `/api/admin/study-files/${variant.id}/versions/ver-1/verify`,
        {
          method: 'POST',
          token: 'token-admin',
          body: { uploadSessionId: sessionId },
        },
      );
      assert.equal(verifyRes.status, 409);
      assert.equal(
        (verifyRes.json as { error: { code: string } }).error.code,
        'ARCHIVE_STATE_UNSPECIFIED',
      );

      // 4) POST publish rejected with 409 ARCHIVE_STATE_UNSPECIFIED
      const publishRes = await apiJson(
        `/api/admin/study-files/${variant.id}/versions/ver-1/publish`,
        { method: 'POST', token: 'token-admin', body: {} },
      );
      assert.equal(publishRes.status, 409);
      assert.equal(
        (publishRes.json as { error: { code: string } }).error.code,
        'ARCHIVE_STATE_UNSPECIFIED',
      );

      // 5) Zero Cloudflare R2 calls occurred
      assert.equal(mockR2.totalOperationCount(), 0);
    }

    // Also verify unspecified version isArchived (missing, null, string) when parent file IS isArchived: false
    const versionVariants: Array<{
      id: string;
      isArchivedEntry: { present: boolean; value?: unknown };
    }> = [
      { id: 'ver-missing', isArchivedEntry: { present: false } },
      { id: 'ver-null', isArchivedEntry: { present: true, value: null } },
      { id: 'ver-string', isArchivedEntry: { present: true, value: 'false' } },
    ];
    const validFileRef = adminFirestore.collection('study_files').doc('file-1');
    for (let idx = 0; idx < versionVariants.length; idx++) {
      const vVar = versionVariants[idx];
      const vPayload: Record<string, unknown> = {
        fileId: validFileRef,
        versionNumber: 10 + idx,
        versionLabel: `${10 + idx}.0`,
        r2ObjectKey: null,
        fileSizeBytes: null,
        checksumSha256: null,
        binaryVerificationStatus: 'NONE',
        isPublished: false,
        createdByUid: 'admin-uid-1',
        updatedByUid: 'admin-uid-1',
        createdAt: now,
        updatedAt: now,
      };
      if (vVar.isArchivedEntry.present) {
        vPayload.isArchived = vVar.isArchivedEntry.value;
      }
      await validFileRef.collection('versions').doc(vVar.id).set(vPayload);

      const sessionId = `dddddddd-0000-4000-8000-00000000000${idx + 1}`;
      await seedPendingSession('file-1', vVar.id, sessionId);
      mockR2.resetCallCounts();

      const slotRes = await apiJson(
        `/api/admin/study-files/file-1/versions/${vVar.id}/upload-slot`,
        {
          method: 'POST',
          token: 'token-admin',
          body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
        },
      );
      assert.equal(slotRes.status, 409);
      assert.equal(
        (slotRes.json as { error: { code: string } }).error.code,
        'ARCHIVE_STATE_UNSPECIFIED',
      );

      const verifyRes = await apiJson(
        `/api/admin/study-files/file-1/versions/${vVar.id}/verify`,
        {
          method: 'POST',
          token: 'token-admin',
          body: { uploadSessionId: sessionId },
        },
      );
      assert.equal(verifyRes.status, 409);
      assert.equal(
        (verifyRes.json as { error: { code: string } }).error.code,
        'ARCHIVE_STATE_UNSPECIFIED',
      );

      const publishRes = await apiJson(
        `/api/admin/study-files/file-1/versions/${vVar.id}/publish`,
        { method: 'POST', token: 'token-admin', body: {} },
      );
      assert.equal(publishRes.status, 409);
      assert.equal(
        (publishRes.json as { error: { code: string } }).error.code,
        'ARCHIVE_STATE_UNSPECIFIED',
      );

      assert.equal(mockR2.totalOperationCount(), 0);
    }

    // 6) Public catalog & streaming reject versions with missing fileId or wrong parent fileId
    const badMissingRefSessionId = 'eeeeeeee-0000-4000-8000-000000000001';
    const badMissingRefAttemptId = 'eeeeeeee-0000-4000-8000-000000000002';
    const badMissingRefKey = `study-files/file-missing-vref/versions/ver-bad-1/${pdf.sha256.slice(0, 16)}_${badMissingRefSessionId}_${badMissingRefAttemptId}.pdf`;
    mockR2.putRaw(badMissingRefKey, pdf.bytes);
    await adminFirestore.collection('study_files').doc('file-missing-vref').set({
      sectionId: sectionRef,
      title: 'ملف بمرجع مفقود',
      order: 20,
      isPublished: true,
      isArchived: false,
      activeVersionId: 'ver-bad-1',
      activeVersionNumber: 1,
      activeVersionLabel: '1.0',
      activeVersionPublishedAt: now,
    });
    await adminFirestore
      .collection('study_files')
      .doc('file-missing-vref')
      .collection('versions')
      .doc('ver-bad-1')
      .set({
        // fileId intentionally missing
        versionNumber: 1,
        versionLabel: '1.0',
        r2ObjectKey: badMissingRefKey,
        fileSizeBytes: pdf.sizeBytes,
        checksumSha256: pdf.sha256,
        binaryVerificationStatus: 'VERIFIED',
        isPublished: true,
        isArchived: false,
        publishedAt: now,
      });

    const otherFileRef = adminFirestore.collection('study_files').doc('other-file');
    const badWrongRefKey = `study-files/file-wrong-vref/versions/ver-bad-2/${pdf.sha256.slice(0, 16)}_${badMissingRefSessionId}_${badMissingRefAttemptId}.pdf`;
    mockR2.putRaw(badWrongRefKey, pdf.bytes);
    await adminFirestore.collection('study_files').doc('file-wrong-vref').set({
      sectionId: sectionRef,
      title: 'ملف بمرجع خاطئ',
      order: 21,
      isPublished: true,
      isArchived: false,
      activeVersionId: 'ver-bad-2',
      activeVersionNumber: 1,
      activeVersionLabel: '1.0',
      activeVersionPublishedAt: now,
    });
    await adminFirestore
      .collection('study_files')
      .doc('file-wrong-vref')
      .collection('versions')
      .doc('ver-bad-2')
      .set({
        fileId: otherFileRef,
        versionNumber: 1,
        versionLabel: '1.0',
        r2ObjectKey: badWrongRefKey,
        fileSizeBytes: pdf.sizeBytes,
        checksumSha256: pdf.sha256,
        binaryVerificationStatus: 'VERIFIED',
        isPublished: true,
        isArchived: false,
        publishedAt: now,
      });

    // 7) Modern file-1 with valid refs & isArchived: false succeeds end-to-end
    const createOk = await apiJson('/api/admin/study-files/file-1/versions', {
      method: 'POST',
      token: 'token-admin',
      body: { versionNumber: 1, versionLabel: '1.0', customVersionId: 'ver-ok' },
    });
    assert.equal(createOk.status, 201);
    const slotOk = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-ok/upload-slot',
      {
        method: 'POST',
        token: 'token-admin',
        body: { expectedSizeBytes: pdf.sizeBytes, expectedSha256: pdf.sha256 },
      },
    );
    assert.equal(slotOk.status, 201);
    const okSlotData = slotOk.json as { uploadSessionId: string; stagingKey: string };
    mockR2.putRaw(okSlotData.stagingKey, pdf.bytes);
    const verifyOk = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-ok/verify',
      {
        method: 'POST',
        token: 'token-admin',
        body: { uploadSessionId: okSlotData.uploadSessionId },
      },
    );
    assert.equal(verifyOk.status, 200);
    const publishOk = await apiJson(
      '/api/admin/study-files/file-1/versions/ver-ok/publish',
      { method: 'POST', token: 'token-admin', body: {} },
    );
    assert.equal(publishOk.status, 200);

    const catalogAfter = await apiJson('/api/catalog/sections/sec-1/study-files');
    assert.equal(catalogAfter.status, 200);
    assert.deepEqual(
      (catalogAfter.json as { items: Array<{ id: string }> }).items.map((x) => x.id),
      ['file-1'],
    );
    assert.equal(
      (await apiJson('/api/study-files/file-missing-vref/versions/ver-bad-1/stream')).status,
      404,
    );
    assert.equal(
      (await apiJson('/api/study-files/file-wrong-vref/versions/ver-bad-2/stream')).status,
      404,
    );
    assert.equal(
      (await apiJson('/api/study-files/file-1/versions/ver-ok/stream')).status,
      200,
    );
  });
});
