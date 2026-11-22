import { randomUUID } from 'node:crypto';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import type { ServerConfig } from '../config/env.js';
import {
  verifyPdfStreamIntegrity,
  type R2StoragePort,
} from '../storage/r2Storage.js';
import type {
  AppUserRole,
  PublishVersionResponseDto,
  UploadSlotResponseDto,
  VerifyUploadResponseDto,
} from '../types/domain.js';
import { ApiError } from '../utils/errors.js';
import {
  isReliableR2ObjectKey,
  validateDocId,
  validatePositiveInt,
  validateSha256Hex,
  validateUuid,
} from '../validation/validators.js';
import type { AuditLogService } from './auditLogService.js';
import type { NotificationDispatchService } from './notificationDispatchService.js';

export interface UploadVerificationDeps {
  firestore: Firestore;
  r2Storage: R2StoragePort;
  config: ServerConfig;
  auditLogService?: AuditLogService;
  notificationDispatchService?: NotificationDispatchService;
  nowMs?: () => number;
  uuidGenerator?: () => string;
  warn?: (message: string) => void;
}

function hasMatchingParentFileRef(
  value: unknown,
  expectedFileId: string,
): boolean {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const candidate = value as {
    id?: unknown;
    path?: unknown;
    parent?: { id?: unknown } | null;
  };
  if (candidate.id !== expectedFileId) {
    return false;
  }
  if (
    candidate.parent &&
    typeof candidate.parent.id === 'string' &&
    candidate.parent.id !== 'study_files'
  ) {
    return false;
  }
  if (
    typeof candidate.path === 'string' &&
    candidate.path !== `study_files/${expectedFileId}`
  ) {
    return false;
  }
  return true;
}

function assertExplicitlyUnarchivedFile(
  fileData: Record<string, unknown>,
  fileId: string,
  actionDescription: string,
): void {
  if (fileData.isArchived === true) {
    throw new ApiError(
      409,
      'FILE_ARCHIVED',
      `Cannot ${actionDescription} while study file '${fileId}' is archived.`,
    );
  }
  if (fileData.isArchived !== false) {
    throw new ApiError(
      409,
      'ARCHIVE_STATE_UNSPECIFIED',
      `Cannot ${actionDescription} for study file '${fileId}' because its isArchived state is unspecified or invalid; review is required before proceeding.`,
    );
  }
}

function assertExplicitlyUnarchivedVersion(
  versionData: Record<string, unknown>,
  versionId: string,
  actionDescription: string,
): void {
  if (versionData.isArchived === true) {
    throw new ApiError(
      409,
      'VERSION_ARCHIVED',
      `Cannot ${actionDescription} while version '${versionId}' is archived.`,
    );
  }
  if (versionData.isArchived !== false) {
    throw new ApiError(
      409,
      'ARCHIVE_STATE_UNSPECIFIED',
      `Cannot ${actionDescription} for version '${versionId}' because its isArchived state is unspecified or invalid; review is required before proceeding.`,
    );
  }
}

function timestampToMs(value: unknown): number {
  if (value instanceof Timestamp) return value.toMillis();
  if (
    value &&
    typeof value === 'object' &&
    'toMillis' in value &&
    typeof (value as { toMillis?: unknown }).toMillis === 'function'
  ) {
    return (value as { toMillis: () => number }).toMillis();
  }
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  return 0;
}

export function buildStagingKey(
  fileId: string,
  versionId: string,
  sessionId: string,
): string {
  return `uploads-staging/${fileId}/${versionId}/${sessionId}.pdf`;
}

export function buildFinalImmutableKey(params: {
  fileId: string;
  versionId: string;
  sha256: string;
  sessionId: string;
  attemptId: string;
}): string {
  const shaPrefix16 = params.sha256.trim().toLowerCase().slice(0, 16);
  return `study-files/${params.fileId}/versions/${params.versionId}/${shaPrefix16}_${params.sessionId}_${params.attemptId}.pdf`;
}

const STAGING_KEY_PREFIX = 'uploads-staging/';
const FINAL_KEY_PREFIX = 'study-files/';

function formatSafeCleanupError(err: unknown): string {
  if (err instanceof ApiError) {
    return `${err.name}:${err.code}`;
  }
  if (err instanceof Error) {
    const rawName = err.name || 'Error';
    const rawCode =
      'code' in err && typeof (err as { code?: unknown }).code === 'string'
        ? `:${String((err as { code?: unknown }).code)}`
        : '';
    // Strip URLs, query parameters (e.g. X-Amz-Signature), and Bearer tokens before logging.
    const sanitizedMsg = err.message
      .replace(/https?:\/\/\S+/gi, '[redacted-url]')
      .replace(/X-Amz-[A-Za-z0-9-]+=[^&\s]+/gi, 'X-Amz-[redacted]')
      .replace(/Bearer\s+\S+/gi, 'Bearer [redacted]')
      .slice(0, 160);
    return `${rawName}${rawCode} (${sanitizedMsg})`;
  }
  return 'Unknown cleanup error';
}

export class UploadVerificationService {
  private readonly firestore: Firestore;
  private readonly r2Storage: R2StoragePort;
  private readonly config: ServerConfig;
  private readonly auditLogService?: AuditLogService;
  private readonly notificationDispatchService?: NotificationDispatchService;
  private readonly nowMs: () => number;
  private readonly uuidGenerator: () => string;
  private readonly warn: (message: string) => void;

  constructor(deps: UploadVerificationDeps) {
    this.firestore = deps.firestore;
    this.r2Storage = deps.r2Storage;
    this.config = deps.config;
    this.auditLogService = deps.auditLogService;
    this.notificationDispatchService = deps.notificationDispatchService;
    this.nowMs = deps.nowMs ?? (() => Date.now());
    this.uuidGenerator = deps.uuidGenerator ?? (() => randomUUID());
    this.warn = deps.warn ?? ((message: string) => console.warn(message));
  }

  private async safeDeleteStagingObject(params: {
    stagingKey: string;
    fileId: string;
    versionId: string;
    sessionId: string;
    phase: string;
  }): Promise<void> {
    const key = params.stagingKey.trim();
    // Strict isolation guard: staging cleanup may NEVER target final study-files/ keys.
    if (
      !key.startsWith(STAGING_KEY_PREFIX) ||
      key.startsWith(FINAL_KEY_PREFIX) ||
      key !== buildStagingKey(params.fileId, params.versionId, params.sessionId)
    ) {
      this.warn(
        `[r2-staging-cleanup] Refusing unsafe staging delete outside isolated prefix: phase=${params.phase} fileId=${params.fileId} versionId=${params.versionId} sessionId=${params.sessionId}`,
      );
      return;
    }

    try {
      await this.r2Storage.deleteObject(key);
    } catch (err) {
      this.warn(
        `[r2-staging-cleanup] Non-fatal staging delete failure (24h uploads-staging/ R2 lifecycle will collect): phase=${params.phase} fileId=${params.fileId} versionId=${params.versionId} sessionId=${params.sessionId} key=${key} cause=${formatSafeCleanupError(err)}`,
      );
    }
  }

  async createUploadSlot(params: {
    fileId: string;
    versionId: string;
    expectedSizeBytes: unknown;
    expectedSha256: unknown;
    actorUid: string;
    actorRole?: AppUserRole | string;
  }): Promise<UploadSlotResponseDto> {
    if (!this.r2Storage.isConfigured()) {
      throw new ApiError(
        503,
        'R2_NOT_CONFIGURED',
        'Cloudflare R2 storage is not configured yet.',
      );
    }

    const fileId = validateDocId(params.fileId, 'fileId');
    const versionId = validateDocId(params.versionId, 'versionId');
    const expectedSizeBytes = validatePositiveInt(
      params.expectedSizeBytes,
      'expectedSizeBytes',
      this.config.maxPdfBytes,
    );
    const expectedSha256 = validateSha256Hex(params.expectedSha256, 'expectedSha256');

    const nowEpoch = this.nowMs();
    const nowTs = Timestamp.fromMillis(nowEpoch);
    const expiresEpoch = nowEpoch + this.config.uploadSessionTtlSeconds * 1000;
    const expiresTs = Timestamp.fromMillis(expiresEpoch);
    const sessionId = this.uuidGenerator().toLowerCase();
    const stagingKey = buildStagingKey(fileId, versionId, sessionId);

    const fileRef = this.firestore.collection('study_files').doc(fileId);
    const versionRef = fileRef.collection('versions').doc(versionId);
    const sessionsCol = this.firestore.collection('upload_sessions');
    const newSessionRef = sessionsCol.doc(sessionId);

    const supersededSessions: Array<{ sessionId: string; stagingKey: string }> =
      [];

    await this.firestore.runTransaction(async (tx) => {
      const [fileSnap, versionSnap, existingSessionsSnap] = await Promise.all([
        tx.get(fileRef),
        tx.get(versionRef),
        tx.get(
          sessionsCol
            .where('fileId', '==', fileId)
            .where('versionId', '==', versionId),
        ),
      ]);

      const fileData = fileSnap.data();
      const versionData = versionSnap.data();

      if (!fileSnap.exists || !fileData) {
        throw new ApiError(404, 'NOT_FOUND', `Study file '${fileId}' does not exist.`);
      }
      assertExplicitlyUnarchivedFile(fileData, fileId, 'request upload slot');

      if (!versionSnap.exists || !versionData) {
        throw new ApiError(
          404,
          'NOT_FOUND',
          `Study file version '${versionId}' does not exist.`,
        );
      }
      if (!hasMatchingParentFileRef(versionData.fileId, fileId)) {
        throw new ApiError(
          404,
          'NOT_FOUND',
          `Version '${versionId}' does not belong to file '${fileId}'.`,
        );
      }
      assertExplicitlyUnarchivedVersion(versionData, versionId, 'request upload slot');
      if (versionData.isPublished === true) {
        throw new ApiError(
          409,
          'VERSION_ALREADY_PUBLISHED',
          `Version '${versionId}' is already published and immutable.`,
        );
      }
      if (versionData.binaryVerificationStatus === 'VERIFIED') {
        throw new ApiError(
          409,
          'VERSION_ALREADY_VERIFIED',
          `Version '${versionId}' already has a verified PDF binary.`,
        );
      }

      for (const existingDoc of existingSessionsSnap.docs) {
        const sData = existingDoc.data();
        if (sData.status === 'VERIFYING') {
          const lockExp = timestampToMs(sData.verifyingExpiresAt);
          if (lockExp > nowEpoch) {
            throw new ApiError(
              409,
              'VERIFY_IN_PROGRESS',
              'A verification attempt is currently active for this version.',
            );
          }
        }
        if (sData.status === 'PENDING' || sData.status === 'VERIFYING') {
          tx.update(existingDoc.ref, {
            status: 'SUPERSEDED',
            supersededAt: nowTs,
            updatedByUid: params.actorUid,
            updatedAt: nowTs,
          });
          if (
            typeof sData.stagingKey === 'string' &&
            sData.stagingKey.trim().length > 0
          ) {
            supersededSessions.push({
              sessionId: existingDoc.id,
              stagingKey: sData.stagingKey.trim(),
            });
          }
        }
      }

      tx.set(newSessionRef, {
        sessionId,
        fileId,
        versionId,
        stagingKey,
        status: 'PENDING',
        expectedSizeBytes,
        expectedSha256,
        attemptCount: 0,
        activeAttemptId: null,
        lockToken: null,
        finalObjectKey: null,
        verifyingExpiresAt: null,
        consumedAt: null,
        supersededAt: null,
        expiresAt: expiresTs,
        createdByUid: params.actorUid,
        updatedByUid: params.actorUid,
        createdAt: nowTs,
        updatedAt: nowTs,
      });

      tx.update(versionRef, {
        activeUploadSessionId: sessionId,
        binaryVerificationStatus: 'PENDING_UPLOAD',
        updatedByUid: params.actorUid,
        updatedAt: nowTs,
      });
    });

    for (const oldSession of supersededSessions) {
      await this.safeDeleteStagingObject({
        stagingKey: oldSession.stagingKey,
        fileId,
        versionId,
        sessionId: oldSession.sessionId,
        phase: 'createUploadSlot:superseded',
      });
    }

    const uploadUrl = await this.r2Storage.createPresignedPutUrl({
      key: stagingKey,
      contentType: 'application/pdf',
      contentLength: expectedSizeBytes,
      expiresInSeconds: this.config.uploadSessionTtlSeconds,
    });

    await this.auditLogService?.recordBestEffort({
      action: 'UPLOAD_SLOT_CREATED',
      actorUid: params.actorUid,
      actorRole: params.actorRole ?? 'admin',
      targetCollection: 'upload_sessions',
      targetId: sessionId,
      summary: `Issued upload slot ${sessionId} for ${fileId}/${versionId}`,
      source: 'backend',
    });

    return {
      uploadSessionId: sessionId,
      fileId,
      versionId,
      stagingKey,
      uploadUrl,
      method: 'PUT',
      requiredHeaders: {
        'Content-Type': 'application/pdf',
        'Content-Length': String(expectedSizeBytes),
      },
      expiresAtEpochMillis: expiresEpoch,
    };
  }

  async verifyUploadedPdf(params: {
    fileId: string;
    versionId: string;
    uploadSessionId: unknown;
    actorUid: string;
    actorRole?: AppUserRole | string;
  }): Promise<VerifyUploadResponseDto> {
    if (!this.r2Storage.isConfigured()) {
      throw new ApiError(
        503,
        'R2_NOT_CONFIGURED',
        'Cloudflare R2 storage is not configured yet.',
      );
    }

    const fileId = validateDocId(params.fileId, 'fileId');
    const versionId = validateDocId(params.versionId, 'versionId');
    const sessionId = validateUuid(params.uploadSessionId, 'uploadSessionId');

    const fileRef = this.firestore.collection('study_files').doc(fileId);
    const versionRef = fileRef.collection('versions').doc(versionId);
    const sessionRef = this.firestore.collection('upload_sessions').doc(sessionId);

    // Phase A: Acquire Lock Transaction
    const lockEpoch = this.nowMs();
    const lockNowTs = Timestamp.fromMillis(lockEpoch);
    const attemptId = this.uuidGenerator().toLowerCase();
    const lockToken = attemptId;
    const verifyingExpiresTs = Timestamp.fromMillis(
      lockEpoch + this.config.verifyLockTtlSeconds * 1000,
    );

    type AcquireOutcome =
      | {
          kind: 'idempotent';
          response: VerifyUploadResponseDto;
        }
      | {
          kind: 'expired';
          stagingKey: string;
        }
      | {
          kind: 'acquired';
          stagingKey: string;
          expectedSizeBytes: number;
          expectedSha256: string;
        };

    const outcome = await this.firestore.runTransaction(
      async (tx): Promise<AcquireOutcome> => {
        const [fileSnap, versionSnap, sessionSnap] = await Promise.all([
          tx.get(fileRef),
          tx.get(versionRef),
          tx.get(sessionRef),
        ]);

        const fileData = fileSnap.data();
        const versionData = versionSnap.data();
        const sessionData = sessionSnap.data();

        if (!fileSnap.exists || !fileData) {
          throw new ApiError(404, 'NOT_FOUND', `Study file '${fileId}' does not exist.`);
        }
        if (!versionSnap.exists || !versionData) {
          throw new ApiError(
            404,
            'NOT_FOUND',
            `Version '${versionId}' does not exist.`,
          );
        }
        if (
          !hasMatchingParentFileRef(versionData.fileId, fileId)
        ) {
          throw new ApiError(
            404,
            'NOT_FOUND',
            `Version '${versionId}' does not belong to file '${fileId}'.`,
          );
        }
        if (!sessionSnap.exists || !sessionData) {
          throw new ApiError(
            404,
            'NOT_FOUND',
            `Upload session '${sessionId}' does not exist.`,
          );
        }
        if (
          sessionData.fileId !== fileId ||
          sessionData.versionId !== versionId
        ) {
          throw new ApiError(
            409,
            'SESSION_MISMATCH',
            'Upload session does not match the target file/version.',
          );
        }
        assertExplicitlyUnarchivedFile(fileData, fileId, 'verify uploaded PDF');
        assertExplicitlyUnarchivedVersion(
          versionData,
          versionId,
          'verify uploaded PDF',
        );

        // Idempotent retry check for already consumed & verified session
        if (
          sessionData.status === 'CONSUMED' &&
          versionData.binaryVerificationStatus === 'VERIFIED' &&
          versionData.verifiedUploadSessionId === sessionId &&
          typeof sessionData.finalObjectKey === 'string' &&
          sessionData.finalObjectKey === versionData.r2ObjectKey &&
          typeof versionData.verifiedAttemptId === 'string' &&
          versionData.verifiedAttemptId === sessionData.activeAttemptId
        ) {
          return {
            kind: 'idempotent',
            response: {
              status: 'VERIFIED',
              idempotent: true,
              fileId,
              versionId,
              uploadSessionId: sessionId,
              attemptId: versionData.verifiedAttemptId,
              r2ObjectKey: versionData.r2ObjectKey,
              fileSizeBytes: versionData.fileSizeBytes,
              checksumSha256: versionData.checksumSha256,
            },
          };
        }

        if (versionData.isPublished === true) {
          throw new ApiError(
            409,
            'VERSION_ALREADY_PUBLISHED',
            `Version '${versionId}' is already published.`,
          );
        }
        if (versionData.binaryVerificationStatus === 'VERIFIED') {
          throw new ApiError(
            409,
            'VERSION_ALREADY_VERIFIED',
            `Version '${versionId}' is already verified under another session.`,
          );
        }
        if (sessionData.status === 'SUPERSEDED') {
          throw new ApiError(
            409,
            'SESSION_SUPERSEDED',
            'Upload session was superseded by a newer upload slot.',
          );
        }
        if (sessionData.status === 'FAILED') {
          throw new ApiError(
            409,
            'SESSION_FAILED',
            'Upload session previously failed; request a fresh upload slot.',
          );
        }
        if (sessionData.status === 'CONSUMED') {
          throw new ApiError(
            409,
            'PROOF_MISMATCH',
            'Upload session is already consumed without matching verified version proof.',
          );
        }
        if (sessionData.status === 'EXPIRED') {
          throw new ApiError(
            410,
            'SESSION_EXPIRED',
            'Upload session has expired.',
          );
        }

        const sessionExpiresAtMs = timestampToMs(sessionData.expiresAt);
        if (sessionData.status === 'PENDING' && sessionExpiresAtMs <= lockEpoch) {
          tx.update(sessionRef, {
            status: 'EXPIRED',
            updatedByUid: params.actorUid,
            updatedAt: lockNowTs,
          });
          return {
            kind: 'expired',
            stagingKey:
              typeof sessionData.stagingKey === 'string'
                ? sessionData.stagingKey.trim()
                : buildStagingKey(fileId, versionId, sessionId),
          };
        }

        if (sessionData.status === 'VERIFYING') {
          const verifyingExpiresMs = timestampToMs(sessionData.verifyingExpiresAt);
          if (verifyingExpiresMs > lockEpoch) {
            throw new ApiError(
              409,
              'VERIFY_IN_PROGRESS',
              'Verification lock is currently held by another worker.',
            );
          }
        } else if (sessionData.status !== 'PENDING') {
          throw new ApiError(
            409,
            'VALIDATION_ERROR',
            `Unsupported upload session state '${String(sessionData.status)}'.`,
          );
        }

        const stagingKey =
          typeof sessionData.stagingKey === 'string'
            ? sessionData.stagingKey.trim()
            : '';
        const expectedSizeBytes = sessionData.expectedSizeBytes;
        const expectedSha256 =
          typeof sessionData.expectedSha256 === 'string'
            ? sessionData.expectedSha256.trim().toLowerCase()
            : '';
        if (
          stagingKey.length === 0 ||
          stagingKey !== buildStagingKey(fileId, versionId, sessionId) ||
          typeof expectedSizeBytes !== 'number' ||
          !Number.isInteger(expectedSizeBytes) ||
          expectedSizeBytes <= 0 ||
          expectedSizeBytes > this.config.maxPdfBytes ||
          !/^[a-f0-9]{64}$/.test(expectedSha256)
        ) {
          throw new ApiError(
            409,
            'PROOF_MISMATCH',
            'Upload session metadata is invalid or malformed.',
          );
        }

        const prevAttemptCount =
          typeof sessionData.attemptCount === 'number'
            ? sessionData.attemptCount
            : 0;

        tx.update(sessionRef, {
          status: 'VERIFYING',
          activeAttemptId: attemptId,
          lockToken,
          attemptCount: prevAttemptCount + 1,
          verifyingExpiresAt: verifyingExpiresTs,
          updatedByUid: params.actorUid,
          updatedAt: lockNowTs,
        });

        return {
          kind: 'acquired',
          stagingKey,
          expectedSizeBytes,
          expectedSha256,
        };
      },
    );

    if (outcome.kind === 'idempotent') {
      return outcome.response;
    }

    if (outcome.kind === 'expired') {
      await this.safeDeleteStagingObject({
        stagingKey: outcome.stagingKey,
        fileId,
        versionId,
        sessionId,
        phase: 'verifyUploadedPdf:expired',
      });
      throw new ApiError(
        410,
        'SESSION_EXPIRED',
        'Upload session TTL expired before verification began.',
      );
    }

    const { stagingKey, expectedSizeBytes, expectedSha256 } = outcome;
    const attemptFinalKey = buildFinalImmutableKey({
      fileId,
      versionId,
      sha256: expectedSha256,
      sessionId,
      attemptId,
    });

    let finalObjectCreated = false;

    try {
      // Phase B: Stream stagingKey, validate bytes & hash, capture stagingETag, conditional CopyObject
      const stagingObject = await this.r2Storage.getObjectStreamWithETag(stagingKey);
      if (!stagingObject.etag) {
        throw new ApiError(
          412,
          'ETAG_MISMATCH',
          'Staging object did not expose an ETag for conditional copy.',
        );
      }

      await verifyPdfStreamIntegrity(stagingObject.stream, {
        expectedSizeBytes,
        expectedSha256,
        maxPdfBytes: this.config.maxPdfBytes,
      });

      await this.r2Storage.copyObjectIfMatch({
        sourceKey: stagingKey,
        destinationKey: attemptFinalKey,
        expectedETag: stagingObject.etag,
        contentType: 'application/pdf',
      });
      finalObjectCreated = true;

      // Phase C: Stream final immutable object bytes itself & re-verify SHA-256 + %PDF- + size
      const finalObject = await this.r2Storage.getObjectStreamWithETag(attemptFinalKey);
      await verifyPdfStreamIntegrity(finalObject.stream, {
        expectedSizeBytes,
        expectedSha256,
        maxPdfBytes: this.config.maxPdfBytes,
      });

      // Phase D: Fencing Commit Transaction verifying lock ownership (activeAttemptId + lockToken)
      const commitTs = Timestamp.fromMillis(this.nowMs());
      await this.firestore.runTransaction(async (tx) => {
        const [freshSessionSnap, freshVersionSnap, freshFileSnap] =
          await Promise.all([
            tx.get(sessionRef),
            tx.get(versionRef),
            tx.get(fileRef),
          ]);

        const freshFile = freshFileSnap.data();
        const freshVersion = freshVersionSnap.data();
        const freshSession = freshSessionSnap.data();

        if (!freshFileSnap.exists || !freshFile || freshFile.isArchived === true) {
          throw new ApiError(
            409,
            'FILE_ARCHIVED',
            'Study file is missing or archived during verification commit.',
          );
        }
        if (freshFile.isArchived !== false) {
          throw new ApiError(
            409,
            'ARCHIVE_STATE_UNSPECIFIED',
            `Study file '${fileId}' has unspecified or invalid isArchived state during verification commit; review is required.`,
          );
        }
        if (!freshVersionSnap.exists || !freshVersion || freshVersion.isPublished === true) {
          throw new ApiError(
            409,
            'CONFLICT_STALE_LOCK',
            'Target version state changed during verification.',
          );
        }
        assertExplicitlyUnarchivedVersion(
          freshVersion,
          versionId,
          'commit verified PDF',
        );
        if (
          !freshSessionSnap.exists ||
          !freshSession ||
          freshSession.status !== 'VERIFYING' ||
          freshSession.activeAttemptId !== attemptId ||
          freshSession.lockToken !== lockToken
        ) {
          throw new ApiError(
            409,
            'CONFLICT_STALE_LOCK',
            'Fencing lock ownership lost before commit; aborting without modifying version.',
          );
        }
        if (freshVersion.binaryVerificationStatus === 'VERIFIED') {
          throw new ApiError(
            409,
            'VERSION_ALREADY_VERIFIED',
            'Target version was already verified by another attempt.',
          );
        }

        tx.update(versionRef, {
          r2ObjectKey: attemptFinalKey,
          fileSizeBytes: expectedSizeBytes,
          checksumSha256: expectedSha256,
          binaryVerificationStatus: 'VERIFIED',
          verifiedUploadSessionId: sessionId,
          verifiedAttemptId: attemptId,
          binaryVerifiedAt: commitTs,
          updatedByUid: params.actorUid,
          updatedAt: commitTs,
        });

        tx.update(sessionRef, {
          status: 'CONSUMED',
          finalObjectKey: attemptFinalKey,
          consumedAt: commitTs,
          updatedByUid: params.actorUid,
          updatedAt: commitTs,
        });
      });

      // Phase E: Non-failing staging cleanup with safe warning log on failure
      await this.safeDeleteStagingObject({
        stagingKey,
        fileId,
        versionId,
        sessionId,
        phase: 'verifyUploadedPdf:consumed',
      });

      await this.auditLogService?.recordBestEffort({
        action: 'PDF_VERIFIED',
        actorUid: params.actorUid,
        actorRole: params.actorRole ?? 'admin',
        targetCollection: `study_files/${fileId}/versions`,
        targetId: versionId,
        summary: `Verified PDF (${expectedSizeBytes} bytes) for ${fileId}/${versionId}`,
        source: 'backend',
      });

      return {
        status: 'VERIFIED',
        idempotent: false,
        fileId,
        versionId,
        uploadSessionId: sessionId,
        attemptId,
        r2ObjectKey: attemptFinalKey,
        fileSizeBytes: expectedSizeBytes,
        checksumSha256: expectedSha256,
      };
    } catch (err) {
      // Phase F: Guarded failure transition & non-destructive final object cleanup
      const isLockConflict =
        err instanceof ApiError && err.code === 'CONFLICT_STALE_LOCK';
      let markedSessionFailed = false;

      if (!isLockConflict) {
        try {
          const failTs = Timestamp.fromMillis(this.nowMs());
          await this.firestore.runTransaction(async (tx) => {
            const snap = await tx.get(sessionRef);
            const data = snap.data();
            if (
              snap.exists &&
              data &&
              data.status === 'VERIFYING' &&
              data.activeAttemptId === attemptId &&
              data.lockToken === lockToken
            ) {
              tx.update(sessionRef, {
                status: 'FAILED',
                updatedByUid: params.actorUid,
                updatedAt: failTs,
              });
              markedSessionFailed = true;
            }
          });
        } catch {
          // Never mask primary error
        }
      }

      if (!isLockConflict && markedSessionFailed) {
        // Safe because this worker held the active lock and committed terminal FAILED state.
        await this.safeDeleteStagingObject({
          stagingKey,
          fileId,
          versionId,
          sessionId,
          phase: 'verifyUploadedPdf:failed',
        });
      }

      if (finalObjectCreated) {
        // CRITICAL SAFETY GUARD: Never delete attemptFinalKey if Firestore read fails or state is uncertain.
        let safeToDelete = false;
        try {
          const [freshSessionSnap, freshVersionSnap] = await Promise.all([
            sessionRef.get(),
            versionRef.get(),
          ]);
          if (freshSessionSnap.exists && freshVersionSnap.exists) {
            const freshSession = freshSessionSnap.data()!;
            const freshVersion = freshVersionSnap.data()!;
            if (
              freshSession.finalObjectKey !== attemptFinalKey &&
              freshVersion.r2ObjectKey !== attemptFinalKey &&
              freshVersion.verifiedAttemptId !== attemptId &&
              freshVersion.isPublished !== true
            ) {
              safeToDelete = true;
            }
          }
        } catch {
          safeToDelete = false;
        }

        if (safeToDelete) {
          try {
            await this.r2Storage.deleteObject(attemptFinalKey);
          } catch (cleanupErr) {
            this.warn(
              `[r2-staging-cleanup] Non-fatal unbound final orphan cleanup failure: fileId=${fileId} versionId=${versionId} key=${attemptFinalKey} cause=${formatSafeCleanupError(cleanupErr)}`,
            );
          }
        }
      }

      throw err;
    }
  }

  async publishVersion(params: {
    fileId: string;
    versionId: string;
    actorUid: string;
    actorRole?: AppUserRole | string;
  }): Promise<PublishVersionResponseDto> {
    if (!this.r2Storage.isConfigured()) {
      throw new ApiError(
        503,
        'R2_NOT_CONFIGURED',
        'Cloudflare R2 storage is not configured yet.',
      );
    }

    const fileId = validateDocId(params.fileId, 'fileId');
    const versionId = validateDocId(params.versionId, 'versionId');

    const fileRef = this.firestore.collection('study_files').doc(fileId);
    const versionsCol = fileRef.collection('versions');
    const targetVersionRef = versionsCol.doc(versionId);

    const [preFileSnap, preVersionSnap] = await Promise.all([
      fileRef.get(),
      targetVersionRef.get(),
    ]);
    const preFileData = preFileSnap.data();
    const preVersionData = preVersionSnap.data();

    if (!preFileSnap.exists || !preFileData) {
      throw new ApiError(404, 'NOT_FOUND', `Study file '${fileId}' does not exist.`);
    }
    assertExplicitlyUnarchivedFile(preFileData, fileId, 'publish version');
    if (!preVersionSnap.exists || !preVersionData) {
      throw new ApiError(
        404,
        'NOT_FOUND',
        `Version '${versionId}' does not exist.`,
      );
    }
    if (!hasMatchingParentFileRef(preVersionData.fileId, fileId)) {
      throw new ApiError(
        404,
        'NOT_FOUND',
        `Version '${versionId}' does not belong to file '${fileId}'.`,
      );
    }
    assertExplicitlyUnarchivedVersion(preVersionData, versionId, 'publish version');
    if (preVersionData.binaryVerificationStatus !== 'VERIFIED') {
      throw new ApiError(
        409,
        'PROOF_MISSING',
        'Cannot publish version before binary verification succeeds.',
      );
    }

    const preR2Key = preVersionData.r2ObjectKey;
    const preSizeBytes = preVersionData.fileSizeBytes;
    const preSessionId = preVersionData.verifiedUploadSessionId;
    const preAttemptId = preVersionData.verifiedAttemptId;

    if (
      !isReliableR2ObjectKey(fileId, versionId, preR2Key) ||
      typeof preSizeBytes !== 'number' ||
      !Number.isInteger(preSizeBytes) ||
      preSizeBytes <= 0 ||
      typeof preSessionId !== 'string' ||
      !preSessionId ||
      typeof preAttemptId !== 'string' ||
      !preAttemptId
    ) {
      throw new ApiError(
        409,
        'PROOF_MISMATCH',
        'Version is missing reliable R2 proof or session binding.',
      );
    }

    const head = await this.r2Storage.headObject(preR2Key);
    if (!head.exists) {
      throw new ApiError(
        409,
        'R2_OBJECT_MISSING',
        'Verified R2 object is missing from storage.',
      );
    }
    if (head.contentLength !== preSizeBytes) {
      throw new ApiError(
        409,
        'SIZE_MISMATCH',
        'Stored R2 object size is missing or does not match verified Firestore metadata.',
      );
    }

    const sessionRef = this.firestore.collection('upload_sessions').doc(preSessionId);
    const publishTs = Timestamp.fromMillis(this.nowMs());

    const result = await this.firestore.runTransaction(async (tx) => {
      const [fileSnap, allVersionsSnap, sessionSnap] = await Promise.all([
        tx.get(fileRef),
        tx.get(versionsCol),
        tx.get(sessionRef),
      ]);

      const fileData = fileSnap.data();
      if (!fileSnap.exists || !fileData) {
        throw new ApiError(404, 'NOT_FOUND', `Study file '${fileId}' does not exist.`);
      }
      assertExplicitlyUnarchivedFile(fileData, fileId, 'publish version');

      const targetSnap = allVersionsSnap.docs.find((d) => d.id === versionId);
      const targetData = targetSnap?.data();
      if (!targetSnap || !targetSnap.exists || !targetData) {
        throw new ApiError(
          404,
          'NOT_FOUND',
          `Version '${versionId}' does not exist.`,
        );
      }
      if (!hasMatchingParentFileRef(targetData.fileId, fileId)) {
        throw new ApiError(
          404,
          'NOT_FOUND',
          'Version parent reference mismatch.',
        );
      }
      assertExplicitlyUnarchivedVersion(targetData, versionId, 'publish version');

      const sessionData = sessionSnap.data();
      if (!sessionSnap.exists || !sessionData) {
        throw new ApiError(
          409,
          'PROOF_MISMATCH',
          'Bound upload session for verified version does not exist.',
        );
      }

      const sha256 =
        typeof targetData.checksumSha256 === 'string'
          ? targetData.checksumSha256.trim().toLowerCase()
          : '';
      const sessionExpectedSha256 =
        typeof sessionData.expectedSha256 === 'string'
          ? sessionData.expectedSha256.trim().toLowerCase()
          : '';
      const fileSizeBytes = targetData.fileSizeBytes;
      const versionNumber = targetData.versionNumber;
      const versionLabel = targetData.versionLabel;

      if (
        targetData.binaryVerificationStatus !== 'VERIFIED' ||
        sessionData.status !== 'CONSUMED' ||
        sessionData.fileId !== fileId ||
        sessionData.versionId !== versionId ||
        targetData.r2ObjectKey !== preR2Key ||
        sessionData.finalObjectKey !== targetData.r2ObjectKey ||
        targetData.verifiedUploadSessionId !== preSessionId ||
        targetData.verifiedUploadSessionId !== sessionSnap.id ||
        targetData.verifiedAttemptId !== preAttemptId ||
        targetData.verifiedAttemptId !== sessionData.activeAttemptId ||
        !isReliableR2ObjectKey(fileId, versionId, targetData.r2ObjectKey) ||
        typeof fileSizeBytes !== 'number' ||
        !Number.isInteger(fileSizeBytes) ||
        fileSizeBytes !== preSizeBytes ||
        sessionData.expectedSizeBytes !== fileSizeBytes ||
        typeof versionNumber !== 'number' ||
        versionNumber < 1 ||
        typeof versionLabel !== 'string' ||
        !versionLabel.trim() ||
        !/^[a-f0-9]{64}$/.test(sha256) ||
        sessionExpectedSha256 !== sha256
      ) {
        throw new ApiError(
          409,
          'PROOF_MISMATCH',
          'Publish cross-checks failed between version, upload session, and R2 proof.',
        );
      }

      for (const otherDoc of allVersionsSnap.docs) {
        if (otherDoc.id === versionId) continue;
        if (otherDoc.data().isPublished === true) {
          tx.update(otherDoc.ref, {
            isPublished: false,
            updatedByUid: params.actorUid,
            updatedAt: publishTs,
          });
        }
      }

      tx.update(targetSnap.ref, {
        isPublished: true,
        publishedAt: publishTs,
        updatedByUid: params.actorUid,
        updatedAt: publishTs,
      });

      tx.update(fileRef, {
        isPublished: true,
        activeVersionId: versionId,
        activeVersionNumber: versionNumber,
        activeVersionLabel: versionLabel.trim(),
        activeVersionPublishedAt: publishTs,
        updatedByUid: params.actorUid,
        updatedAt: publishTs,
      });

      const hadPriorActiveVersion =
        typeof fileData.activeVersionId === 'string' &&
        fileData.activeVersionId.trim().length > 0 &&
        fileData.activeVersionId.trim() !== versionId;
      const resolvedFileTitle =
        (typeof fileData.title === 'string' && fileData.title.trim()) ||
        (typeof fileData.nameAr === 'string' && fileData.nameAr.trim()) ||
        (typeof fileData.name === 'string' && fileData.name.trim()) ||
        fileId;

      return {
        response: {
          status: 'PUBLISHED' as const,
          fileId,
          versionId,
          versionNumber,
          versionLabel: versionLabel.trim(),
          checksumSha256: sha256,
          fileSizeBytes,
          verifiedUploadSessionId: sessionSnap.id,
          verifiedAttemptId: String(targetData.verifiedAttemptId),
        },
        isNewFile: !hadPriorActiveVersion && Number(versionNumber) === 1,
        resolvedFileTitle,
      };
    });

    await this.auditLogService?.recordBestEffort({
      action: 'VERSION_PUBLISHED',
      actorUid: params.actorUid,
      actorRole: params.actorRole ?? 'admin',
      targetCollection: `study_files/${fileId}/versions`,
      targetId: versionId,
      summary: `Published ${result.resolvedFileTitle} v${result.response.versionNumber} (${result.response.versionLabel})`,
      source: 'backend',
    });

    await this.notificationDispatchService?.notifyPublishedVersion({
      fileId,
      fileTitle: result.resolvedFileTitle,
      versionId,
      versionNumber: result.response.versionNumber,
      versionLabel: result.response.versionLabel,
      isNewFile: result.isNewFile,
    });

    return result.response;
  }
}
