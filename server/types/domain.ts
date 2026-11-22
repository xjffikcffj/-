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

export type ArchiveStatus = 'UNARCHIVED' | 'ARCHIVED' | 'UNSPECIFIED';

export interface PublicStudyFileDto {
  id: string;
  sectionId: string;
  title: string;
  description: string | null;
  order: number;
  archiveStatus: ArchiveStatus;
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

export interface AdminStudyFileDto {
  id: string;
  sectionId: string;
  title: string;
  description: string | null;
  order: number;
  isPublished: boolean;
  isArchived: boolean | null;
  archiveStatus: ArchiveStatus;
  mimeType: string;
  storageProvider: string;
  activeVersionId: string | null;
  activeVersionNumber: number | null;
  activeVersionLabel: string | null;
  activeVersionPublishedAtEpochMillis: number | null;
}

export interface AdminStudyFileVersionDto {
  id: string;
  fileId: string;
  versionNumber: number;
  versionLabel: string;
  versionNotes: string | null;
  isPublished: boolean;
  isArchived: boolean | null;
  archiveStatus: ArchiveStatus;
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

export type StaffRole = 'owner' | 'admin';

export interface StaffMemberDto {
  uid: string;
  email: string | null;
  displayName: string | null;
  role: StaffRole;
  disabled: boolean;
  emailVerified: boolean;
}

export interface UpdateStaffRoleResponseDto {
  status: 'UPDATED';
  uid: string;
  email: string | null;
  role: 'admin' | 'student';
  tokensRevoked: boolean;
}

export type AuditLogSource = 'backend' | 'client';

export type AuditLogAction =
  | 'BRANCH_CREATED'
  | 'BRANCH_UPDATED'
  | 'BRANCH_PUBLISH_CHANGED'
  | 'BRANCHES_REORDERED'
  | 'SUBJECT_CREATED'
  | 'SUBJECT_UPDATED'
  | 'SUBJECT_PUBLISH_CHANGED'
  | 'SUBJECTS_REORDERED'
  | 'SECTION_CREATED'
  | 'SECTION_UPDATED'
  | 'SECTION_PUBLISH_CHANGED'
  | 'SECTIONS_REORDERED'
  | 'STUDY_FILE_CREATED'
  | 'STUDY_FILE_UPDATED'
  | 'STUDY_FILE_PUBLISH_CHANGED'
  | 'STUDY_FILE_ARCHIVE_CHANGED'
  | 'STUDY_FILES_REORDERED'
  | 'VERSION_CREATED'
  | 'VERSION_UPDATED'
  | 'UPLOAD_SLOT_CREATED'
  | 'PDF_VERIFIED'
  | 'VERSION_PUBLISHED'
  | 'ADMIN_ROLE_GRANTED'
  | 'ADMIN_ROLE_REVOKED'
  | 'IMPORTANT_UPDATE_BROADCAST';

export interface AuditLogDto {
  id: string;
  action: AuditLogAction | string;
  actorUid: string;
  actorRole: AppUserRole | string;
  targetCollection: string;
  targetId: string;
  summary: string;
  source: AuditLogSource | string;
  createdAtEpochMillis: number | null;
}

export type StudyNotificationType =
  | 'NEW_FILE'
  | 'NEW_VERSION'
  | 'IMPORTANT_UPDATE';

export interface BroadcastNotificationResponseDto {
  notificationId: string;
  topic: string;
  type: StudyNotificationType;
  title: string;
  body: string;
  sent: boolean;
  reason?: string;
}
