import {
  StudyBranch,
  StudyFileMetadata,
  StudySection,
  StudySubject,
} from './models';

export interface CatalogSnapshot {
  branches: StudyBranch[];
  subjects: StudySubject[];
  sections: StudySection[];
  studyFiles: StudyFileMetadata[];
}

export type LibraryRoute =
  | { level: 'BRANCHES' }
  | { level: 'SUBJECTS'; branchId: string }
  | { level: 'SECTIONS'; branchId: string; subjectId: string }
  | { level: 'FILES'; branchId: string; subjectId: string; sectionId: string };

export interface ResolvedLibraryContext {
  level: LibraryRoute['level'];
  branch: StudyBranch | null;
  subject: StudySubject | null;
  section: StudySection | null;
  branches: StudyBranch[];
  subjects: StudySubject[];
  sections: StudySection[];
  files: StudyFileMetadata[];
  topBarTitle: string;
  isUnavailable: boolean;
}

export function resolveLibraryNavigation(
  catalog: CatalogSnapshot,
  route: LibraryRoute
): ResolvedLibraryContext {
  const publishedBranches = catalog.branches
    .filter((b) => b.isPublished)
    .sort((a, b) => a.order - b.order);

  if (route.level === 'BRANCHES') {
    return {
      level: 'BRANCHES',
      branch: null,
      subject: null,
      section: null,
      branches: publishedBranches,
      subjects: [],
      sections: [],
      files: [],
      topBarTitle: 'المكتبة',
      isUnavailable: false,
    };
  }

  const branch = publishedBranches.find((b) => b.id === route.branchId) ?? null;
  if (!branch) {
    return {
      level: route.level,
      branch: null,
      subject: null,
      section: null,
      branches: publishedBranches,
      subjects: [],
      sections: [],
      files: [],
      topBarTitle: 'المكتبة',
      isUnavailable: true,
    };
  }

  const publishedSubjects = catalog.subjects
    .filter((s) => s.branchId === branch.id && s.isPublished)
    .sort((a, b) => a.order - b.order);

  if (route.level === 'SUBJECTS') {
    return {
      level: 'SUBJECTS',
      branch,
      subject: null,
      section: null,
      branches: publishedBranches,
      subjects: publishedSubjects,
      sections: [],
      files: [],
      topBarTitle: branch.name,
      isUnavailable: false,
    };
  }

  const subject = publishedSubjects.find((s) => s.id === route.subjectId) ?? null;
  if (!subject) {
    return {
      level: route.level,
      branch,
      subject: null,
      section: null,
      branches: publishedBranches,
      subjects: publishedSubjects,
      sections: [],
      files: [],
      topBarTitle: branch.name,
      isUnavailable: true,
    };
  }

  const publishedSections = catalog.sections
    .filter((sec) => sec.subjectId === subject.id && sec.isPublished)
    .sort((a, b) => a.order - b.order);

  if (route.level === 'SECTIONS') {
    return {
      level: 'SECTIONS',
      branch,
      subject,
      section: null,
      branches: publishedBranches,
      subjects: publishedSubjects,
      sections: publishedSections,
      files: [],
      topBarTitle: subject.name,
      isUnavailable: false,
    };
  }

  const section = publishedSections.find((sec) => sec.id === route.sectionId) ?? null;
  if (!section) {
    return {
      level: 'FILES',
      branch,
      subject,
      section: null,
      branches: publishedBranches,
      subjects: publishedSubjects,
      sections: publishedSections,
      files: [],
      topBarTitle: subject.name,
      isUnavailable: true,
    };
  }

  const publishedFiles = catalog.studyFiles
    .filter((f) => f.sectionId === section.id && f.isPublished && !f.isArchived)
    .sort((a, b) => a.order - b.order);

  return {
    level: 'FILES',
    branch,
    subject,
    section,
    branches: publishedBranches,
    subjects: publishedSubjects,
    sections: publishedSections,
    files: publishedFiles,
    topBarTitle: section.name,
    isUnavailable: false,
  };
}

export function resolveLibraryBackRoute(
  catalog: CatalogSnapshot,
  route: LibraryRoute
): LibraryRoute {
  if (route.level === 'BRANCHES' || route.level === 'SUBJECTS') {
    return { level: 'BRANCHES' };
  }

  const branchPublished = catalog.branches.some(
    (b) => b.id === route.branchId && b.isPublished
  );
  if (!branchPublished) {
    return { level: 'BRANCHES' };
  }

  if (route.level === 'SECTIONS') {
    return { level: 'SUBJECTS', branchId: route.branchId };
  }

  const subjectPublished = catalog.subjects.some(
    (s) => s.id === route.subjectId && s.branchId === route.branchId && s.isPublished
  );
  if (!subjectPublished) {
    return { level: 'SUBJECTS', branchId: route.branchId };
  }

  return {
    level: 'SECTIONS',
    branchId: route.branchId,
    subjectId: route.subjectId,
  };
}

export function resolvePublishedStudyFile(
  catalog: CatalogSnapshot,
  fileId: string
): StudyFileMetadata | null {
  if (!fileId) return null;

  const file = catalog.studyFiles.find(
    (f) => f.id === fileId && f.isPublished && !f.isArchived
  );
  if (!file) return null;

  const section = catalog.sections.find(
    (sec) => sec.id === file.sectionId && sec.isPublished
  );
  if (!section) return null;

  const subject = catalog.subjects.find(
    (sub) => sub.id === section.subjectId && sub.isPublished
  );
  if (!subject) return null;

  const branch = catalog.branches.find(
    (b) => b.id === subject.branchId && b.isPublished
  );
  if (!branch) return null;

  return file;
}
