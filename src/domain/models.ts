export type AppUserRole = 'GUEST' | 'STUDENT' | 'ADMIN' | 'OWNER';

export type AdminRole = 'OWNER' | 'ADMIN' | 'NONE';

export interface AdminSession {
  isSignedIn: boolean;
  uid: string | null;
  email: string | null;
  role: AdminRole;
  hasLoadedTokenClaims: boolean;
  areClaimsConfirmedCurrent: boolean;
}

export function getEffectiveUserRole(session: AdminSession): AppUserRole {
  if (!session.isSignedIn) return 'GUEST';
  if (session.hasLoadedTokenClaims && session.role === 'OWNER') return 'OWNER';
  if (session.hasLoadedTokenClaims && session.role === 'ADMIN') return 'ADMIN';
  return 'STUDENT';
}

export function canDownloadOfflineFiles(session: AdminSession): boolean {
  return getEffectiveUserRole(session) !== 'GUEST';
}

export function hasRecognizedAdminRole(session: AdminSession): boolean {
  return session.isSignedIn && session.hasLoadedTokenClaims && (session.role === 'ADMIN' || session.role === 'OWNER');
}

export function canPerformAdminActions(session: AdminSession): boolean {
  return hasRecognizedAdminRole(session) && session.areClaimsConfirmedCurrent;
}

export interface StudyBranch {
  id: string;
  name: string;
  order: number;
  isPublished: boolean;
  referencePath: string;
}

export interface StudySubject {
  id: string;
  branchId: string;
  name: string;
  order: number;
  isPublished: boolean;
  branchReferencePath: string;
}

export interface StudySection {
  id: string;
  subjectId: string;
  name: string;
  order: number;
  isPublished: boolean;
  subjectReferencePath: string;
}

export interface StudyPageContent {
  pageNumber: number;
  heading: string;
  paragraphs: string[];
  keyTakeaway?: string;
}

export interface StudyFileMetadata {
  id: string;
  sectionId: string;
  sectionReferencePath: string;
  title: string;
  description: string | null;
  order: number;
  isPublished: boolean;
  isArchived: boolean;
  mimeType: string;
  storageProvider: string;
  activeVersionId: string | null;
  activeVersionNumber: number | null;
  activeVersionLabel: string | null;
  createdByUid?: string | null;
  updatedByUid?: string | null;
}

export interface FileReadingProgress {
  fileId: string;
  lastPageNumber: number;
  totalPages: number;
  updatedAtEpochMillis: number;
}

export type PdfSearchStatus =
  | 'IDLE'
  | 'SEARCHING'
  | 'FOUND'
  | 'NO_RESULTS'
  | 'NO_EXTRACTABLE_TEXT'
  | 'ERROR';

export interface PdfSearchMatch {
  pageNumber: number;
  matchIndexInDocument: number;
  snippet: string;
}

export interface PdfSearchResult {
  query: string;
  status: PdfSearchStatus;
  matches: PdfSearchMatch[];
}

export interface UserPreferences {
  darkModeEnabled: boolean;
}

export type MainTab = 'HOME' | 'LIBRARY' | 'DOWNLOADS' | 'SETTINGS';

export type AdminManagementSection = 'BRANCHES' | 'SUBJECTS' | 'SECTIONS' | 'PDF_FILES';

export interface FirebaseProjectInfo {
  projectId: string;
  projectNumber: string;
  firestoreDatabaseId: string;
  firestoreLocation: string;
  packageId: string;
  versionName: string;
}

export const FIREBASE_PROJECT_INFO: FirebaseProjectInfo = {
  projectId: 'student-learning-platform-2026',
  projectNumber: '48673314803',
  firestoreDatabaseId: '(default)',
  firestoreLocation: 'me-central1',
  packageId: 'com.studentlearning.platform2026',
  versionName: '0.1.0',
};
