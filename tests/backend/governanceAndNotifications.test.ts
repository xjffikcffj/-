import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { createHash } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import { describe, it } from 'node:test';
import type { Firestore } from 'firebase-admin/firestore';
import { createBackendApp } from '../../server/app.js';
import type { ServerConfig } from '../../server/config/env.js';
import {
  buildTopicFcmMessage,
  type DecodedIdTokenLike,
  type IdTokenVerifier,
  type PushNotificationDispatcher,
  type PushNotificationMessage,
  type StaffAuthManager,
  type StaffAuthUserRecord,
} from '../../server/firebase/adminApp.js';
import type { R2StoragePort } from '../../server/storage/r2Storage.js';
import { ApiError } from '../../server/utils/errors.js';

class InMemoryFirestore {
  private readonly store = new Map<string, Record<string, unknown>>();
  private autoIdSeq = 1;
  failAuditLogWrites = false;

  setRaw(path: string, data: Record<string, unknown>): void {
    this.store.set(path, { ...data });
  }

  getRaw(path: string): Record<string, unknown> | undefined {
    const val = this.store.get(path);
    return val ? { ...val } : undefined;
  }

  collection(name: string) {
    return this.makeCollection(name);
  }

  async runTransaction<T>(fn: (tx: {
    get: (target: ReturnType<InMemoryFirestore['makeDoc']> | ReturnType<InMemoryFirestore['makeCollection']>) => Promise<unknown>;
    set: (docRef: ReturnType<InMemoryFirestore['makeDoc']>, data: Record<string, unknown>) => void;
    update: (docRef: ReturnType<InMemoryFirestore['makeDoc']>, data: Record<string, unknown>) => void;
  }) => Promise<T>): Promise<T> {
    const tx = {
      get: async (target: ReturnType<InMemoryFirestore['makeDoc']> | ReturnType<InMemoryFirestore['makeCollection']>) => {
        return target.get();
      },
      set: (docRef: ReturnType<InMemoryFirestore['makeDoc']>, data: Record<string, unknown>) => {
        this.store.set(docRef.path, { ...data });
      },
      update: (docRef: ReturnType<InMemoryFirestore['makeDoc']>, data: Record<string, unknown>) => {
        const existing = this.store.get(docRef.path) ?? {};
        this.store.set(docRef.path, { ...existing, ...data });
      },
    };
    return fn(tx);
  }

  private makeDoc(path: string, id: string, collectionPath: string) {
    return {
      id,
      path,
      parent: { id: collectionPath.split('/').pop() ?? collectionPath },
      collection: (sub: string) => this.makeCollection(`${path}/${sub}`),
      get: async () => {
        const data = this.store.get(path);
        return {
          id,
          exists: data !== undefined,
          ref: this.makeDoc(path, id, collectionPath),
          data: () => (data ? { ...data } : undefined),
        };
      },
      set: async (data: Record<string, unknown>) => {
        if (this.failAuditLogWrites && collectionPath === 'audit_logs') {
          const err = new Error('Firestore audit write failed') as Error & { code?: string };
          err.code = 'firestore/unavailable';
          throw err;
        }
        this.store.set(path, { ...data });
      },
      update: async (data: Record<string, unknown>) => {
        const existing = this.store.get(path) ?? {};
        this.store.set(path, { ...existing, ...data });
      },
    };
  }

  private makeCollection(colPath: string) {
    const buildQuery = (filters: {
      wheres?: Array<{ field: string; op: string; value: unknown }>;
      orderByField?: string;
      orderDesc?: boolean;
      limitCount?: number;
    }) => {
      const api = {
        where: (field: string, op: string, value: unknown) =>
          buildQuery({
            ...filters,
            wheres: [...(filters.wheres ?? []), { field, op, value }],
          }),
        orderBy: (field: string, dir?: 'asc' | 'desc') =>
          buildQuery({ ...filters, orderByField: field, orderDesc: dir === 'desc' }),
        limit: (n: number) => buildQuery({ ...filters, limitCount: n }),
        get: async () => {
          const prefix = `${colPath}/`;
          let docs = Array.from(this.store.entries())
            .filter(([key]) => key.startsWith(prefix) && !key.slice(prefix.length).includes('/'))
            .map(([fullPath, data]) => {
              const id = fullPath.slice(prefix.length);
              return {
                id,
                exists: true,
                ref: this.makeDoc(fullPath, id, colPath),
                data: () => ({ ...data }),
              };
            });

          for (const w of filters.wheres ?? []) {
            if (w.op === '==') {
              docs = docs.filter((d) => d.data()[w.field] === w.value);
            } else if (w.op === 'in' && Array.isArray(w.value)) {
              docs = docs.filter((d) => (w.value as unknown[]).includes(d.data()[w.field]));
            }
          }

          if (filters.orderByField) {
            const field = filters.orderByField;
            docs.sort((a, b) => {
              const va = toComparableMs(a.data()[field]);
              const vb = toComparableMs(b.data()[field]);
              return filters.orderDesc ? vb - va : va - vb;
            });
          }
          if (typeof filters.limitCount === 'number') {
            docs = docs.slice(0, filters.limitCount);
          }
          return { docs };
        },
      };
      return api;
    };

    const baseQuery = buildQuery({});
    return {
      doc: (id?: string) => {
        const docId = id ?? `auto_${this.autoIdSeq++}`;
        return this.makeDoc(`${colPath}/${docId}`, docId, colPath);
      },
      where: baseQuery.where,
      orderBy: baseQuery.orderBy,
      limit: baseQuery.limit,
      get: baseQuery.get,
    };
  }
}

function toComparableMs(val: unknown): number {
  if (!val) return 0;
  if (typeof val === 'number') return val;
  if (typeof val === 'object' && 'toMillis' in val && typeof (val as { toMillis: () => number }).toMillis === 'function') {
    return (val as { toMillis: () => number }).toMillis();
  }
  return 0;
}

class MockR2 implements R2StoragePort {
  private readonly objects = new Map<string, { bytes: Buffer; etag: string; contentType: string }>();
  private etagSeq = 1;

  isConfigured(): boolean {
    return true;
  }

  putRaw(key: string, bytes: Buffer): void {
    this.objects.set(key, {
      bytes: Buffer.from(bytes),
      etag: `etag-${this.etagSeq++}`,
      contentType: 'application/pdf',
    });
  }

  async createPresignedPutUrl(params: { key: string }): Promise<string> {
    return `https://mock-r2.local/upload/${encodeURIComponent(params.key)}`;
  }

  async getObjectStreamWithETag(key: string) {
    const obj = this.objects.get(key);
    if (!obj) {
      throw new ApiError(404, 'R2_OBJECT_MISSING', 'Missing mock R2 object');
    }
    async function* gen() {
      yield new Uint8Array(obj!.bytes);
    }
    return {
      stream: gen(),
      etag: obj.etag,
      contentLength: obj.bytes.length,
      contentType: obj.contentType,
    };
  }

  async copyObjectIfMatch(params: { sourceKey: string; destinationKey: string; expectedETag: string; contentType: string }) {
    const src = this.objects.get(params.sourceKey);
    if (!src) throw new ApiError(404, 'R2_OBJECT_MISSING', 'Source missing');
    if (src.etag !== params.expectedETag) throw new ApiError(412, 'ETAG_MISMATCH', 'ETag mismatch');
    const etag = `etag-${this.etagSeq++}`;
    this.objects.set(params.destinationKey, {
      bytes: Buffer.from(src.bytes),
      etag,
      contentType: params.contentType,
    });
    return { etag };
  }

  async headObject(key: string) {
    const obj = this.objects.get(key);
    if (!obj) {
      return { exists: false, contentLength: null, etag: null, contentType: null };
    }
    return { exists: true, contentLength: obj.bytes.length, etag: obj.etag, contentType: obj.contentType };
  }

  async deleteObject(key: string): Promise<void> {
    this.objects.delete(key);
  }
}

class InMemoryStaffAuthManager implements StaffAuthManager {
  readonly users = new Map<string, StaffAuthUserRecord>();
  readonly revokedUids: string[] = [];
  failNextRevoke = false;

  seedUser(user: StaffAuthUserRecord): void {
    this.users.set(user.uid, {
      ...user,
      customClaims: user.customClaims ? { ...user.customClaims } : undefined,
    });
  }

  async listUsers(): Promise<{ users: StaffAuthUserRecord[]; pageToken?: string }> {
    return { users: Array.from(this.users.values()) };
  }

  async getUserByEmail(email: string): Promise<StaffAuthUserRecord> {
    const clean = email.trim().toLowerCase();
    for (const user of this.users.values()) {
      if ((user.email ?? '').toLowerCase() === clean) {
        return user;
      }
    }
    const err = new Error('User not found') as Error & { code?: string };
    err.code = 'auth/user-not-found';
    throw err;
  }

  async getUser(uid: string): Promise<StaffAuthUserRecord> {
    const found = this.users.get(uid);
    if (!found) {
      const err = new Error('User not found') as Error & { code?: string };
      err.code = 'auth/user-not-found';
      throw err;
    }
    return found;
  }

  async setCustomUserClaims(uid: string, customUserClaims: Record<string, unknown> | null): Promise<void> {
    const found = await this.getUser(uid);
    this.users.set(uid, {
      ...found,
      customClaims: customUserClaims ? { ...customUserClaims } : undefined,
    });
  }

  async revokeRefreshTokens(uid: string): Promise<void> {
    if (this.failNextRevoke) {
      this.failNextRevoke = false;
      const err = new Error('Token revocation temporarily unavailable') as Error & { code?: string };
      err.code = 'auth/internal-error';
      throw err;
    }
    this.revokedUids.push(uid);
  }
}

function testConfig(): ServerConfig {
  return {
    port: 0,
    trustProxy: false,
    firebaseProjectId: 'student-learning-platform-2026',
    r2AccountId: 'acc',
    r2AccessKeyId: 'key',
    r2SecretAccessKey: 'secret',
    r2BucketName: 'bucket',
    uploadSessionTtlSeconds: 900,
    verifyLockTtlSeconds: 60,
    maxPdfBytes: 25 * 1024 * 1024,
    allowedOrigins: [],
  };
}

describe('Governance, Audit Logs & Notification Dispatch Backend Routes', () => {
  function setupHarness(options?: { failNextPush?: boolean; withoutPushDispatcher?: boolean }) {
    const db = new InMemoryFirestore();
    const r2 = new MockR2();
    const staffManager = new InMemoryStaffAuthManager();

    staffManager.seedUser({
      uid: 'owner-1',
      email: 'owner@example.com',
      displayName: 'المالك',
      disabled: false,
      emailVerified: true,
      customClaims: { role: 'owner', tier: 'founder' },
    });
    staffManager.seedUser({
      uid: 'admin-1',
      email: 'admin@example.com',
      displayName: 'مشرف أول',
      disabled: false,
      emailVerified: true,
      customClaims: { role: 'admin', campus: 'baghdad' },
    });
    staffManager.seedUser({
      uid: 'student-1',
      email: 'student@example.com',
      displayName: 'طالب',
      disabled: false,
      emailVerified: true,
      customClaims: { cohort: '2026' },
    });

    db.setRaw('study_files/file-1', {
      sectionId: { id: 'sec-1', path: 'sections/sec-1', parent: { id: 'sections' } },
      title: 'ملزمة الفيزياء',
      order: 1,
      isPublished: false,
      isArchived: false,
      activeVersionId: null,
      activeVersionNumber: null,
      activeVersionLabel: null,
    });

    let clockMs = 1_700_000_000_000;
    let uuidSeq = 1;
    const sentPayloads: PushNotificationMessage[] = [];
    const dispatcher: PushNotificationDispatcher | undefined = options?.withoutPushDispatcher
      ? undefined
      : {
          async sendToTopic(payload: PushNotificationMessage): Promise<string> {
            if (options?.failNextPush) {
              const err = new Error('FCM down') as Error & { code?: string };
              err.code = 'messaging/internal-error';
              throw err;
            }
            sentPayloads.push(payload);
            return `msg-${sentPayloads.length}`;
          },
        };

    const verifier: IdTokenVerifier = {
      async verifyIdToken(token: string): Promise<DecodedIdTokenLike> {
        if (token === 'owner-token') return { uid: 'owner-1', role: 'owner' };
        if (token === 'admin-token') return { uid: 'admin-1', role: 'admin' };
        if (token === 'student-token') return { uid: 'student-1' };
        const err = new Error('Invalid token') as Error & { code?: string };
        err.code = 'auth/argument-error';
        throw err;
      },
    };

    const app = createBackendApp({
      config: testConfig(),
      firestore: db as unknown as Firestore,
      idTokenVerifier: verifier,
      r2Storage: r2,
      staffAuthManager: staffManager,
      pushNotificationDispatcher: dispatcher,
      nowMs: () => {
        clockMs += 1000;
        return clockMs;
      },
      uuidGenerator: () => {
        const hex = String(uuidSeq++).padStart(12, '0');
        return `11111111-1111-4111-8111-${hex}`;
      },
      enableRateLimiting: false,
    });

    return { db, r2, staffManager, sentPayloads, app };
  }

  async function withServer(
    app: ReturnType<typeof createBackendApp>,
    fn: (baseUrl: string) => Promise<void>,
  ) {
    const server = await new Promise<ReturnType<typeof app.listen>>((resolve) => {
      const s = app.listen(0, '127.0.0.1', () => resolve(s));
    });
    const addr = server.address() as AddressInfo;
    const baseUrl = `http://127.0.0.1:${addr.port}`;
    try {
      await fn(baseUrl);
    } finally {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  }

  it('enforces Owner-only staff endpoints, preserves claims, revokes tokens, and records audit logs', async () => {
    const { staffManager, app } = setupHarness();
    await withServer(app, async (baseUrl) => {
      // Guest -> 401
      const unauthRes = await fetch(`${baseUrl}/api/admin/staff`);
      assert.equal(unauthRes.status, 401);

      // Student -> 403
      const studentRes = await fetch(`${baseUrl}/api/admin/staff`, {
        headers: { Authorization: 'Bearer student-token' },
      });
      assert.equal(studentRes.status, 403);

      // Non-Owner Admin -> 403 INSUFFICIENT_ROLE
      const adminListRes = await fetch(`${baseUrl}/api/admin/staff`, {
        headers: { Authorization: 'Bearer admin-token' },
      });
      assert.equal(adminListRes.status, 403);
      const adminListJson = (await adminListRes.json()) as { error: { code: string } };
      assert.equal(adminListJson.error.code, 'INSUFFICIENT_ROLE');

      const adminGrantRes = await fetch(`${baseUrl}/api/admin/staff/roles`, {
        method: 'POST',
        headers: {
          Authorization: 'Bearer admin-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ targetEmail: 'student@example.com', role: 'admin' }),
      });
      assert.equal(adminGrantRes.status, 403);

      // Owner lists staff -> sorted owner first
      const ownerListRes = await fetch(`${baseUrl}/api/admin/staff`, {
        headers: { Authorization: 'Bearer owner-token' },
      });
      assert.equal(ownerListRes.status, 200);
      const staffBody = (await ownerListRes.json()) as {
        items: Array<{ uid: string; role: string }>;
      };
      assert.deepEqual(
        staffBody.items.map((u) => [u.uid, u.role]),
        [
          ['owner-1', 'owner'],
          ['admin-1', 'admin'],
        ],
      );

      // Cannot assign owner via API
      const assignOwnerRes = await fetch(`${baseUrl}/api/admin/staff/roles`, {
        method: 'POST',
        headers: {
          Authorization: 'Bearer owner-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ targetEmail: 'student@example.com', role: 'owner' }),
      });
      assert.equal(assignOwnerRes.status, 403);

      // Cannot modify self
      const selfRes = await fetch(`${baseUrl}/api/admin/staff/roles`, {
        method: 'POST',
        headers: {
          Authorization: 'Bearer owner-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ targetEmail: 'owner@example.com', role: 'none' }),
      });
      assert.equal(selfRes.status, 409);

      // Owner promotes student-1 -> admin while preserving cohort claim & revoking refresh tokens
      const grantRes = await fetch(`${baseUrl}/api/admin/staff/roles`, {
        method: 'POST',
        headers: {
          Authorization: 'Bearer owner-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ targetEmail: 'STUDENT@example.com', role: 'admin' }),
      });
      assert.equal(grantRes.status, 200);
      const promoted = await staffManager.getUser('student-1');
      assert.equal(promoted.customClaims?.role, 'admin');
      assert.equal(promoted.customClaims?.cohort, '2026');
      assert.ok(staffManager.revokedUids.includes('student-1'));

      // Owner revokes admin-1 while preserving campus claim & revoking refresh tokens
      const revokeRes = await fetch(`${baseUrl}/api/admin/staff/roles`, {
        method: 'POST',
        headers: {
          Authorization: 'Bearer owner-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ targetEmail: 'admin@example.com', role: 'none' }),
      });
      assert.equal(revokeRes.status, 200);
      const demoted = await staffManager.getUser('admin-1');
      assert.equal(demoted.customClaims?.role, undefined);
      assert.equal(demoted.customClaims?.campus, 'baghdad');
      assert.ok(staffManager.revokedUids.includes('admin-1'));

      // Nonexistent user -> 404 USER_NOT_FOUND
      const missingRes = await fetch(`${baseUrl}/api/admin/staff/roles`, {
        method: 'POST',
        headers: {
          Authorization: 'Bearer owner-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ targetEmail: 'nobody@example.com', role: 'admin' }),
      });
      assert.equal(missingRes.status, 404);

      // Audit logs record ADMIN_ROLE_GRANTED and ADMIN_ROLE_REVOKED newest-first
      const logsRes = await fetch(`${baseUrl}/api/admin/audit-logs?limit=10`, {
        headers: { Authorization: 'Bearer owner-token' },
      });
      assert.equal(logsRes.status, 200);
      const logsBody = (await logsRes.json()) as {
        items: Array<{ action: string; actorUid: string }>;
      };
      assert.deepEqual(
        logsBody.items.map((l) => l.action),
        ['ADMIN_ROLE_REVOKED', 'ADMIN_ROLE_GRANTED'],
      );
    });
  });

  it('records audit logs across version publish lifecycle, sends best-effort NEW_FILE and NEW_VERSION notifications, and broadcasts updates', async () => {
    const { r2, sentPayloads, app } = setupHarness();
    const pdfBytes = Buffer.from('%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n', 'utf8');
    const pdfSha = createHash('sha256').update(pdfBytes).digest('hex');

    await withServer(app, async (baseUrl) => {
      // 1) Create v1, upload & verify & publish -> NEW_FILE
      const v1Res = await fetch(`${baseUrl}/api/admin/study-files/file-1/versions`, {
        method: 'POST',
        headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ customVersionId: 'v1', versionNumber: 1, versionLabel: '1.0' }),
      });
      assert.equal(v1Res.status, 201);

      const patchRes = await fetch(`${baseUrl}/api/admin/study-files/file-1/versions/v1`, {
        method: 'PATCH',
        headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ versionLabel: '1.0 محدث', versionNotes: 'نسخة أولى' }),
      });
      assert.equal(patchRes.status, 200);

      const slot1Res = await fetch(`${baseUrl}/api/admin/study-files/file-1/versions/v1/upload-slot`, {
        method: 'POST',
        headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ expectedSizeBytes: pdfBytes.length, expectedSha256: pdfSha }),
      });
      assert.equal(slot1Res.status, 201);
      const slot1 = (await slot1Res.json()) as { uploadSessionId: string; stagingKey: string };
      r2.putRaw(slot1.stagingKey, pdfBytes);

      const verify1Res = await fetch(`${baseUrl}/api/admin/study-files/file-1/versions/v1/verify`, {
        method: 'POST',
        headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ uploadSessionId: slot1.uploadSessionId }),
      });
      assert.equal(verify1Res.status, 200);

      const pub1Res = await fetch(`${baseUrl}/api/admin/study-files/file-1/versions/v1/publish`, {
        method: 'POST',
        headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      assert.equal(pub1Res.status, 200);
      assert.equal(sentPayloads.length, 1);
      assert.equal(sentPayloads[0].data.type, 'NEW_FILE');
      assert.equal(sentPayloads[0].data.fileId, 'file-1');
      assert.ok(!('notification' in sentPayloads[0]));
      const builtFcm1 = buildTopicFcmMessage(sentPayloads[0]);
      assert.equal('notification' in builtFcm1, false);
      assert.equal(builtFcm1.android?.priority, 'high');
      assert.ok(builtFcm1.data?.notificationId);
      assert.ok(builtFcm1.data?.title);
      assert.ok(builtFcm1.data?.body);

      // 2) Create v2, verify & publish -> NEW_VERSION
      await fetch(`${baseUrl}/api/admin/study-files/file-1/versions`, {
        method: 'POST',
        headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ customVersionId: 'v2', versionNumber: 2, versionLabel: '2.0' }),
      });
      const slot2Res = await fetch(`${baseUrl}/api/admin/study-files/file-1/versions/v2/upload-slot`, {
        method: 'POST',
        headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ expectedSizeBytes: pdfBytes.length, expectedSha256: pdfSha }),
      });
      const slot2 = (await slot2Res.json()) as { uploadSessionId: string; stagingKey: string };
      r2.putRaw(slot2.stagingKey, pdfBytes);
      await fetch(`${baseUrl}/api/admin/study-files/file-1/versions/v2/verify`, {
        method: 'POST',
        headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ uploadSessionId: slot2.uploadSessionId }),
      });
      const pub2Res = await fetch(`${baseUrl}/api/admin/study-files/file-1/versions/v2/publish`, {
        method: 'POST',
        headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      assert.equal(pub2Res.status, 200);
      assert.equal(sentPayloads.length, 2);
      assert.equal(sentPayloads[1].data.type, 'NEW_VERSION');
      const builtFcm2 = buildTopicFcmMessage(sentPayloads[1]);
      assert.equal('notification' in builtFcm2, false);
      assert.equal(builtFcm2.android?.priority, 'high');

      // 3) Admin broadcasts IMPORTANT_UPDATE
      const broadcastRes = await fetch(`${baseUrl}/api/admin/notifications/broadcast`, {
        method: 'POST',
        headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: 'إعلان امتحاني', body: 'تمت إضافة المراجعة النهائية' }),
      });
      assert.equal(broadcastRes.status, 200);
      const broadcastJson = (await broadcastRes.json()) as {
        sent: boolean;
        type: string;
        topic: string;
      };
      assert.equal(broadcastJson.sent, true);
      assert.equal(broadcastJson.type, 'IMPORTANT_UPDATE');
      assert.equal(broadcastJson.topic, 'haqibati_updates');
      assert.equal(sentPayloads.length, 3);
      const builtFcm3 = buildTopicFcmMessage(sentPayloads[2]);
      assert.equal('notification' in builtFcm3, false);
      assert.equal(builtFcm3.android?.priority, 'high');
      assert.equal(builtFcm3.data?.type, 'IMPORTANT_UPDATE');
      assert.equal(builtFcm3.data?.title, 'إعلان امتحاني');
      assert.equal(builtFcm3.data?.body, 'تمت إضافة المراجعة النهائية');

      // 4) Non-owner Admin MUST receive 403 INSUFFICIENT_ROLE on GET /api/admin/audit-logs
      const adminDeniedRes = await fetch(
        `${baseUrl}/api/admin/audit-logs?action=IMPORTANT_UPDATE_BROADCAST`,
        { headers: { Authorization: 'Bearer admin-token' } },
      );
      assert.equal(adminDeniedRes.status, 403);
      const adminDeniedJson = (await adminDeniedRes.json()) as { error: { code: string } };
      assert.equal(adminDeniedJson.error.code, 'INSUFFICIENT_ROLE');

      // 5) Owner reads filtered and full audit logs and still sees Admin-executed backend actions
      const filteredRes = await fetch(
        `${baseUrl}/api/admin/audit-logs?action=IMPORTANT_UPDATE_BROADCAST`,
        { headers: { Authorization: 'Bearer owner-token' } },
      );
      assert.equal(filteredRes.status, 200);
      const filtered = (await filteredRes.json()) as { items: Array<{ action: string; actorUid: string }> };
      assert.equal(filtered.items.length, 1);
      assert.equal(filtered.items[0].action, 'IMPORTANT_UPDATE_BROADCAST');
      assert.equal(filtered.items[0].actorUid, 'admin-1');

      const allRes = await fetch(`${baseUrl}/api/admin/audit-logs?limit=50`, {
        headers: { Authorization: 'Bearer owner-token' },
      });
      assert.equal(allRes.status, 200);
      const allLogs = (await allRes.json()) as { items: Array<{ action: string }> };
      assert.ok(allLogs.items.some((l) => l.action === 'VERSION_CREATED'));
      assert.ok(allLogs.items.some((l) => l.action === 'VERSION_UPDATED'));
      assert.ok(allLogs.items.some((l) => l.action === 'UPLOAD_SLOT_CREATED'));
      assert.ok(allLogs.items.some((l) => l.action === 'PDF_VERIFIED'));
      assert.ok(allLogs.items.some((l) => l.action === 'VERSION_PUBLISHED'));
    });
  });

  it('keeps publish succeeding and reports sent=false on broadcast when FCM dispatch fails or is unconfigured', async () => {
    const { r2, app } = setupHarness({ failNextPush: true });
    const pdfBytes = Buffer.from('%PDF-1.7\n%%EOF\n', 'utf8');
    const pdfSha = createHash('sha256').update(pdfBytes).digest('hex');

    await withServer(app, async (baseUrl) => {
      await fetch(`${baseUrl}/api/admin/study-files/file-1/versions`, {
        method: 'POST',
        headers: { Authorization: 'Bearer owner-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ customVersionId: 'v1', versionNumber: 1, versionLabel: '1.0' }),
      });
      const slotRes = await fetch(`${baseUrl}/api/admin/study-files/file-1/versions/v1/upload-slot`, {
        method: 'POST',
        headers: { Authorization: 'Bearer owner-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ expectedSizeBytes: pdfBytes.length, expectedSha256: pdfSha }),
      });
      const slot = (await slotRes.json()) as { uploadSessionId: string; stagingKey: string };
      r2.putRaw(slot.stagingKey, pdfBytes);
      await fetch(`${baseUrl}/api/admin/study-files/file-1/versions/v1/verify`, {
        method: 'POST',
        headers: { Authorization: 'Bearer owner-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ uploadSessionId: slot.uploadSessionId }),
      });

      // Publish must still return 200 even if FCM dispatcher throws
      const pubRes = await fetch(`${baseUrl}/api/admin/study-files/file-1/versions/v1/publish`, {
        method: 'POST',
        headers: { Authorization: 'Bearer owner-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      assert.equal(pubRes.status, 200);

      // Broadcast returns sent=false with non-empty reason instead of 500
      const broadcastRes = await fetch(`${baseUrl}/api/admin/notifications/broadcast`, {
        method: 'POST',
        headers: { Authorization: 'Bearer owner-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: 'اختبار', body: 'رسالة اختبار' }),
      });
      assert.equal(broadcastRes.status, 200);
      const body = (await broadcastRes.json()) as { sent: boolean; reason?: string };
      assert.equal(body.sent, false);
      assert.equal(body.reason, 'messaging/internal-error');
    });
  });

  it('reports explicit partial failure when setCustomUserClaims succeeds and revokeRefreshTokens fails without rolling back claims', async () => {
    const { staffManager, app } = setupHarness();
    staffManager.failNextRevoke = true;

    await withServer(app, async (baseUrl) => {
      const res = await fetch(`${baseUrl}/api/admin/staff/roles`, {
        method: 'POST',
        headers: {
          Authorization: 'Bearer owner-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ targetEmail: 'student@example.com', role: 'admin' }),
      });
      assert.equal(res.status, 502);
      const json = (await res.json()) as {
        error: {
          code: string;
          details: {
            claimsUpdated: boolean;
            tokensRevoked: boolean;
            auditRecorded: boolean;
            uid: string;
            role: string;
          };
        };
      };
      assert.equal(json.error.code, 'STAFF_ROLE_CLAIMS_UPDATED_TOKEN_REVOKE_FAILED');
      assert.deepEqual(json.error.details, {
        claimsUpdated: true,
        tokensRevoked: false,
        auditRecorded: true,
        uid: 'student-1',
        role: 'admin',
      });

      // Custom claims MUST stay updated (no automatic rollback) and existing cohort claim preserved
      const updatedUser = await staffManager.getUser('student-1');
      assert.equal(updatedUser.customClaims?.role, 'admin');
      assert.equal(updatedUser.customClaims?.cohort, '2026');
      assert.equal(staffManager.revokedUids.includes('student-1'), false);

      // Partial state is recorded in audit_logs for Owner visibility
      const logsRes = await fetch(`${baseUrl}/api/admin/audit-logs?action=ADMIN_ROLE_GRANTED`, {
        headers: { Authorization: 'Bearer owner-token' },
      });
      assert.equal(logsRes.status, 200);
      const logsJson = (await logsRes.json()) as { items: Array<{ summary: string }> };
      assert.equal(logsJson.items.length, 1);
      assert.ok(logsJson.items[0].summary.includes('refresh token revocation failed'));
    });
  });

  it('returns explicit audit failure error without claiming full success when mandatory audit write fails on staff role change', async () => {
    const { db, staffManager, app } = setupHarness();
    db.failAuditLogWrites = true;

    await withServer(app, async (baseUrl) => {
      const res = await fetch(`${baseUrl}/api/admin/staff/roles`, {
        method: 'POST',
        headers: {
          Authorization: 'Bearer owner-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ targetEmail: 'admin@example.com', role: 'none' }),
      });
      assert.equal(res.status, 500);
      const json = (await res.json()) as {
        error: {
          code: string;
          details: {
            claimsUpdated: boolean;
            tokensRevoked: boolean;
            auditRecorded: boolean;
            uid: string;
            role: string;
          };
        };
      };
      assert.equal(json.error.code, 'STAFF_ROLE_UPDATED_AUDIT_LOG_FAILED');
      assert.deepEqual(json.error.details, {
        claimsUpdated: true,
        tokensRevoked: true,
        auditRecorded: false,
        uid: 'admin-1',
        role: 'student',
      });

      const demoted = await staffManager.getUser('admin-1');
      assert.equal(demoted.customClaims?.role, undefined);
      assert.equal(demoted.customClaims?.campus, 'baghdad');
      assert.ok(staffManager.revokedUids.includes('admin-1'));
    });
  });
});
