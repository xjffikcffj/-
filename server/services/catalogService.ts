import {
  Timestamp,
  type DocumentData,
  type DocumentReference,
  type Firestore,
} from 'firebase-admin/firestore';
import type {
  AdminStudyFileVersionDto,
  BinaryVerificationStatus,
  CatalogBranchDto,
  CatalogSectionDto,
  CatalogSubjectDto,
  PublicStudyFileDto,
} from '../types/domain.js';
import { ApiError } from '../utils/errors.js';
import {
  isReliableR2ObjectKey,
  MAX_VERSION_LABEL_LENGTH,
  MAX_VERSION_NOTES_LENGTH,
  validateDocId,
  validateNonEmptyString,
  validateOptionalString,
  validatePositiveInt,
} from '../validation/validators.js';

const SHA256_REGEX = /^[a-f0-9]{64}$/i;

function extractString(
  data: DocumentData,
  ...keys: string[]
): string | null {
  for (const key of keys) {
    const val = data[key];
    if (typeof val === 'string') {
      const trimmed = val.trim();
      if (trimmed.length > 0) return trimmed;
    }
  }
  return null;
}

function extractOrder(data: DocumentData): number {
  const raw = data.order;
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return Math.trunc(raw);
  }
  return Number.MAX_SAFE_INTEGER;
}

function extractEpochMillis(value: unknown): number | null {
  if (!value) return null;
  if (value instanceof Timestamp) {
    return value.toMillis();
  }
  if (value instanceof Date) {
    return value.getTime();
  }
  if (
    typeof value === 'object' &&
    value !== null &&
    'toMillis' in value &&
    typeof (value as { toMillis?: unknown }).toMillis === 'function'
  ) {
    return (value as { toMillis: () => number }).toMillis();
  }
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    return value;
  }
  return null;
}

function extractRefInfo(
  value: unknown,
): { collectionId: string; id: string; path: string } | null {
  if (
    value &&
    typeof value === 'object' &&
    'id' in value &&
    'path' in value &&
    'parent' in value
  ) {
    const ref = value as DocumentReference;
    return {
      collectionId: ref.parent.id,
      id: ref.id,
      path: ref.path,
    };
  }
  return null;
}

function mapVerificationStatus(raw: unknown): BinaryVerificationStatus {
  if (raw === 'VERIFIED' || raw === 'FAILED' || raw === 'PENDING_UPLOAD') {
    return raw;
  }
  return 'PENDING_UPLOAD';
}

function isHierarchyNodePublishedAndNotArchived(data: DocumentData): boolean {
  return (
    data.isPublished === true &&
    (data.isArchived === undefined || data.isArchived === false)
  );
}

export interface ResolvedPublishedVersionBinary {
  fileId: string;
  fileTitle: string;
  versionId: string;
  versionNumber: number;
  versionLabel: string;
  r2ObjectKey: string;
  fileSizeBytes: number;
  checksumSha256: string;
  mimeType: string;
}

export class CatalogService {
  constructor(private readonly firestore: Firestore) {}

  async listPublishedBranches(): Promise<CatalogBranchDto[]> {
    const snapshot = await this.firestore
      .collection('branches')
      .where('isPublished', '==', true)
      .get();

    return snapshot.docs
      .map((doc): CatalogBranchDto | null => {
        const data = doc.data();
        if (!isHierarchyNodePublishedAndNotArchived(data)) return null;
        const name = extractString(data, 'nameAr', 'name', 'title');
        if (!name) return null;
        return {
          id: doc.id,
          name,
          order: extractOrder(data),
          isPublished: true,
          referencePath: `branches/${doc.id}`,
        };
      })
      .filter((item): item is CatalogBranchDto => item !== null)
      .sort((a, b) => a.order - b.order || a.name.localeCompare(b.name, 'ar'));
  }

  async listPublishedSubjects(rawBranchId: string): Promise<CatalogSubjectDto[]> {
    const branchId = validateDocId(rawBranchId, 'branchId');
    const branchRef = this.firestore.collection('branches').doc(branchId);
    const branchSnap = await branchRef.get();
    const branchData = branchSnap.data();
    if (
      !branchSnap.exists ||
      !branchData ||
      !isHierarchyNodePublishedAndNotArchived(branchData)
    ) {
      throw new ApiError(
        404,
        'CATALOG_UNAVAILABLE',
        `Branch '${branchId}' is not available.`,
      );
    }

    const snapshot = await this.firestore
      .collection('subjects')
      .where('branchId', '==', branchRef)
      .where('isPublished', '==', true)
      .get();

    return snapshot.docs
      .map((doc): CatalogSubjectDto | null => {
        const data = doc.data();
        if (!isHierarchyNodePublishedAndNotArchived(data)) return null;
        const refInfo = extractRefInfo(data.branchId);
        if (!refInfo || refInfo.collectionId !== 'branches' || refInfo.id !== branchId) {
          return null;
        }
        const name = extractString(data, 'nameAr', 'name', 'title');
        if (!name) return null;
        return {
          id: doc.id,
          branchId,
          name,
          order: extractOrder(data),
          isPublished: true,
          branchReferencePath: refInfo.path,
        };
      })
      .filter((item): item is CatalogSubjectDto => item !== null)
      .sort((a, b) => a.order - b.order || a.name.localeCompare(b.name, 'ar'));
  }

  async listPublishedSections(rawSubjectId: string): Promise<CatalogSectionDto[]> {
    const subjectId = validateDocId(rawSubjectId, 'subjectId');
    const subjectRef = this.firestore.collection('subjects').doc(subjectId);
    const subjectSnap = await subjectRef.get();
    const subjectData = subjectSnap.data();
    if (
      !subjectSnap.exists ||
      !subjectData ||
      !isHierarchyNodePublishedAndNotArchived(subjectData)
    ) {
      throw new ApiError(
        404,
        'CATALOG_UNAVAILABLE',
        `Subject '${subjectId}' is not available.`,
      );
    }

    const branchInfo = extractRefInfo(subjectData.branchId);
    if (!branchInfo || branchInfo.collectionId !== 'branches') {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Parent branch reference missing.');
    }
    const branchSnap = await this.firestore
      .collection('branches')
      .doc(branchInfo.id)
      .get();
    const branchData = branchSnap.data();
    if (
      !branchSnap.exists ||
      !branchData ||
      !isHierarchyNodePublishedAndNotArchived(branchData)
    ) {
      throw new ApiError(
        404,
        'CATALOG_UNAVAILABLE',
        'Parent branch is not published.',
      );
    }

    const snapshot = await this.firestore
      .collection('sections')
      .where('subjectId', '==', subjectRef)
      .where('isPublished', '==', true)
      .get();

    return snapshot.docs
      .map((doc): CatalogSectionDto | null => {
        const data = doc.data();
        if (!isHierarchyNodePublishedAndNotArchived(data)) return null;
        const refInfo = extractRefInfo(data.subjectId);
        if (!refInfo || refInfo.collectionId !== 'subjects' || refInfo.id !== subjectId) {
          return null;
        }
        const name = extractString(data, 'nameAr', 'name', 'title');
        if (!name) return null;
        return {
          id: doc.id,
          subjectId,
          name,
          order: extractOrder(data),
          isPublished: true,
          subjectReferencePath: refInfo.path,
        };
      })
      .filter((item): item is CatalogSectionDto => item !== null)
      .sort((a, b) => a.order - b.order || a.name.localeCompare(b.name, 'ar'));
  }

  async listPublishedStudyFiles(rawSectionId: string): Promise<PublicStudyFileDto[]> {
    const sectionId = validateDocId(rawSectionId, 'sectionId');
    const sectionRef = this.firestore.collection('sections').doc(sectionId);
    const sectionSnap = await sectionRef.get();
    const sectionData = sectionSnap.data();
    if (
      !sectionSnap.exists ||
      !sectionData ||
      !isHierarchyNodePublishedAndNotArchived(sectionData)
    ) {
      throw new ApiError(
        404,
        'CATALOG_UNAVAILABLE',
        `Section '${sectionId}' is not available.`,
      );
    }

    const subjectInfo = extractRefInfo(sectionData.subjectId);
    if (!subjectInfo || subjectInfo.collectionId !== 'subjects') {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Parent subject reference missing.');
    }
    const subjectSnap = await this.firestore
      .collection('subjects')
      .doc(subjectInfo.id)
      .get();
    const subjectData = subjectSnap.data();
    if (
      !subjectSnap.exists ||
      !subjectData ||
      !isHierarchyNodePublishedAndNotArchived(subjectData)
    ) {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Parent subject is not published.');
    }

    const branchInfo = extractRefInfo(subjectData.branchId);
    if (!branchInfo || branchInfo.collectionId !== 'branches') {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Parent branch reference missing.');
    }
    const branchSnap = await this.firestore
      .collection('branches')
      .doc(branchInfo.id)
      .get();
    const branchData = branchSnap.data();
    if (
      !branchSnap.exists ||
      !branchData ||
      !isHierarchyNodePublishedAndNotArchived(branchData)
    ) {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Parent branch is not published.');
    }

    const filesSnap = await this.firestore
      .collection('study_files')
      .where('sectionId', '==', sectionRef)
      .where('isPublished', '==', true)
      .get();

    const mapped = await Promise.all(
      filesSnap.docs.map(async (fileDoc): Promise<PublicStudyFileDto | null> => {
        const data = fileDoc.data();
        if (data.isPublished !== true || data.isArchived !== false) {
          return null;
        }
        const refInfo = extractRefInfo(data.sectionId);
        if (!refInfo || refInfo.collectionId !== 'sections' || refInfo.id !== sectionId) {
          return null;
        }
        const title = extractString(data, 'title', 'nameAr', 'name');
        if (!title) return null;

        const activeVersionId = extractString(data, 'activeVersionId');
        const activeVersionNumber =
          typeof data.activeVersionNumber === 'number' &&
          Number.isInteger(data.activeVersionNumber) &&
          data.activeVersionNumber >= 1
            ? data.activeVersionNumber
            : null;
        if (!activeVersionId || !activeVersionNumber) {
          return null;
        }

        const versionSnap = await fileDoc.ref
          .collection('versions')
          .doc(activeVersionId)
          .get();
        const verData = versionSnap.data();
        if (!versionSnap.exists || !verData) return null;
        if (verData.isPublished !== true || verData.isArchived !== false) return null;
        if (verData.versionNumber !== activeVersionNumber) return null;
        if (verData.binaryVerificationStatus !== 'VERIFIED') return null;

        const r2ObjectKey = verData.r2ObjectKey;
        const fileSizeBytes = verData.fileSizeBytes;
        const checksumSha256 =
          typeof verData.checksumSha256 === 'string'
            ? verData.checksumSha256.trim().toLowerCase()
            : '';

        if (
          !isReliableR2ObjectKey(fileDoc.id, activeVersionId, r2ObjectKey) ||
          typeof fileSizeBytes !== 'number' ||
          !Number.isInteger(fileSizeBytes) ||
          fileSizeBytes <= 0 ||
          !SHA256_REGEX.test(checksumSha256)
        ) {
          return null;
        }

        return {
          id: fileDoc.id,
          sectionId,
          title,
          description: extractString(data, 'description'),
          order: extractOrder(data),
          mimeType: extractString(data, 'mimeType') ?? 'application/pdf',
          activeVersionId,
          activeVersionNumber,
          activeVersionLabel: extractString(
            data,
            'activeVersionLabel',
          ) ?? extractString(verData, 'versionLabel'),
          activeVersionPublishedAtEpochMillis:
            extractEpochMillis(data.activeVersionPublishedAt) ??
            extractEpochMillis(verData.publishedAt),
          fileSizeBytes,
          checksumSha256,
          streamPath: `/api/study-files/${encodeURIComponent(fileDoc.id)}/versions/${encodeURIComponent(activeVersionId)}/stream`,
          downloadPath: `/api/study-files/${encodeURIComponent(fileDoc.id)}/versions/${encodeURIComponent(activeVersionId)}/download`,
        };
      }),
    );

    return mapped
      .filter((item): item is PublicStudyFileDto => item !== null)
      .sort((a, b) => a.order - b.order || a.title.localeCompare(b.title, 'ar'));
  }

  async resolvePublishedHierarchyForVersion(
    rawFileId: string,
    rawVersionId: string,
  ): Promise<ResolvedPublishedVersionBinary> {
    const fileId = validateDocId(rawFileId, 'fileId');
    const versionId = validateDocId(rawVersionId, 'versionId');

    const fileRef = this.firestore.collection('study_files').doc(fileId);
    const versionRef = fileRef.collection('versions').doc(versionId);
    const [fileSnap, versionSnap] = await Promise.all([
      fileRef.get(),
      versionRef.get(),
    ]);

    const fileData = fileSnap.data();
    const versionData = versionSnap.data();

    if (!fileSnap.exists || !fileData) {
      throw new ApiError(404, 'NOT_FOUND', `Study file '${fileId}' was not found.`);
    }
    if (fileData.isPublished !== true || fileData.isArchived !== false) {
      throw new ApiError(
        404,
        'CATALOG_UNAVAILABLE',
        `Study file '${fileId}' is not published or is archived.`,
      );
    }

    if (!versionSnap.exists || !versionData) {
      throw new ApiError(
        404,
        'NOT_FOUND',
        `Version '${versionId}' for file '${fileId}' was not found.`,
      );
    }

    const verFileRef = extractRefInfo(versionData.fileId);
    if (
      !verFileRef ||
      verFileRef.collectionId !== 'study_files' ||
      verFileRef.id !== fileId
    ) {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Version parent mismatch.');
    }

    if (
      versionData.isPublished !== true ||
      versionData.isArchived !== false ||
      versionData.binaryVerificationStatus !== 'VERIFIED'
    ) {
      throw new ApiError(
        404,
        'CATALOG_UNAVAILABLE',
        `Version '${versionId}' is not active and verified.`,
      );
    }

    if (
      fileData.activeVersionId !== versionId ||
      fileData.activeVersionNumber !== versionData.versionNumber
    ) {
      throw new ApiError(
        404,
        'CATALOG_UNAVAILABLE',
        `Version '${versionId}' is not the currently active published version.`,
      );
    }

    const r2ObjectKey = versionData.r2ObjectKey;
    const fileSizeBytes = versionData.fileSizeBytes;
    const checksumSha256 =
      typeof versionData.checksumSha256 === 'string'
        ? versionData.checksumSha256.trim().toLowerCase()
        : '';

    if (
      !isReliableR2ObjectKey(fileId, versionId, r2ObjectKey) ||
      typeof fileSizeBytes !== 'number' ||
      !Number.isInteger(fileSizeBytes) ||
      fileSizeBytes <= 0 ||
      !SHA256_REGEX.test(checksumSha256)
    ) {
      throw new ApiError(
        404,
        'PROOF_MISMATCH',
        'Verified binary metadata is incomplete or invalid.',
      );
    }

    // Live check of section -> subject -> branch without any cache.
    const sectionInfo = extractRefInfo(fileData.sectionId);
    if (!sectionInfo || sectionInfo.collectionId !== 'sections') {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Missing section reference.');
    }
    const sectionSnap = await this.firestore
      .collection('sections')
      .doc(sectionInfo.id)
      .get();
    const sectionData = sectionSnap.data();
    if (
      !sectionSnap.exists ||
      !sectionData ||
      !isHierarchyNodePublishedAndNotArchived(sectionData)
    ) {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Parent section is not published.');
    }

    const subjectInfo = extractRefInfo(sectionData.subjectId);
    if (!subjectInfo || subjectInfo.collectionId !== 'subjects') {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Missing subject reference.');
    }
    const subjectSnap = await this.firestore
      .collection('subjects')
      .doc(subjectInfo.id)
      .get();
    const subjectData = subjectSnap.data();
    if (
      !subjectSnap.exists ||
      !subjectData ||
      !isHierarchyNodePublishedAndNotArchived(subjectData)
    ) {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Parent subject is not published.');
    }

    const branchInfo = extractRefInfo(subjectData.branchId);
    if (!branchInfo || branchInfo.collectionId !== 'branches') {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Missing branch reference.');
    }
    const branchSnap = await this.firestore
      .collection('branches')
      .doc(branchInfo.id)
      .get();
    const branchData = branchSnap.data();
    if (
      !branchSnap.exists ||
      !branchData ||
      !isHierarchyNodePublishedAndNotArchived(branchData)
    ) {
      throw new ApiError(404, 'CATALOG_UNAVAILABLE', 'Parent branch is not published.');
    }

    return {
      fileId,
      fileTitle: extractString(fileData, 'title', 'nameAr', 'name') ?? fileId,
      versionId,
      versionNumber: versionData.versionNumber as number,
      versionLabel: extractString(versionData, 'versionLabel') ?? `v${versionData.versionNumber}`,
      r2ObjectKey,
      fileSizeBytes,
      checksumSha256,
      mimeType: extractString(fileData, 'mimeType') ?? 'application/pdf',
    };
  }

  // ===== Admin Read & Draft Version Operations (Admin / Owner) =====

  async listAllBranchesForAdmin(): Promise<CatalogBranchDto[]> {
    const snapshot = await this.firestore.collection('branches').get();
    return snapshot.docs
      .map((doc): CatalogBranchDto | null => {
        const data = doc.data();
        const name = extractString(data, 'nameAr', 'name', 'title');
        if (!name) return null;
        return {
          id: doc.id,
          name,
          order: extractOrder(data),
          isPublished: data.isPublished === true,
          referencePath: `branches/${doc.id}`,
        };
      })
      .filter((item): item is CatalogBranchDto => item !== null)
      .sort((a, b) => a.order - b.order || a.name.localeCompare(b.name, 'ar'));
  }

  async listAllSubjectsForAdmin(rawBranchId: string): Promise<CatalogSubjectDto[]> {
    const branchId = validateDocId(rawBranchId, 'branchId');
    const branchRef = this.firestore.collection('branches').doc(branchId);
    const snapshot = await this.firestore
      .collection('subjects')
      .where('branchId', '==', branchRef)
      .get();

    return snapshot.docs
      .map((doc): CatalogSubjectDto | null => {
        const data = doc.data();
        const refInfo = extractRefInfo(data.branchId);
        if (!refInfo || refInfo.collectionId !== 'branches' || refInfo.id !== branchId) {
          return null;
        }
        const name = extractString(data, 'nameAr', 'name', 'title');
        if (!name) return null;
        return {
          id: doc.id,
          branchId,
          name,
          order: extractOrder(data),
          isPublished: data.isPublished === true,
          branchReferencePath: refInfo.path,
        };
      })
      .filter((item): item is CatalogSubjectDto => item !== null)
      .sort((a, b) => a.order - b.order || a.name.localeCompare(b.name, 'ar'));
  }

  async listAllSectionsForAdmin(rawSubjectId: string): Promise<CatalogSectionDto[]> {
    const subjectId = validateDocId(rawSubjectId, 'subjectId');
    const subjectRef = this.firestore.collection('subjects').doc(subjectId);
    const snapshot = await this.firestore
      .collection('sections')
      .where('subjectId', '==', subjectRef)
      .get();

    return snapshot.docs
      .map((doc): CatalogSectionDto | null => {
        const data = doc.data();
        const refInfo = extractRefInfo(data.subjectId);
        if (!refInfo || refInfo.collectionId !== 'subjects' || refInfo.id !== subjectId) {
          return null;
        }
        const name = extractString(data, 'nameAr', 'name', 'title');
        if (!name) return null;
        return {
          id: doc.id,
          subjectId,
          name,
          order: extractOrder(data),
          isPublished: data.isPublished === true,
          subjectReferencePath: refInfo.path,
        };
      })
      .filter((item): item is CatalogSectionDto => item !== null)
      .sort((a, b) => a.order - b.order || a.name.localeCompare(b.name, 'ar'));
  }

  async listStudyFilesForAdmin(rawSectionId: string) {
    const sectionId = validateDocId(rawSectionId, 'sectionId');
    const sectionRef = this.firestore.collection('sections').doc(sectionId);
    const snapshot = await this.firestore
      .collection('study_files')
      .where('sectionId', '==', sectionRef)
      .get();

    return snapshot.docs
      .map((doc) => {
        const data = doc.data();
        const refInfo = extractRefInfo(data.sectionId);
        if (!refInfo || refInfo.collectionId !== 'sections' || refInfo.id !== sectionId) {
          return null;
        }
        const title = extractString(data, 'title', 'nameAr', 'name');
        if (!title) return null;
        return {
          id: doc.id,
          sectionId,
          title,
          description: extractString(data, 'description'),
          order: extractOrder(data),
          isPublished: data.isPublished === true,
          isArchived: data.isArchived === true,
          mimeType: extractString(data, 'mimeType') ?? 'application/pdf',
          storageProvider: extractString(data, 'storageProvider') ?? 'cloudflare_r2',
          activeVersionId: extractString(data, 'activeVersionId'),
          activeVersionNumber:
            typeof data.activeVersionNumber === 'number'
              ? data.activeVersionNumber
              : null,
          activeVersionLabel: extractString(data, 'activeVersionLabel'),
          activeVersionPublishedAtEpochMillis: extractEpochMillis(
            data.activeVersionPublishedAt,
          ),
        };
      })
      .filter((item): item is NonNullable<typeof item> => item !== null)
      .sort((a, b) => {
        if (a.isArchived !== b.isArchived) return a.isArchived ? 1 : -1;
        return a.order - b.order || a.title.localeCompare(b.title, 'ar');
      });
  }

  async listStudyFileVersionsForAdmin(
    rawFileId: string,
  ): Promise<AdminStudyFileVersionDto[]> {
    const fileId = validateDocId(rawFileId, 'fileId');
    const fileRef = this.firestore.collection('study_files').doc(fileId);
    const fileSnap = await fileRef.get();
    if (!fileSnap.exists) {
      throw new ApiError(404, 'NOT_FOUND', `Study file '${fileId}' was not found.`);
    }

    const versionsSnap = await fileRef.collection('versions').get();
    return versionsSnap.docs
      .map((doc): AdminStudyFileVersionDto | null => {
        const data = doc.data();
        const refInfo = extractRefInfo(data.fileId);
        if (!refInfo || refInfo.collectionId !== 'study_files' || refInfo.id !== fileId) {
          return null;
        }
        const versionNumber =
          typeof data.versionNumber === 'number' && data.versionNumber >= 1
            ? data.versionNumber
            : null;
        const versionLabel = extractString(data, 'versionLabel');
        if (!versionNumber || !versionLabel) return null;

        return {
          id: doc.id,
          fileId,
          versionNumber,
          versionLabel,
          versionNotes: extractString(data, 'versionNotes'),
          isPublished: data.isPublished === true,
          isArchived: data.isArchived === true,
          binaryVerificationStatus: mapVerificationStatus(
            data.binaryVerificationStatus,
          ),
          fileSizeBytes:
            typeof data.fileSizeBytes === 'number' && data.fileSizeBytes > 0
              ? data.fileSizeBytes
              : null,
          checksumSha256: extractString(data, 'checksumSha256'),
          verifiedUploadSessionId: extractString(data, 'verifiedUploadSessionId'),
          verifiedAttemptId: extractString(data, 'verifiedAttemptId'),
          publishedAtEpochMillis: extractEpochMillis(data.publishedAt),
          createdAtEpochMillis: extractEpochMillis(data.createdAt),
          updatedAtEpochMillis: extractEpochMillis(data.updatedAt),
        };
      })
      .filter((item): item is AdminStudyFileVersionDto => item !== null)
      .sort((a, b) => b.versionNumber - a.versionNumber);
  }

  async createDraftStudyFileVersion(params: {
    fileId: string;
    versionNumber: unknown;
    versionLabel: unknown;
    versionNotes?: unknown;
    customVersionId?: unknown;
    actorUid: string;
  }): Promise<AdminStudyFileVersionDto> {
    const fileId = validateDocId(params.fileId, 'fileId');
    const versionNumber = validatePositiveInt(params.versionNumber, 'versionNumber', 10000);
    const versionLabel = validateNonEmptyString(
      params.versionLabel,
      'versionLabel',
      MAX_VERSION_LABEL_LENGTH,
    );
    const versionNotes = validateOptionalString(
      params.versionNotes,
      'versionNotes',
      MAX_VERSION_NOTES_LENGTH,
    );
    const customVersionId =
      params.customVersionId !== undefined && params.customVersionId !== null
        ? validateDocId(params.customVersionId, 'customVersionId')
        : null;

    const fileRef = this.firestore.collection('study_files').doc(fileId);
    const versionsCol = fileRef.collection('versions');
    const versionRef = customVersionId
      ? versionsCol.doc(customVersionId)
      : versionsCol.doc();

    const now = Timestamp.now();

    await this.firestore.runTransaction(async (tx) => {
      const fileSnap = await tx.get(fileRef);
      const fileData = fileSnap.data();
      if (!fileSnap.exists || !fileData) {
        throw new ApiError(404, 'NOT_FOUND', `Study file '${fileId}' was not found.`);
      }
      if (fileData.isArchived === true) {
        throw new ApiError(
          409,
          'FILE_ARCHIVED',
          `Cannot add a version to archived study file '${fileId}'.`,
        );
      }

      const candidateSnap = await tx.get(versionRef);
      if (candidateSnap.exists) {
        throw new ApiError(
          409,
          'VERSION_NUMBER_CONFLICT',
          `Version ID '${versionRef.id}' already exists.`,
        );
      }

      const existingVersionsSnap = await tx.get(versionsCol);
      const existingNumbers = new Set<number>();
      const rawRegistered = fileData.registeredVersionNumbers;
      if (Array.isArray(rawRegistered)) {
        for (const n of rawRegistered) {
          if (typeof n === 'number' && Number.isInteger(n) && n >= 1) {
            existingNumbers.add(n);
          }
        }
      }
      for (const snap of existingVersionsSnap.docs) {
        const n = snap.data().versionNumber;
        if (typeof n === 'number' && Number.isInteger(n) && n >= 1) {
          existingNumbers.add(n);
        }
      }

      if (existingNumbers.has(versionNumber)) {
        throw new ApiError(
          409,
          'VERSION_NUMBER_CONFLICT',
          `Version number ${versionNumber} already exists for file '${fileId}'.`,
        );
      }

      existingNumbers.add(versionNumber);
      const nextNumbers = Array.from(existingNumbers).sort((a, b) => a - b);

      tx.set(versionRef, {
        fileId: fileRef,
        versionNumber,
        versionLabel,
        versionNotes,
        r2ObjectKey: null,
        fileSizeBytes: null,
        checksumSha256: null,
        binaryVerificationStatus: 'PENDING_UPLOAD',
        verifiedUploadSessionId: null,
        verifiedAttemptId: null,
        activeUploadSessionId: null,
        binaryVerifiedAt: null,
        isPublished: false,
        isArchived: false,
        publishedAt: null,
        createdByUid: params.actorUid,
        updatedByUid: params.actorUid,
        createdAt: now,
        updatedAt: now,
      });

      tx.update(fileRef, {
        registeredVersionNumbers: nextNumbers,
        updatedByUid: params.actorUid,
        updatedAt: now,
      });
    });

    return {
      id: versionRef.id,
      fileId,
      versionNumber,
      versionLabel,
      versionNotes,
      isPublished: false,
      isArchived: false,
      binaryVerificationStatus: 'PENDING_UPLOAD',
      fileSizeBytes: null,
      checksumSha256: null,
      verifiedUploadSessionId: null,
      verifiedAttemptId: null,
      publishedAtEpochMillis: null,
      createdAtEpochMillis: now.toMillis(),
      updatedAtEpochMillis: now.toMillis(),
    };
  }

  async updateStudyFileVersionMetadata(params: {
    fileId: string;
    versionId: string;
    versionLabel: unknown;
    versionNotes?: unknown;
    actorUid: string;
  }): Promise<void> {
    const fileId = validateDocId(params.fileId, 'fileId');
    const versionId = validateDocId(params.versionId, 'versionId');
    const versionLabel = validateNonEmptyString(
      params.versionLabel,
      'versionLabel',
      MAX_VERSION_LABEL_LENGTH,
    );
    const versionNotes = validateOptionalString(
      params.versionNotes,
      'versionNotes',
      MAX_VERSION_NOTES_LENGTH,
    );

    const fileRef = this.firestore.collection('study_files').doc(fileId);
    const versionRef = fileRef.collection('versions').doc(versionId);
    const now = Timestamp.now();

    await this.firestore.runTransaction(async (tx) => {
      const [fileSnap, versionSnap] = await Promise.all([
        tx.get(fileRef),
        tx.get(versionRef),
      ]);
      const fileData = fileSnap.data();
      const versionData = versionSnap.data();

      if (!fileSnap.exists || !fileData) {
        throw new ApiError(404, 'NOT_FOUND', `Study file '${fileId}' was not found.`);
      }
      if (!versionSnap.exists || !versionData) {
        throw new ApiError(404, 'NOT_FOUND', `Version '${versionId}' was not found.`);
      }
      const verFileRef = extractRefInfo(versionData.fileId);
      if (!verFileRef || verFileRef.id !== fileId) {
        throw new ApiError(404, 'NOT_FOUND', 'Version does not belong to file.');
      }

      tx.update(versionRef, {
        versionLabel,
        versionNotes,
        updatedByUid: params.actorUid,
        updatedAt: now,
      });

      if (
        fileData.activeVersionId === versionId &&
        versionData.isPublished === true
      ) {
        tx.update(fileRef, {
          activeVersionLabel: versionLabel,
          updatedByUid: params.actorUid,
          updatedAt: now,
        });
      }
    });
  }
}
