export type AppUserRole = 'owner' | 'admin' | 'student' | 'guest';

export interface AuthContext {
  isAuthenticated: boolean;
  isAnonymous: boolean;
  uid: string | null;
  email: string | null;
  role: AppUserRole;
}

export interface CatalogBranchDto {
  id: string;
  name: string;
  order: number;
  isPublished: boolean;
  referencePath: string;
}

export interface CatalogSubjectDto {
  id: string;
  branchId: string;
  name: string;
  order: number;
  isPublished: boolean;
  branchReferencePath: string;
}

export interface CatalogSectionDto {
  id: string;
  subjectId: string;
  name: string;
  order: number;
  isPublished: boolean;
  subjectReferencePath: string;
}

export interface PublicStudyFileDto {
  id: string;
  sectionId: string;
  title: string;
  description: string | null;
  order: number;
  mimeType: string;
  activeVersionId: string;
  activeVersionNumber: number;
  activeVersionLabel: string | null;
  activeVersionPublishedAtEpochMillis: number | null;
  fileSizeBytes: number;
  checksumSha256: string;
  streamPath: string;
  downloadPath: string;
}

export type BinaryVerificationStatus =
  | 'PENDING_UPLOAD'
  | 'VERIFIED'
  | 'FAILED';

export interface AdminStudyFileVersionDto {
  id: string;
  fileId: string;
  versionNumber: number;
  versionLabel: string;
  versionNotes: string | null;
  isPublished: boolean;
  isArchived: boolean;
  binaryVerificationStatus: BinaryVerificationStatus;
  fileSizeBytes: number | null;
  checksumSha256: string | null;
  verifiedUploadSessionId: string | null;
  verifiedAttemptId: string | null;
  publishedAtEpochMillis: number | null;
  createdAtEpochMillis: number | null;
  updatedAtEpochMillis: number | null;
}

export type UploadSessionStatus =
  | 'PENDING'
  | 'VERIFYING'
  | 'CONSUMED'
  | 'FAILED'
  | 'SUPERSEDED'
  | 'EXPIRED';

export interface UploadSlotResponseDto {
  uploadSessionId: string;
  fileId: string;
  versionId: string;
  stagingKey: string;
  uploadUrl: string;
  method: 'PUT';
  requiredHeaders: {
    'Content-Type': 'application/pdf';
    'Content-Length': string;
  };
  expiresAtEpochMillis: number;
}

export interface VerifyUploadResponseDto {
  status: 'VERIFIED';
  idempotent: boolean;
  fileId: string;
  versionId: string;
  uploadSessionId: string;
  attemptId: string;
  r2ObjectKey: string;
  fileSizeBytes: number;
  checksumSha256: string;
}

export interface PublishVersionResponseDto {
  status: 'PUBLISHED';
  fileId: string;
  versionId: string;
  versionNumber: number;
  versionLabel: string;
  checksumSha256: string;
  fileSizeBytes: number;
  verifiedUploadSessionId: string;
  verifiedAttemptId: string;
}
