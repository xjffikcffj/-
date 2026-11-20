import React, { createContext, useContext, useEffect, useMemo, useState } from 'react';
import {
  AdminSession,
  FileReadingProgress,
  StudyBranch,
  StudyFileMetadata,
  StudySection,
  StudySubject,
  UserPreferences,
} from '../domain/models';
import {
  SEED_BRANCHES,
  SEED_SECTIONS,
  SEED_STUDY_FILES,
  SEED_SUBJECTS,
} from '../data/seedCatalog';
import {
  validateCatalogEntryInput,
  validateEmail,
  validateSignInInput,
  validateStudentSignUpInput,
} from '../domain/validators';

/**
 * WEB PREVIEW ONLY: Local browser simulation of accounts/roles.
 * IMPORTANT SECURITY BOUNDARY:
 * - Neither `computeLocalPreviewHash` (non-cryptographic 32-bit FNV-1a) nor persisting
 *   `role` inside `localStorage` (`haqibati_session_v1`) provides any security or authorization.
 * - Client-side browser state can always be inspected or modified by the user in DevTools.
 * - Real authentication, Admin/Owner verification, and write authorization exist ONLY on
 *   Android + Firebase Authentication + Custom Claims + Firestore Security Rules.
 */
export const IS_WEB_PREVIEW_AUTH_SIMULATION = true;

export const IS_PREVIEW_DEMO_CREDENTIALS_ENABLED: boolean =
  typeof __HAQIBATI_PREVIEW_DEMO_ENABLED__ === 'boolean' &&
  __HAQIBATI_PREVIEW_DEMO_ENABLED__ &&
  typeof import.meta.env === 'object' &&
  import.meta.env.MODE !== 'production' &&
  import.meta.env.VITE_ENABLE_PREVIEW_DEMO === 'true';

interface RegisteredStudentPreviewRecord {
  uid: string;
  email: string;
  previewCredentialHash: string;
  role: 'STUDENT';
}

interface DemoPreviewAccount {
  uid: string;
  email: string;
  previewCredentialHash: string;
  role: 'STUDENT' | 'ADMIN' | 'OWNER';
}

function computeLocalPreviewHash(rawSecret: string): string {
  // Non-cryptographic FNV-1a fingerprint solely to avoid saving raw text in localStorage
  // during local preview; NOT password hashing and NOT a security boundary.
  let h1 = 0x811c9dc5;
  for (let i = 0; i < rawSecret.length; i++) {
    h1 ^= rawSecret.charCodeAt(i);
    h1 = Math.imul(h1, 0x01000193);
  }
  return `preview_${(h1 >>> 0).toString(16)}`;
}

interface CatalogStore {
  branches: StudyBranch[];
  subjects: StudySubject[];
  sections: StudySection[];
  studyFiles: StudyFileMetadata[];
}

const STORAGE_KEYS = {
  PREFS: 'haqibati_preferences_v1',
  SESSION: 'haqibati_session_v1',
  USERS: 'haqibati_preview_students_v2',
  LEGACY_USERS: 'haqibati_users_v1',
  CATALOG: 'haqibati_catalog_v2',
  DOWNLOADS: 'haqibati_downloads_v1',
  PROGRESS: 'haqibati_reading_progress_v1',
};

// Built-in preview simulation accounts stay in memory only (never persisted to localStorage)
// and are stripped completely from production/hosted bundles outside dev/preview mode.
const PREVIEW_DEMO_ACCOUNTS: readonly DemoPreviewAccount[] =
  typeof __HAQIBATI_PREVIEW_DEMO_ENABLED__ === 'boolean' &&
  __HAQIBATI_PREVIEW_DEMO_ENABLED__ &&
  typeof import.meta.env === 'object' &&
  import.meta.env.MODE !== 'production' &&
  import.meta.env.VITE_ENABLE_PREVIEW_DEMO === 'true'
    ? [
        {
          uid: 'owner_001',
          email: 'owner@haqibati.edu',
          previewCredentialHash: computeLocalPreviewHash('Owner1234'),
          role: 'OWNER',
        },
        {
          uid: 'admin_001',
          email: 'admin@haqibati.edu',
          previewCredentialHash: computeLocalPreviewHash('Admin1234'),
          role: 'ADMIN',
        },
        {
          uid: 'student_001',
          email: 'student@haqibati.edu',
          previewCredentialHash: computeLocalPreviewHash('Student1234'),
          role: 'STUDENT',
        },
      ]
    : [];

const GUEST_SESSION: AdminSession = {
  isSignedIn: false,
  uid: null,
  email: null,
  role: 'NONE',
  hasLoadedTokenClaims: true,
  areClaimsConfirmedCurrent: false,
};

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function writeJson<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Ignore storage quota errors
  }
}

interface HaqibatiContextValue {
  preferences: UserPreferences;
  toggleDarkMode: (enabled: boolean) => void;
  session: AdminSession;
  signIn: (email: string, password: string) => Promise<void>;
  signUpStudent: (email: string, password: string, confirmPassword: string) => Promise<void>;
  sendPasswordReset: (email: string) => Promise<string>;
  signOut: () => void;
  refreshSession: () => void;

  // Catalog (Read)
  catalog: CatalogStore;

  // Admin Catalog Mutations
  saveBranch: (params: {
    editingId?: string;
    customId?: string | null;
    name: string;
    order: number;
    isPublished: boolean;
  }) => void;
  toggleBranchPublished: (id: string) => void;
  moveBranch: (id: string, moveUp: boolean) => void;

  saveSubject: (params: {
    branchId: string;
    editingId?: string;
    customId?: string | null;
    name: string;
    order: number;
    isPublished: boolean;
  }) => void;
  toggleSubjectPublished: (id: string) => void;
  moveSubject: (id: string, branchId: string, moveUp: boolean) => void;

  saveSection: (params: {
    subjectId: string;
    editingId?: string;
    customId?: string | null;
    name: string;
    order: number;
    isPublished: boolean;
  }) => void;
  toggleSectionPublished: (id: string) => void;
  moveSection: (id: string, subjectId: string, moveUp: boolean) => void;

  saveStudyFile: (params: {
    sectionId: string;
    editingId?: string;
    customId?: string | null;
    title: string;
    description?: string | null;
    order: number;
    isPublished: boolean;
  }) => void;
  toggleStudyFilePublished: (id: string) => void;
  toggleStudyFileArchived: (id: string) => void;
  moveStudyFile: (id: string, sectionId: string, moveUp: boolean) => void;

  // Offline Downloads (local device, requires signed-in role)
  downloadedFileIds: string[];
  toggleDownloadFile: (fileId: string) => boolean;

  // Local Reading Progress (strictly keyed by fileId, survives sign-out)
  readingProgressMap: Record<string, FileReadingProgress>;
  saveReadingProgress: (fileId: string, lastPageNumber: number, totalPages: number) => void;
}

const HaqibatiContext = createContext<HaqibatiContextValue | null>(null);

export const HaqibatiProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [preferences, setPreferences] = useState<UserPreferences>(() =>
    readJson<UserPreferences>(STORAGE_KEYS.PREFS, { darkModeEnabled: false })
  );

  const [registeredStudents, setRegisteredStudents] = useState<RegisteredStudentPreviewRecord[]>(() => {
    try {
      // Remove any legacy plaintext user records from earlier preview sessions.
      localStorage.removeItem(STORAGE_KEYS.LEGACY_USERS);
    } catch {
      // Ignore storage access errors
    }
    return readJson<RegisteredStudentPreviewRecord[]>(STORAGE_KEYS.USERS, []);
  });

  const [session, setSession] = useState<AdminSession>(() => {
    const stored = readJson<AdminSession>(STORAGE_KEYS.SESSION, GUEST_SESSION);
    if (!IS_PREVIEW_DEMO_CREDENTIALS_ENABLED && (stored.role === 'ADMIN' || stored.role === 'OWNER')) {
      return GUEST_SESSION;
    }
    return stored;
  });

  const [catalog, setCatalog] = useState<CatalogStore>(() =>
    readJson<CatalogStore>(STORAGE_KEYS.CATALOG, {
      branches: SEED_BRANCHES,
      subjects: SEED_SUBJECTS,
      sections: SEED_SECTIONS,
      studyFiles: SEED_STUDY_FILES,
    })
  );

  const [downloadedFileIds, setDownloadedFileIds] = useState<string[]>(() =>
    readJson<string[]>(STORAGE_KEYS.DOWNLOADS, [])
  );

  const [readingProgressMap, setReadingProgressMap] = useState<Record<string, FileReadingProgress>>(() =>
    readJson<Record<string, FileReadingProgress>>(STORAGE_KEYS.PROGRESS, {})
  );

  useEffect(() => {
    const root = document.documentElement;
    if (preferences.darkModeEnabled) {
      root.classList.add('dark');
    } else {
      root.classList.remove('dark');
    }
    writeJson(STORAGE_KEYS.PREFS, preferences);
  }, [preferences]);

  useEffect(() => {
    writeJson(STORAGE_KEYS.USERS, registeredStudents);
  }, [registeredStudents]);

  useEffect(() => {
    writeJson(STORAGE_KEYS.SESSION, session);
  }, [session]);

  useEffect(() => {
    writeJson(STORAGE_KEYS.CATALOG, catalog);
  }, [catalog]);

  useEffect(() => {
    writeJson(STORAGE_KEYS.DOWNLOADS, downloadedFileIds);
  }, [downloadedFileIds]);

  useEffect(() => {
    writeJson(STORAGE_KEYS.PROGRESS, readingProgressMap);
  }, [readingProgressMap]);

  const toggleDarkMode = (enabled: boolean) => {
    setPreferences({ darkModeEnabled: enabled });
  };

  const signIn = async (rawEmail: string, password: string) => {
    const { email } = validateSignInInput(rawEmail, password);
    const inputHash = computeLocalPreviewHash(password);
    const allPreviewAccounts: readonly (DemoPreviewAccount | RegisteredStudentPreviewRecord)[] = [
      ...PREVIEW_DEMO_ACCOUNTS,
      ...registeredStudents,
    ];
    const match = allPreviewAccounts.find(
      (u) => u.email.toLowerCase() === email.toLowerCase() && u.previewCredentialHash === inputHash
    );
    if (!match) {
      throw new Error('البريد الإلكتروني أو كلمة المرور غير صحيحة.');
    }
    setSession({
      isSignedIn: true,
      uid: match.uid,
      email: match.email,
      role: match.role === 'STUDENT' ? 'NONE' : match.role,
      hasLoadedTokenClaims: true,
      areClaimsConfirmedCurrent: true,
    });
  };

  const signUpStudent = async (rawEmail: string, password: string, confirmPassword: string) => {
    const { email } = validateStudentSignUpInput(rawEmail, password, confirmPassword);
    const allEmails = [...PREVIEW_DEMO_ACCOUNTS, ...registeredStudents];
    const exists = allEmails.some((u) => u.email.toLowerCase() === email.toLowerCase());
    if (exists) {
      throw new Error('هذا البريد الإلكتروني مستخدم مسبقاً لحساب آخر. يمكنك تسجيل الدخول أو استعادة كلمة المرور.');
    }
    const newUser: RegisteredStudentPreviewRecord = {
      uid: `student_${Date.now()}`,
      email,
      previewCredentialHash: computeLocalPreviewHash(password),
      role: 'STUDENT',
    };
    setRegisteredStudents((prev) => [...prev, newUser]);
    setSession({
      isSignedIn: true,
      uid: newUser.uid,
      email: newUser.email,
      role: 'NONE',
      hasLoadedTokenClaims: true,
      areClaimsConfirmedCurrent: true,
    });
  };

  const sendPasswordReset = async (rawEmail: string): Promise<string> => {
    const email = validateEmail(rawEmail);
    return email;
  };

  const signOut = () => {
    setSession(GUEST_SESSION);
  };

  const refreshSession = () => {
    if (!session.isSignedIn || !session.uid) return;
    const found = [...PREVIEW_DEMO_ACCOUNTS, ...registeredStudents].find((u) => u.uid === session.uid);
    if (!found) {
      setSession(GUEST_SESSION);
      return;
    }
    setSession({
      isSignedIn: true,
      uid: found.uid,
      email: found.email,
      role: found.role === 'STUDENT' ? 'NONE' : found.role,
      hasLoadedTokenClaims: true,
      areClaimsConfirmedCurrent: true,
    });
  };

  const saveBranch: HaqibatiContextValue['saveBranch'] = ({
    editingId,
    customId,
    name,
    order,
    isPublished,
  }) => {
    const validated = validateCatalogEntryInput(name, order, editingId ? null : customId);
    setCatalog((prev) => {
      if (editingId) {
        return {
          ...prev,
          branches: prev.branches
            .map((b) => (b.id === editingId ? { ...b, name: validated.name, order: validated.order } : b))
            .sort((a, b) => a.order - b.order),
        };
      }
      const newId = validated.customId || `branch_${Date.now()}`;
      if (prev.branches.some((b) => b.id === newId)) {
        throw new Error('المعرّف التقني مستخدم مسبقاً.');
      }
      const created: StudyBranch = {
        id: newId,
        name: validated.name,
        order: validated.order,
        isPublished,
        referencePath: `branches/${newId}`,
      };
      return {
        ...prev,
        branches: [...prev.branches, created].sort((a, b) => a.order - b.order),
      };
    });
  };

  const toggleBranchPublished = (id: string) => {
    setCatalog((prev) => ({
      ...prev,
      branches: prev.branches.map((b) => (b.id === id ? { ...b, isPublished: !b.isPublished } : b)),
    }));
  };

  const moveBranch = (id: string, moveUp: boolean) => {
    setCatalog((prev) => {
      const sorted = [...prev.branches].sort((a, b) => a.order - b.order);
      const idx = sorted.findIndex((b) => b.id === id);
      const targetIdx = moveUp ? idx - 1 : idx + 1;
      if (idx < 0 || targetIdx < 0 || targetIdx >= sorted.length) return prev;
      const a = sorted[idx];
      const b = sorted[targetIdx];
      sorted[idx] = { ...a, order: b.order === a.order ? (moveUp ? a.order - 1 : a.order + 1) : b.order };
      sorted[targetIdx] = { ...b, order: a.order };
      return { ...prev, branches: sorted.sort((x, y) => x.order - y.order) };
    });
  };

  const saveSubject: HaqibatiContextValue['saveSubject'] = ({
    branchId,
    editingId,
    customId,
    name,
    order,
    isPublished,
  }) => {
    const validated = validateCatalogEntryInput(name, order, editingId ? null : customId);
    setCatalog((prev) => {
      if (editingId) {
        return {
          ...prev,
          subjects: prev.subjects
            .map((s) => (s.id === editingId ? { ...s, name: validated.name, order: validated.order } : s))
            .sort((a, b) => a.order - b.order),
        };
      }
      const newId = validated.customId || `subject_${Date.now()}`;
      if (prev.subjects.some((s) => s.id === newId)) {
        throw new Error('المعرّف التقني مستخدم مسبقاً.');
      }
      const created: StudySubject = {
        id: newId,
        branchId,
        name: validated.name,
        order: validated.order,
        isPublished,
        branchReferencePath: `branches/${branchId}`,
      };
      return {
        ...prev,
        subjects: [...prev.subjects, created].sort((a, b) => a.order - b.order),
      };
    });
  };

  const toggleSubjectPublished = (id: string) => {
    setCatalog((prev) => ({
      ...prev,
      subjects: prev.subjects.map((s) => (s.id === id ? { ...s, isPublished: !s.isPublished } : s)),
    }));
  };

  const moveSubject = (id: string, branchId: string, moveUp: boolean) => {
    setCatalog((prev) => {
      const subset = prev.subjects.filter((s) => s.branchId === branchId).sort((a, b) => a.order - b.order);
      const idx = subset.findIndex((s) => s.id === id);
      const targetIdx = moveUp ? idx - 1 : idx + 1;
      if (idx < 0 || targetIdx < 0 || targetIdx >= subset.length) return prev;
      const a = subset[idx];
      const b = subset[targetIdx];
      const newOrderA = b.order === a.order ? (moveUp ? a.order - 1 : a.order + 1) : b.order;
      const newOrderB = a.order;
      return {
        ...prev,
        subjects: prev.subjects
          .map((s) => (s.id === a.id ? { ...s, order: newOrderA } : s.id === b.id ? { ...s, order: newOrderB } : s))
          .sort((x, y) => x.order - y.order),
      };
    });
  };

  const saveSection: HaqibatiContextValue['saveSection'] = ({
    subjectId,
    editingId,
    customId,
    name,
    order,
    isPublished,
  }) => {
    const validated = validateCatalogEntryInput(name, order, editingId ? null : customId);
    setCatalog((prev) => {
      if (editingId) {
        return {
          ...prev,
          sections: prev.sections
            .map((sec) => (sec.id === editingId ? { ...sec, name: validated.name, order: validated.order } : sec))
            .sort((a, b) => a.order - b.order),
        };
      }
      const newId = validated.customId || `section_${Date.now()}`;
      if (prev.sections.some((sec) => sec.id === newId)) {
        throw new Error('المعرّف التقني مستخدم مسبقاً.');
      }
      const created: StudySection = {
        id: newId,
        subjectId,
        name: validated.name,
        order: validated.order,
        isPublished,
        subjectReferencePath: `subjects/${subjectId}`,
      };
      return {
        ...prev,
        sections: [...prev.sections, created].sort((a, b) => a.order - b.order),
      };
    });
  };

  const toggleSectionPublished = (id: string) => {
    setCatalog((prev) => ({
      ...prev,
      sections: prev.sections.map((sec) => (sec.id === id ? { ...sec, isPublished: !sec.isPublished } : sec)),
    }));
  };

  const moveSection = (id: string, subjectId: string, moveUp: boolean) => {
    setCatalog((prev) => {
      const subset = prev.sections.filter((sec) => sec.subjectId === subjectId).sort((a, b) => a.order - b.order);
      const idx = subset.findIndex((sec) => sec.id === id);
      const targetIdx = moveUp ? idx - 1 : idx + 1;
      if (idx < 0 || targetIdx < 0 || targetIdx >= subset.length) return prev;
      const a = subset[idx];
      const b = subset[targetIdx];
      const newOrderA = b.order === a.order ? (moveUp ? a.order - 1 : a.order + 1) : b.order;
      const newOrderB = a.order;
      return {
        ...prev,
        sections: prev.sections
          .map((sec) =>
            sec.id === a.id ? { ...sec, order: newOrderA } : sec.id === b.id ? { ...sec, order: newOrderB } : sec
          )
          .sort((x, y) => x.order - y.order),
      };
    });
  };

  const saveStudyFile: HaqibatiContextValue['saveStudyFile'] = ({
    sectionId,
    editingId,
    customId,
    title,
    description,
    order,
    isPublished,
  }) => {
    const validated = validateCatalogEntryInput(title, order, editingId ? null : customId);
    const normalizedDesc = description?.trim() || null;
    setCatalog((prev) => {
      if (editingId) {
        return {
          ...prev,
          studyFiles: prev.studyFiles
            .map((f) =>
              f.id === editingId
                ? {
                    ...f,
                    title: validated.name,
                    description: normalizedDesc,
                    order: validated.order,
                    updatedByUid: session.uid,
                  }
                : f
            )
            .sort((a, b) => a.order - b.order),
        };
      }
      const newId = validated.customId || `file_${Date.now()}`;
      if (prev.studyFiles.some((f) => f.id === newId)) {
        throw new Error('المعرّف التقني مستخدم مسبقاً.');
      }
      const created: StudyFileMetadata = {
        id: newId,
        sectionId,
        sectionReferencePath: `sections/${sectionId}`,
        title: validated.name,
        description: normalizedDesc,
        order: validated.order,
        isPublished,
        isArchived: false,
        mimeType: 'application/pdf',
        storageProvider: 'cloudflare_r2',
        activeVersionId: null,
        activeVersionNumber: null,
        activeVersionLabel: null,
        createdByUid: session.uid,
      };
      return {
        ...prev,
        studyFiles: [...prev.studyFiles, created].sort((a, b) => a.order - b.order),
      };
    });
  };

  const toggleStudyFilePublished = (id: string) => {
    setCatalog((prev) => ({
      ...prev,
      studyFiles: prev.studyFiles.map((f) => (f.id === id ? { ...f, isPublished: !f.isPublished } : f)),
    }));
  };

  const toggleStudyFileArchived = (id: string) => {
    setCatalog((prev) => ({
      ...prev,
      studyFiles: prev.studyFiles.map((f) => (f.id === id ? { ...f, isArchived: !f.isArchived } : f)),
    }));
  };

  const moveStudyFile = (id: string, sectionId: string, moveUp: boolean) => {
    setCatalog((prev) => {
      const subset = prev.studyFiles.filter((f) => f.sectionId === sectionId).sort((a, b) => a.order - b.order);
      const idx = subset.findIndex((f) => f.id === id);
      const targetIdx = moveUp ? idx - 1 : idx + 1;
      if (idx < 0 || targetIdx < 0 || targetIdx >= subset.length) return prev;
      const a = subset[idx];
      const b = subset[targetIdx];
      const newOrderA = b.order === a.order ? (moveUp ? a.order - 1 : a.order + 1) : b.order;
      const newOrderB = a.order;
      return {
        ...prev,
        studyFiles: prev.studyFiles
          .map((f) =>
            f.id === a.id ? { ...f, order: newOrderA } : f.id === b.id ? { ...f, order: newOrderB } : f
          )
          .sort((x, y) => x.order - y.order),
      };
    });
  };

  const toggleDownloadFile = (fileId: string): boolean => {
    if (!session.isSignedIn) {
      return false;
    }
    setDownloadedFileIds((prev) =>
      prev.includes(fileId) ? prev.filter((id) => id !== fileId) : [...prev, fileId]
    );
    return true;
  };

  const saveReadingProgress = (fileId: string, lastPageNumber: number, totalPages: number) => {
    if (!fileId || lastPageNumber <= 0) return;
    setReadingProgressMap((prev) => ({
      ...prev,
      [fileId]: {
        fileId,
        lastPageNumber,
        totalPages,
        updatedAtEpochMillis: Date.now(),
      },
    }));
  };

  const value = useMemo<HaqibatiContextValue>(
    () => ({
      preferences,
      toggleDarkMode,
      session,
      signIn,
      signUpStudent,
      sendPasswordReset,
      signOut,
      refreshSession,
      catalog,
      saveBranch,
      toggleBranchPublished,
      moveBranch,
      saveSubject,
      toggleSubjectPublished,
      moveSubject,
      saveSection,
      toggleSectionPublished,
      moveSection,
      saveStudyFile,
      toggleStudyFilePublished,
      toggleStudyFileArchived,
      moveStudyFile,
      downloadedFileIds,
      toggleDownloadFile,
      readingProgressMap,
      saveReadingProgress,
    }),
    [preferences, session, catalog, downloadedFileIds, readingProgressMap]
  );

  return <HaqibatiContext.Provider value={value}>{children}</HaqibatiContext.Provider>;
};

export function useHaqibati(): HaqibatiContextValue {
  const ctx = useContext(HaqibatiContext);
  if (!ctx) {
    throw new Error('useHaqibati must be used inside HaqibatiProvider');
  }
  return ctx;
}
