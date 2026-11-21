import { Buffer } from 'node:buffer';
import { createHash } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import { after, before, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
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
    return `https://mock-r2.local/upload/${encodeURIComponent(params.key)}?expires=${params.expiresInSeconds}`;
  }

  async getObjectStreamWithETag(key: string): Promise<R2ObjectStreamResult> {
    if (this.onGetStreamHook) {
      await this.onGetStreamHook(key);
    }
    const obj = this.objects.get(key);
    if (!obj) {
      throw new ApiError(404, 'R2_OBJECT_MISSING', `Mock R2 object '${key}' not found.`);
    }
    const bytes = obj.bytes;
    async function* chunkedStream(): AsyncIterable<Uint8Array> {
      const chunkSize = 8;
      for (let i = 0; i < bytes.length; i += chunkSize) {
        yield bytes.subarray(i, i + chunkSize);
      }
    }
    return {
      stream: chunkedStream(),
      etag: obj.etag,
      contentLength: bytes.length,
      contentType: obj.contentType,
    };
  }

  async copyObjectIfMatch(
    params: R2CopyIfMatchParams,
  ): Promise<{ etag: string | null }> {
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
    if (this.forceMissingHeadForKey === key) {
      return { exists: false, contentLength: null, etag: null, contentType: null };
    }
    const obj = this.objects.get(key);
    if (!obj) {
      return { exists: false, contentLength: null, etag: null, contentType: null };
    }
    return {
      exists: true,
      contentLength: obj.bytes.length,
      etag: obj.etag,
      contentType: obj.contentType,
    };
  }

  async deleteObject(key: string): Promise<void> {
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
    verifier.calls.length = 0;
    currentNowMs = 1_750_000_000_000;
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

    // Verify public catalog now exposes file-1 + stream/download works
    const catalogRes = await apiJson('/api/catalog/sections/sec-1/study-files');
    assert.equal(catalogRes.status, 200);
    const files = (catalogRes.json as { items: Array<{ id: string }> }).items;
    assert.equal(files.length, 1);
    assert.equal(files[0].id, 'file-1');
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
});
