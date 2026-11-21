import { randomUUID } from 'node:crypto';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import type { ServerConfig } from '../config/env.js';
import {
  verifyPdfStreamIntegrity,
  type R2StoragePort,
} from '../storage/r2Storage.js';
import type {
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

export interface UploadVerificationDeps {
  firestore: Firestore;
  r2Storage: R2StoragePort;
  config: ServerConfig;
  nowMs?: () => number;
  uuidGenerator?: () => string;
}

function getRefDocId(value: unknown): string | null {
  if (
    value &&
    typeof value === 'object' &&
    'id' in value &&
    typeof (value as { id?: unknown }).id === 'string'
  ) {
    return (value as { id: string }).id;
  }
  return null;
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

export class UploadVerificationService {
  private readonly firestore: Firestore;
  private readonly r2Storage: R2StoragePort;
  private readonly config: ServerConfig;
  private readonly nowMs: () => number;
  private readonly uuidGenerator: () => string;

  constructor(deps: UploadVerificationDeps) {
    this.firestore = deps.firestore;
    this.r2Storage = deps.r2Storage;
    this.config = deps.config;
    this.nowMs = deps.nowMs ?? (() => Date.now());
    this.uuidGenerator = deps.uuidGenerator ?? (() => randomUUID());
  }

  async createUploadSlot(params: {
    fileId: string;
    versionId: string;
    expectedSizeBytes: unknown;
    expectedSha256: unknown;
    actorUid: string;
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
      if (fileData.isArchived === true) {
        throw new ApiError(
          409,
          'FILE_ARCHIVED',
          `Cannot request upload slot while study file '${fileId}' is archived.`,
        );
      }

      if (!versionSnap.exists || !versionData) {
        throw new ApiError(
          404,
          'NOT_FOUND',
          `Study file version '${versionId}' does not exist.`,
        );
      }
      if (getRefDocId(versionData.fileId) !== fileId) {
        throw new ApiError(
          404,
          'NOT_FOUND',
          `Version '${versionId}' does not belong to file '${fileId}'.`,
        );
      }
      if (versionData.isArchived === true) {
        throw new ApiError(
          409,
          'VERSION_ARCHIVED',
          `Cannot upload PDF to archived version '${versionId}'.`,
        );
      }
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

    const uploadUrl = await this.r2Storage.createPresignedPutUrl({
      key: stagingKey,
      contentType: 'application/pdf',
      contentLength: expectedSizeBytes,
      expiresInSeconds: this.config.uploadSessionTtlSeconds,
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
        if (getRefDocId(versionData.fileId) !== fileId) {
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
        if (fileData.isArchived === true) {
          throw new ApiError(
            409,
            'FILE_ARCHIVED',
            `Study file '${fileId}' is archived.`,
          );
        }
        if (versionData.isArchived === true) {
          throw new ApiError(
            409,
            'VERSION_ARCHIVED',
            `Version '${versionId}' is archived.`,
          );
        }

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
          throw new ApiError(
            410,
            'SESSION_EXPIRED',
            'Upload session TTL expired before verification began.',
          );
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
          stagingKey: String(sessionData.stagingKey),
          expectedSizeBytes: Number(sessionData.expectedSizeBytes),
          expectedSha256: String(sessionData.expectedSha256).toLowerCase(),
        };
      },
    );

    if (outcome.kind === 'idempotent') {
      return outcome.response;
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
        if (
          !freshVersionSnap.exists ||
          !freshVersion ||
          freshVersion.isArchived === true ||
          freshVersion.isPublished === true
        ) {
          throw new ApiError(
            409,
            'CONFLICT_STALE_LOCK',
            'Target version state changed during verification.',
          );
        }
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

      // Phase E: Non-failing staging cleanup
      try {
        await this.r2Storage.deleteObject(stagingKey);
      } catch {
        // Non-fatal: R2 lifecycle policy cleans uploads-staging/
      }

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
            }
          });
        } catch {
          // Never mask primary error
        }
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
          } catch {
            // Best-effort orphan cleanup only
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
    if (preFileData.isArchived === true) {
      throw new ApiError(
        409,
        'FILE_ARCHIVED',
        `Cannot publish archived study file '${fileId}'.`,
      );
    }
    if (!preVersionSnap.exists || !preVersionData) {
      throw new ApiError(
        404,
        'NOT_FOUND',
        `Version '${versionId}' does not exist.`,
      );
    }
    if (preVersionData.isArchived === true) {
      throw new ApiError(
        409,
        'VERSION_ARCHIVED',
        `Cannot publish archived version '${versionId}'.`,
      );
    }
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
    if (head.contentLength !== null && head.contentLength !== preSizeBytes) {
      throw new ApiError(
        409,
        'SIZE_MISMATCH',
        'Stored R2 object size does not match verified Firestore metadata.',
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
      if (fileData.isArchived === true) {
        throw new ApiError(
          409,
          'FILE_ARCHIVED',
          `Cannot publish archived study file '${fileId}'.`,
        );
      }

      const targetSnap = allVersionsSnap.docs.find((d) => d.id === versionId);
      const targetData = targetSnap?.data();
      if (!targetSnap || !targetSnap.exists || !targetData) {
        throw new ApiError(
          404,
          'NOT_FOUND',
          `Version '${versionId}' does not exist.`,
        );
      }
      if (getRefDocId(targetData.fileId) !== fileId) {
        throw new ApiError(
          404,
          'NOT_FOUND',
          'Version parent reference mismatch.',
        );
      }
      if (targetData.isArchived === true) {
        throw new ApiError(
          409,
          'VERSION_ARCHIVED',
          `Cannot publish archived version '${versionId}'.`,
        );
      }

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
      const fileSizeBytes = targetData.fileSizeBytes;
      const versionNumber = targetData.versionNumber;
      const versionLabel = targetData.versionLabel;

      if (
        targetData.binaryVerificationStatus !== 'VERIFIED' ||
        sessionData.status !== 'CONSUMED' ||
        sessionData.fileId !== fileId ||
        sessionData.versionId !== versionId ||
        sessionData.finalObjectKey !== targetData.r2ObjectKey ||
        targetData.verifiedUploadSessionId !== sessionSnap.id ||
        targetData.verifiedAttemptId !== sessionData.activeAttemptId ||
        !isReliableR2ObjectKey(fileId, versionId, targetData.r2ObjectKey) ||
        typeof fileSizeBytes !== 'number' ||
        fileSizeBytes <= 0 ||
        typeof versionNumber !== 'number' ||
        versionNumber < 1 ||
        typeof versionLabel !== 'string' ||
        !versionLabel.trim() ||
        !/^[a-f0-9]{64}$/.test(sha256)
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

      return {
        status: 'PUBLISHED' as const,
        fileId,
        versionId,
        versionNumber,
        versionLabel: versionLabel.trim(),
        checksumSha256: sha256,
        fileSizeBytes,
        verifiedUploadSessionId: sessionSnap.id,
        verifiedAttemptId: String(targetData.verifiedAttemptId),
      };
    });

    return result;
  }
}
