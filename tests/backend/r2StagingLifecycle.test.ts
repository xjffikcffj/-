import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { describe, it } from 'node:test';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import { loadServerConfig, type ServerConfig } from '../../server/config/env.js';
import {
  UploadVerificationService,
  buildFinalImmutableKey,
  buildStagingKey,
} from '../../server/services/uploadVerificationService.js';
import type {
  PresignedPutParams,
  R2CopyIfMatchParams,
  R2HeadResult,
  R2ObjectStreamResult,
  R2StoragePort,
} from '../../server/storage/r2Storage.js';
import { ApiError } from '../../server/utils/errors.js';

interface StoredObject {
  bytes: Buffer;
  etag: string;
}

class LifecycleMockR2Adapter implements R2StoragePort {
  readonly objects = new Map<string, StoredObject>();
  readonly deletedKeys: string[] = [];
  failDeleteForKeyPrefix?: string;
  failDeleteErrorMessage = 'Simulated R2 DeleteObject error';
  corruptCopyForSourceKey?: string;
  onGetStreamHook?: (key: string) => Promise<void>;
  private etagCounter = 1;

  isConfigured(): boolean {
    return true;
  }

  putRaw(key: string, bytes: Buffer): string {
    const etag = `etag-${this.etagCounter++}`;
    this.objects.set(key, { bytes: Buffer.from(bytes), etag });
    return etag;
  }

  async createPresignedPutUrl(params: PresignedPutParams): Promise<string> {
    return `https://mock-r2.local/${params.key}?X-Amz-Signature=secret123`;
  }

  async getObjectStreamWithETag(key: string): Promise<R2ObjectStreamResult> {
    if (this.onGetStreamHook) {
      await this.onGetStreamHook(key);
    }
    const obj = this.objects.get(key);
    if (!obj) {
      throw new ApiError(404, 'R2_OBJECT_MISSING', `Missing mock object ${key}`);
    }
    const stored = obj;
    async function* chunks() {
      yield stored.bytes;
    }
    return {
      stream: chunks(),
      etag: stored.etag,
      contentLength: stored.bytes.length,
      contentType: 'application/pdf',
    };
  }

  async copyObjectIfMatch(
    params: R2CopyIfMatchParams,
  ): Promise<{ etag: string | null }> {
    const src = this.objects.get(params.sourceKey);
    if (!src) {
      throw new ApiError(404, 'R2_OBJECT_MISSING', 'Source missing');
    }
    if (src.etag !== params.expectedETag) {
      throw new ApiError(412, 'ETAG_MISMATCH', 'ETag mismatch');
    }
    const destBytes = Buffer.from(src.bytes);
    if (this.corruptCopyForSourceKey === params.sourceKey) {
      destBytes[destBytes.length - 8] = destBytes[destBytes.length - 8] ^ 0x01;
    }
    const etag = this.putRaw(params.destinationKey, destBytes);
    return { etag };
  }

  async headObject(key: string): Promise<R2HeadResult> {
    const obj = this.objects.get(key);
    if (!obj) {
      return { exists: false, contentLength: null, etag: null, contentType: null };
    }
    return {
      exists: true,
      contentLength: obj.bytes.length,
      etag: obj.etag,
      contentType: 'application/pdf',
    };
  }

  async deleteObject(key: string): Promise<void> {
    if (
      this.failDeleteForKeyPrefix &&
      key.startsWith(this.failDeleteForKeyPrefix)
    ) {
      throw new Error(this.failDeleteErrorMessage);
    }
    this.deletedKeys.push(key);
    this.objects.delete(key);
  }
}

class InMemoryTransactionalFirestore {
  readonly docs = new Map<string, Record<string, unknown>>();
  failGetCollection?: string;
  private txChain: Promise<unknown> = Promise.resolve();

  getRaw(docPath: string): Record<string, unknown> | undefined {
    const d = this.docs.get(docPath);
    return d ? { ...d } : undefined;
  }

  private makeSnap(docPath: string) {
    const data = this.docs.get(docPath);
    return {
      exists: Boolean(data),
      id: path.posix.basename(docPath),
      ref: this.makeDocRef(docPath),
      data: () => (data ? { ...data } : undefined),
    };
  }

  private makeDocRef(docPath: string): Record<string, unknown> {
    const self = this;
    return {
      path: docPath,
      id: path.posix.basename(docPath),
      async get() {
        const col = docPath.split('/')[0];
        if (self.failGetCollection === col) {
          throw new Error(`Simulated Firestore read failure on ${col}`);
        }
        return self.makeSnap(docPath);
      },
      collection(subName: string) {
        return self.makeCollectionRef(`${docPath}/${subName}`);
      },
    };
  }

  private makeCollectionRef(colPath: string): Record<string, unknown> {
    const self = this;
    const buildQuery = (
      filters: Array<{ field: string; value: unknown }>,
    ): {
      where: (field: string, op: string, value: unknown) => unknown;
      get: () => Promise<{ docs: unknown[] }>;
    } => ({
      where(field: string, _op: string, value: unknown) {
        return buildQuery([...filters, { field, value }]);
      },
      async get() {
        const prefix = `${colPath}/`;
        const matched = Array.from(self.docs.entries())
          .filter(([p, data]) => {
            if (!p.startsWith(prefix)) return false;
            const rest = p.slice(prefix.length);
            if (rest.includes('/')) return false;
            return filters.every((f) => data[f.field] === f.value);
          })
          .map(([p]) => self.makeSnap(p));
        return { docs: matched };
      },
    });

    return {
      doc(docId: string) {
        return self.makeDocRef(`${colPath}/${docId}`);
      },
      where(field: string, _op: string, value: unknown) {
        return buildQuery([{ field, value }]);
      },
      async get() {
        return buildQuery([]).get();
      },
    };
  }

  asFirestore(): Firestore {
    const self = this;
    const db = {
      collection(name: string) {
        return self.makeCollectionRef(name);
      },
      async runTransaction<T>(fn: (tx: unknown) => Promise<T>): Promise<T> {
        const run = self.txChain.then(async () => {
          const stagedWrites: Array<() => void> = [];
          const tx = {
            async get(target: { path?: string; get?: () => Promise<unknown> }) {
              if (target.path) {
                return self.makeSnap(target.path);
              }
              return target.get ? target.get() : { docs: [] };
            },
            set(ref: { path: string }, data: Record<string, unknown>) {
              stagedWrites.push(() => {
                self.docs.set(ref.path, { ...data });
              });
              return tx;
            },
            update(ref: { path: string }, patch: Record<string, unknown>) {
              stagedWrites.push(() => {
                const existing = self.docs.get(ref.path);
                if (!existing) {
                  throw new Error(`Missing document for update: ${ref.path}`);
                }
                self.docs.set(ref.path, { ...existing, ...patch });
              });
              return tx;
            },
          };
          const result = await fn(tx);
          for (const apply of stagedWrites) {
            apply();
          }
          return result;
        });
        self.txChain = run.catch(() => undefined);
        return run;
      },
    };
    return db as unknown as Firestore;
  }
}

function makePdfFixture(label: string) {
  const bytes = Buffer.from(`%PDF-1.4\n%${label}\n%%EOF\n`, 'utf8');
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  return { bytes, sizeBytes: bytes.length, sha256 };
}

function createHarness(nowStart = 1_700_000_000_000) {
  let nowMs = nowStart;
  let seq = 1;
  const warnings: string[] = [];
  const fakeDb = new InMemoryTransactionalFirestore();
  const r2 = new LifecycleMockR2Adapter();

  fakeDb.docs.set('study_files/file-1', {
    title: 'ملف تجريبي',
    isPublished: false,
    isArchived: false,
  });
  fakeDb.docs.set('study_files/file-1/versions/ver-1', {
    fileId: { id: 'file-1', path: 'study_files/file-1' },
    versionNumber: 1,
    versionLabel: 'الإصدار 1',
    isPublished: false,
    isArchived: false,
    binaryVerificationStatus: 'PENDING_UPLOAD',
  });

  const config: ServerConfig = {
    ...loadServerConfig({}),
    r2Endpoint: 'https://mock-r2.local',
    r2BucketName: 'haqibati-test',
    r2AccessKeyId: 'test-key',
    r2SecretAccessKey: 'test-secret',
  };

  const service = new UploadVerificationService({
    firestore: fakeDb.asFirestore(),
    r2Storage: r2,
    config,
    nowMs: () => nowMs,
    uuidGenerator: () => {
      const s = String(seq++).padStart(12, '0');
      return `11111111-2222-4333-8444-${s}`;
    },
    warn: (msg) => warnings.push(msg),
  });

  return {
    service,
    fakeDb,
    r2,
    warnings,
    getNow: () => nowMs,
    advanceMs: (delta: number) => {
      nowMs += delta;
    },
  };
}

describe('R2 Staging Lifecycle & Safe Cleanup (Fix Pack 2D)', () => {
  it('1) removes uploads-staging key cleanly when verification succeeds and isolates final study-files key', async () => {
    const h = createHarness();
    const pdf = makePdfFixture('LifecycleSuccess');

    const slot = await h.service.createUploadSlot({
      fileId: 'file-1',
      versionId: 'ver-1',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      actorUid: 'admin-1',
    });
    assert.equal(slot.expiresAtEpochMillis, h.getNow() + 600_000);
    assert.equal(slot.stagingKey.startsWith('uploads-staging/'), true);

    h.r2.putRaw(slot.stagingKey, pdf.bytes);
    const verified = await h.service.verifyUploadedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      uploadSessionId: slot.uploadSessionId,
      actorUid: 'admin-1',
    });

    assert.equal(verified.status, 'VERIFIED');
    assert.equal(verified.r2ObjectKey.startsWith('study-files/'), true);
    assert.equal(h.r2.objects.has(slot.stagingKey), false);
    assert.equal(h.r2.objects.has(verified.r2ObjectKey), true);
    assert.equal(h.warnings.length, 0);
  });

  it('2) commits verification cleanly even when staging deletion fails, logging a sanitized warning without leaking URLs', async () => {
    const h = createHarness();
    const pdf = makePdfFixture('StagingDeleteError');

    const slot = await h.service.createUploadSlot({
      fileId: 'file-1',
      versionId: 'ver-1',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      actorUid: 'admin-1',
    });
    h.r2.putRaw(slot.stagingKey, pdf.bytes);
    h.r2.failDeleteForKeyPrefix = 'uploads-staging/';
    h.r2.failDeleteErrorMessage =
      'R2 DeleteObject 503 on https://r2.example.com/bucket/key?X-Amz-Signature=supersecret';

    const verified = await h.service.verifyUploadedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      uploadSessionId: slot.uploadSessionId,
      actorUid: 'admin-1',
    });

    assert.equal(verified.status, 'VERIFIED');
    assert.equal(h.r2.objects.has(verified.r2ObjectKey), true);
    assert.equal(h.warnings.length, 1);
    assert.match(h.warnings[0], /uploads-staging\//);
    assert.doesNotMatch(h.warnings[0], /supersecret/);
    assert.doesNotMatch(h.warnings[0], /https:\/\/r2\.example\.com/);
  });

  it('3) never deletes a verified or bound final object under study-files/', async () => {
    const h = createHarness();
    const pdf = makePdfFixture('PreserveVerifiedFinal');

    const slot = await h.service.createUploadSlot({
      fileId: 'file-1',
      versionId: 'ver-1',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      actorUid: 'admin-1',
    });
    h.r2.putRaw(slot.stagingKey, pdf.bytes);
    const verified = await h.service.verifyUploadedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      uploadSessionId: slot.uploadSessionId,
      actorUid: 'admin-1',
    });
    const published = await h.service.publishVersion({
      fileId: 'file-1',
      versionId: 'ver-1',
      actorUid: 'owner-1',
    });
    assert.equal(published.status, 'PUBLISHED');

    // Subsequent upload-slot MUST reject (409) and never delete the bound final object
    await assert.rejects(
      () =>
        h.service.createUploadSlot({
          fileId: 'file-1',
          versionId: 'ver-1',
          expectedSizeBytes: pdf.sizeBytes,
          expectedSha256: pdf.sha256,
          actorUid: 'admin-1',
        }),
      (err: unknown) =>
        err instanceof ApiError && err.code === 'VERSION_ALREADY_PUBLISHED',
    );
    assert.equal(h.r2.objects.has(verified.r2ObjectKey), true);
    assert.equal(
      h.r2.deletedKeys.some((k) => k.startsWith('study-files/')),
      false,
    );
  });

  it('4) refuses to delete attemptFinalKey during failure cleanup if Firestore state read fails', async () => {
    const h = createHarness();
    const pdf = makePdfFixture('FirestoreReadUncertain');

    const slot = await h.service.createUploadSlot({
      fileId: 'file-1',
      versionId: 'ver-1',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      actorUid: 'admin-1',
    });
    h.r2.putRaw(slot.stagingKey, pdf.bytes);
    h.r2.corruptCopyForSourceKey = slot.stagingKey;
    h.fakeDb.failGetCollection = 'upload_sessions';

    await assert.rejects(
      () =>
        h.service.verifyUploadedPdf({
          fileId: 'file-1',
          versionId: 'ver-1',
          uploadSessionId: slot.uploadSessionId,
          actorUid: 'admin-1',
        }),
      (err: unknown) => err instanceof ApiError && err.code === 'SHA256_MISMATCH',
    );

    // Final object copied into study-files/ MUST NOT be deleted when Firestore state is uncertain
    assert.equal(
      h.r2.deletedKeys.some((k) => k.startsWith('study-files/')),
      false,
    );
    const remainingFinalKeys = Array.from(h.r2.objects.keys()).filter((k) =>
      k.startsWith('study-files/'),
    );
    assert.equal(remainingFinalKeys.length, 1);
  });

  it('5) does NOT delete active VERIFYING staging object while verification lock is held within TTL', async () => {
    const h = createHarness();
    const pdf = makePdfFixture('ActiveVerifyingLock');

    const slot = await h.service.createUploadSlot({
      fileId: 'file-1',
      versionId: 'ver-1',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      actorUid: 'admin-1',
    });
    h.r2.putRaw(slot.stagingKey, pdf.bytes);

    let releaseWorker!: () => void;
    const workerPaused = new Promise<void>((resolve) => {
      h.r2.onGetStreamHook = async (key) => {
        if (key === slot.stagingKey && h.r2.onGetStreamHook) {
          h.r2.onGetStreamHook = undefined;
          resolve();
          await new Promise<void>((r) => {
            releaseWorker = r;
          });
        }
      };
    });

    const verifyPromise = h.service.verifyUploadedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      uploadSessionId: slot.uploadSessionId,
      actorUid: 'admin-1',
    });
    await workerPaused;

    // Attempting a new upload-slot or second verify while lock active MUST fail 409 and NOT delete stagingKey
    await assert.rejects(
      () =>
        h.service.createUploadSlot({
          fileId: 'file-1',
          versionId: 'ver-1',
          expectedSizeBytes: pdf.sizeBytes,
          expectedSha256: pdf.sha256,
          actorUid: 'admin-2',
        }),
      (err: unknown) => err instanceof ApiError && err.code === 'VERIFY_IN_PROGRESS',
    );
    assert.equal(h.r2.objects.has(slot.stagingKey), true);

    releaseWorker();
    const result = await verifyPromise;
    assert.equal(result.status, 'VERIFIED');
  });

  it('6) cleans staging keys for EXPIRED, SUPERSEDED, and FAILED sessions safely without affecting newer sessions', async () => {
    const h = createHarness();
    const pdf = makePdfFixture('TerminalSessionCleanups');

    // A) Superseded slot -> staging key deleted when new slot supersedes it
    const slot1 = await h.service.createUploadSlot({
      fileId: 'file-1',
      versionId: 'ver-1',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      actorUid: 'admin-1',
    });
    h.r2.putRaw(slot1.stagingKey, pdf.bytes);

    const slot2 = await h.service.createUploadSlot({
      fileId: 'file-1',
      versionId: 'ver-1',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      actorUid: 'admin-1',
    });
    assert.equal(h.r2.objects.has(slot1.stagingKey), false);
    assert.equal(h.fakeDb.getRaw(`upload_sessions/${slot1.uploadSessionId}`)?.status, 'SUPERSEDED');

    // B) Failed verification (non-PDF header) -> marks FAILED and deletes slot2 staging key
    h.r2.putRaw(slot2.stagingKey, Buffer.alloc(pdf.sizeBytes, 'Z'));
    await assert.rejects(
      () =>
        h.service.verifyUploadedPdf({
          fileId: 'file-1',
          versionId: 'ver-1',
          uploadSessionId: slot2.uploadSessionId,
          actorUid: 'admin-1',
        }),
      (err: unknown) => err instanceof ApiError && err.code === 'INVALID_PDF_HEADER',
    );
    assert.equal(h.r2.objects.has(slot2.stagingKey), false);
    assert.equal(h.fakeDb.getRaw(`upload_sessions/${slot2.uploadSessionId}`)?.status, 'FAILED');

    // C) Expired PENDING session -> transitions to EXPIRED and deletes slot3 staging key
    const slot3 = await h.service.createUploadSlot({
      fileId: 'file-1',
      versionId: 'ver-1',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      actorUid: 'admin-1',
    });
    h.r2.putRaw(slot3.stagingKey, pdf.bytes);
    h.advanceMs(601_000);
    await assert.rejects(
      () =>
        h.service.verifyUploadedPdf({
          fileId: 'file-1',
          versionId: 'ver-1',
          uploadSessionId: slot3.uploadSessionId,
          actorUid: 'admin-1',
        }),
      (err: unknown) => err instanceof ApiError && err.code === 'SESSION_EXPIRED',
    );
    assert.equal(h.r2.objects.has(slot3.stagingKey), false);
    assert.equal(h.fakeDb.getRaw(`upload_sessions/${slot3.uploadSessionId}`)?.status, 'EXPIRED');
  });

  it('7) stale worker losing lock cannot overwrite newer attempt, mark session FAILED, or delete active staging/final object', async () => {
    const h = createHarness();
    const pdf = makePdfFixture('FencingLockIsolation');

    const slot = await h.service.createUploadSlot({
      fileId: 'file-1',
      versionId: 'ver-1',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      actorUid: 'admin-1',
    });
    h.r2.putRaw(slot.stagingKey, pdf.bytes);

    let releaseWorker1!: () => void;
    let paused = false;
    const worker1InPhaseC = new Promise<void>((resolve) => {
      h.r2.onGetStreamHook = async (key) => {
        if (!paused && key.startsWith('study-files/')) {
          paused = true;
          resolve();
          await new Promise<void>((r) => {
            releaseWorker1 = r;
          });
        }
      };
    });

    const worker1Promise = h.service.verifyUploadedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      uploadSessionId: slot.uploadSessionId,
      actorUid: 'admin-1',
    });
    await worker1InPhaseC;

    // Advance past 300s lock TTL and let Worker 2 succeed
    h.advanceMs(305_000);
    h.r2.onGetStreamHook = undefined;
    const worker2 = await h.service.verifyUploadedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      uploadSessionId: slot.uploadSessionId,
      actorUid: 'admin-2',
    });
    assert.equal(worker2.status, 'VERIFIED');

    // Now Worker 1 resumes and MUST fail with 409 CONFLICT_STALE_LOCK without hurting Worker 2's final object
    releaseWorker1();
    await assert.rejects(
      () => worker1Promise,
      (err: unknown) => err instanceof ApiError && err.code === 'CONFLICT_STALE_LOCK',
    );
    assert.equal(h.r2.objects.has(worker2.r2ObjectKey), true);
    assert.equal(h.fakeDb.getRaw(`upload_sessions/${slot.uploadSessionId}`)?.status, 'CONSUMED');
    assert.equal(
      h.fakeDb.getRaw('study_files/file-1/versions/ver-1')?.r2ObjectKey,
      worker2.r2ObjectKey,
    );
  });

  it('8) preserves idempotent verify response after staging key has already been cleaned up', async () => {
    const h = createHarness();
    const pdf = makePdfFixture('IdempotentVerify');

    const slot = await h.service.createUploadSlot({
      fileId: 'file-1',
      versionId: 'ver-1',
      expectedSizeBytes: pdf.sizeBytes,
      expectedSha256: pdf.sha256,
      actorUid: 'admin-1',
    });
    h.r2.putRaw(slot.stagingKey, pdf.bytes);

    const first = await h.service.verifyUploadedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      uploadSessionId: slot.uploadSessionId,
      actorUid: 'admin-1',
    });
    assert.equal(first.idempotent, false);
    assert.equal(h.r2.objects.has(slot.stagingKey), false);

    const retry = await h.service.verifyUploadedPdf({
      fileId: 'file-1',
      versionId: 'ver-1',
      uploadSessionId: slot.uploadSessionId,
      actorUid: 'admin-1',
    });
    assert.equal(retry.idempotent, true);
    assert.equal(retry.r2ObjectKey, first.r2ObjectKey);
    assert.equal(retry.attemptId, first.attemptId);
    assert.equal(h.r2.objects.has(first.r2ObjectKey), true);
  });

  it('verifies staging and final key prefixes remain strictly isolated', () => {
    const staging = buildStagingKey('file-1', 'ver-1', '11111111-2222-4333-8444-555555555555');
    const finalKey = buildFinalImmutableKey({
      fileId: 'file-1',
      versionId: 'ver-1',
      sha256: 'a'.repeat(64),
      sessionId: '11111111-2222-4333-8444-555555555555',
      attemptId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
    });
    assert.equal(staging.startsWith('uploads-staging/'), true);
    assert.equal(finalKey.startsWith('study-files/'), true);
    assert.equal(staging.startsWith('study-files/'), false);
    assert.equal(finalKey.startsWith('uploads-staging/'), false);
  });
});
