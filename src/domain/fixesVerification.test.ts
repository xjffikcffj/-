import {
  resolveLibraryBackRoute,
  resolveLibraryNavigation,
  resolvePublishedStudyFile,
  CatalogSnapshot,
} from './catalogNavigation';
import {
  determineMostVisiblePage,
  hasReachedTargetPage,
  isPureHorizontalScroll,
  resolveSearchStatusMessage,
} from './pdfReaderHelpers';
import { resolvePreviewDemoEnabled } from './previewDemoConfig';
import { searchArabicAcrossPages } from './validators';
import {
  SEED_BRANCHES,
  SEED_SECTIONS,
  SEED_STUDY_FILES,
  SEED_SUBJECTS,
} from '../data/seedCatalog';

function assertEqual<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message} (expected: ${String(expected)}, actual: ${String(actual)})`);
  }
}

function assertTrue(condition: boolean, message: string): void {
  if (!condition) {
    throw new Error(message);
  }
}

function runCheck(name: string, fn: () => void): void {
  try {
    fn();
    console.log(`PASS: ${name}`);
  } catch (err) {
    console.error(`FAIL: ${name}`);
    throw err;
  }
}

function makeCatalog(): CatalogSnapshot {
  return {
    branches: SEED_BRANCHES.map((b) => ({ ...b })),
    subjects: SEED_SUBJECTS.map((s) => ({ ...s })),
    sections: SEED_SECTIONS.map((sec) => ({ ...sec })),
    studyFiles: SEED_STUDY_FILES.map((f) => ({ ...f })),
  };
}

runCheck('determineMostVisiblePage tracks natural scroll across viewport transitions', () => {
  const pageRects = [
    { pageNumber: 1, top: -720, bottom: 80 },
    { pageNumber: 2, top: 100, bottom: 900 },
    { pageNumber: 3, top: 920, bottom: 1720 },
  ];

  assertEqual(
    determineMostVisiblePage(0, 700, pageRects),
    2,
    'Expected page 2 to dominate viewport during scroll'
  );

  const lowerScrollRects = [
    { pageNumber: 2, top: -620, bottom: 180 },
    { pageNumber: 3, top: 200, bottom: 1000 },
  ];
  assertEqual(
    determineMostVisiblePage(0, 700, lowerScrollRects),
    3,
    'Expected page 3 to dominate after scrolling further down'
  );
});

runCheck('hasReachedTargetPage keeps programmatic lock until target page arrives in viewport', () => {
  // Mid-animation at page 2 while jumping to page 6 -> must NOT unlock yet.
  assertEqual(
    hasReachedTargetPage({
      containerTop: 0,
      containerBottom: 700,
      containerScrollTop: 900,
      containerMaxScrollTop: 4000,
      targetRect: { pageNumber: 6, top: 2400, bottom: 3200 },
      mostVisiblePage: 2,
      targetPage: 6,
    }),
    false,
    'Should remain locked while page 6 is still below viewport'
  );

  // Target page 6 dominates viewport -> safe to unlock.
  assertEqual(
    hasReachedTargetPage({
      containerTop: 0,
      containerBottom: 700,
      containerScrollTop: 2800,
      containerMaxScrollTop: 4000,
      targetRect: { pageNumber: 6, top: 20, bottom: 820 },
      mostVisiblePage: 6,
      targetPage: 6,
    }),
    true,
    'Should unlock once target page 6 dominates viewport'
  );
});

runCheck('searchArabicAcrossPages returns NO_EXTRACTABLE_TEXT and UI helper provides clear Arabic message', () => {
  const scanResult = searchArabicAcrossPages('الفيزياء', ['   ', '\n\t']);
  assertEqual(scanResult.status, 'NO_EXTRACTABLE_TEXT', 'Expected NO_EXTRACTABLE_TEXT for blank pages');
  assertTrue(
    Boolean(resolveSearchStatusMessage('NO_EXTRACTABLE_TEXT')),
    'Expected Arabic message for NO_EXTRACTABLE_TEXT'
  );
  assertTrue(
    Boolean(resolveSearchStatusMessage('ERROR')),
    'Expected Arabic message for ERROR'
  );
  assertEqual(
    resolveSearchStatusMessage('FOUND'),
    null,
    'Expected no error message when status is FOUND'
  );
});

runCheck('resolvePublishedStudyFile blocks access if file, section, subject, or branch is unpublished', () => {
  const targetFile = SEED_STUDY_FILES[0];
  const baseSection = SEED_SECTIONS.find((sec) => sec.id === targetFile.sectionId)!;
  const baseSubject = SEED_SUBJECTS.find((sub) => sub.id === baseSection.subjectId)!;
  const baseBranch = SEED_BRANCHES.find((b) => b.id === baseSubject.branchId)!;

  assertTrue(
    resolvePublishedStudyFile(makeCatalog(), targetFile.id) !== null,
    'Published file with published ancestors must resolve'
  );

  // Unpublish section -> blocked
  const secUnpublished = makeCatalog();
  secUnpublished.sections = secUnpublished.sections.map((s) =>
    s.id === baseSection.id ? { ...s, isPublished: false } : s
  );
  assertEqual(
    resolvePublishedStudyFile(secUnpublished, targetFile.id),
    null,
    'File under unpublished section must return null'
  );

  // Unpublish subject -> blocked
  const subUnpublished = makeCatalog();
  subUnpublished.subjects = subUnpublished.subjects.map((s) =>
    s.id === baseSubject.id ? { ...s, isPublished: false } : s
  );
  assertEqual(
    resolvePublishedStudyFile(subUnpublished, targetFile.id),
    null,
    'File under unpublished subject must return null'
  );

  // Unpublish branch -> blocked
  const branchUnpublished = makeCatalog();
  branchUnpublished.branches = branchUnpublished.branches.map((b) =>
    b.id === baseBranch.id ? { ...b, isPublished: false } : b
  );
  assertEqual(
    resolvePublishedStudyFile(branchUnpublished, targetFile.id),
    null,
    'File under unpublished branch must return null'
  );

  // Archive file -> blocked
  const archived = makeCatalog();
  archived.studyFiles = archived.studyFiles.map((f) =>
    f.id === targetFile.id ? { ...f, isArchived: true } : f
  );
  assertEqual(
    resolvePublishedStudyFile(archived, targetFile.id),
    null,
    'Archived study file must return null'
  );
});

runCheck('ID-based navigation and unified back route reflect edits and unpublishing cleanly', () => {
  const catalog = makeCatalog();
  const route = {
    level: 'FILES' as const,
    branchId: 'scientific',
    subjectId: 'math_sci',
    sectionId: 'math_calculus',
  };

  catalog.sections = catalog.sections.map((s) =>
    s.id === 'math_calculus' ? { ...s, name: 'التفاضل والتكامل (محدّث)' } : s
  );
  const updatedCtx = resolveLibraryNavigation(catalog, route);
  assertEqual(updatedCtx.isUnavailable, false, 'Section should remain available');
  assertEqual(
    updatedCtx.topBarTitle,
    'التفاضل والتكامل (محدّث)',
    'TopBar title must reflect live updated section name'
  );

  // If section is unpublished, unified back route returns SECTIONS (same for topbar and LibraryScreen CTA).
  catalog.sections = catalog.sections.map((s) =>
    s.id === 'math_calculus' ? { ...s, isPublished: false } : s
  );
  const backFromFiles = resolveLibraryBackRoute(catalog, route);
  assertEqual(backFromFiles.level, 'SECTIONS', 'Should step back cleanly to SECTIONS when subject is still published');

  // If parent subject is unpublished too, unified back route steps cleanly to SUBJECTS.
  catalog.subjects = catalog.subjects.map((sub) =>
    sub.id === 'math_sci' ? { ...sub, isPublished: false } : sub
  );
  const backWhenSubjectUnpublished = resolveLibraryBackRoute(catalog, route);
  assertEqual(
    backWhenSubjectUnpublished.level,
    'SUBJECTS',
    'Should fall back to SUBJECTS when parent subject is unpublished'
  );
});

runCheck('hasReachedTargetPage distinguishes short trailing page arrival from unrelated bottom edge', () => {
  // Jumping to short trailing page 4 at maxScrollTop where page 4 fits inside viewport -> arrived.
  assertEqual(
    hasReachedTargetPage({
      containerTop: 0,
      containerBottom: 700,
      containerScrollTop: 1760,
      containerMaxScrollTop: 1760,
      targetRect: { pageNumber: 4, top: 434, bottom: 684 },
      mostVisiblePage: 3,
      targetPage: 4,
    }),
    true,
    'Short trailing page fully in view at maxScrollTop must count as arrived'
  );

  // Jumping UP from maxScrollTop to page 1 while page 1 is clipped above top edge -> NOT arrived.
  assertEqual(
    hasReachedTargetPage({
      containerTop: 0,
      containerBottom: 700,
      containerScrollTop: 220,
      containerMaxScrollTop: 220,
      targetRect: { pageNumber: 1, top: -220, bottom: 380 },
      mostVisiblePage: 2,
      targetPage: 1,
    }),
    false,
    'Partially-clipped earlier page at bottom edge must NOT falsely unlock as arrived'
  );
});

runCheck('isPureHorizontalScroll isolates zoomed horizontal panning from vertical scroll', () => {
  assertEqual(
    isPureHorizontalScroll({
      prevScrollTop: 500,
      prevScrollLeft: 0,
      nextScrollTop: 500,
      nextScrollLeft: 160,
    }),
    true,
    'ScrollLeft change with stationary scrollTop must be classified as horizontal-only'
  );
  assertEqual(
    isPureHorizontalScroll({
      prevScrollTop: 500,
      prevScrollLeft: 0,
      nextScrollTop: 620,
      nextScrollLeft: 160,
    }),
    false,
    'Scroll involving vertical movement must NOT be ignored as horizontal-only'
  );
});

runCheck('resolvePreviewDemoEnabled blocks production bundles even when NODE_ENV=development', () => {
  assertEqual(
    resolvePreviewDemoEnabled({
      command: 'build',
      mode: 'production',
      nodeEnv: 'development',
      enablePreviewDemoFlag: 'true',
    }),
    false,
    'Production build must never allow preview demo credentials even with NODE_ENV=development'
  );

  assertEqual(
    resolvePreviewDemoEnabled({
      command: 'serve',
      mode: 'development',
      nodeEnv: 'development',
      enablePreviewDemoFlag: undefined,
    }),
    false,
    'Dev mode alone without explicit opt-in flag must not enable demo credentials'
  );

  assertEqual(
    resolvePreviewDemoEnabled({
      command: 'serve',
      mode: 'development',
      nodeEnv: 'development',
      enablePreviewDemoFlag: 'true',
    }),
    true,
    'Explicit dev preview mode with VITE_ENABLE_PREVIEW_DEMO=true must work'
  );
});
