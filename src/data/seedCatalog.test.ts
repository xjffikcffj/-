import { SEED_BRANCHES, SEED_SECTIONS, SEED_SUBJECTS } from './seedCatalog';

function assertEqual<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message} (expected: ${String(expected)}, actual: ${String(actual)})`);
  }
}

function assertArrayEqual(actual: string[], expected: string[], message: string): void {
  if (
    actual.length !== expected.length ||
    actual.some((item, index) => item !== expected[index])
  ) {
    throw new Error(
      `${message}\n  expected: ${JSON.stringify(expected)}\n  actual:   ${JSON.stringify(actual)}`
    );
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

function getPublishedSubjectsForBranch(branchId: string) {
  return SEED_SUBJECTS.filter((s) => s.branchId === branchId && s.isPublished).sort(
    (a, b) => a.order - b.order
  );
}

runCheck('preserves existing scientific and literary branches', () => {
  const branches = [...SEED_BRANCHES].sort((a, b) => a.order - b.order);
  assertEqual(branches.length, 2, 'Expected 2 branches');
  assertEqual(branches[0].id, 'scientific', 'First branch id mismatch');
  assertEqual(branches[0].name, 'الفرع العلمي', 'First branch name mismatch');
  assertEqual(branches[1].id, 'literary', 'Second branch id mismatch');
  assertEqual(branches[1].name, 'الفرع الأدبي', 'Second branch name mismatch');
});

runCheck('contains unique IDs across all 14 subjects', () => {
  assertEqual(SEED_SUBJECTS.length, 14, 'Expected 14 total subjects');
  const uniqueIds = new Set(SEED_SUBJECTS.map((s) => s.id));
  assertEqual(uniqueIds.size, 14, 'Expected all 14 subject IDs to be unique');
});

runCheck('scientific branch has 7 published subjects in exact order', () => {
  const scientific = getPublishedSubjectsForBranch('scientific');
  assertEqual(scientific.length, 7, 'Expected 7 scientific subjects');

  assertArrayEqual(
    scientific.map((s) => s.name),
    [
      'الرياضيات',
      'التربية الإسلامية',
      'اللغة العربية',
      'اللغة الإنكليزية',
      'الكيمياء',
      'الفيزياء',
      'الأحياء',
    ],
    'Scientific subjects order or names mismatch'
  );

  scientific.forEach((subject, idx) => {
    assertEqual(subject.order, idx, `Scientific subject ${subject.id} order mismatch`);
    assertEqual(subject.branchId, 'scientific', `Scientific subject ${subject.id} branchId mismatch`);
    assertEqual(
      subject.branchReferencePath,
      'branches/scientific',
      `Scientific subject ${subject.id} branchReferencePath mismatch`
    );
    assertTrue(subject.isPublished, `Scientific subject ${subject.id} must be published`);
  });
});

runCheck('literary branch has 7 published subjects in exact order', () => {
  const literary = getPublishedSubjectsForBranch('literary');
  assertEqual(literary.length, 7, 'Expected 7 literary subjects');

  assertArrayEqual(
    literary.map((s) => s.name),
    [
      'التربية الإسلامية',
      'اللغة العربية',
      'اللغة الإنكليزية',
      'الرياضيات',
      'الجغرافية',
      'التاريخ',
      'الاقتصاد',
    ],
    'Literary subjects order or names mismatch'
  );

  literary.forEach((subject, idx) => {
    assertEqual(subject.order, idx, `Literary subject ${subject.id} order mismatch`);
    assertEqual(subject.branchId, 'literary', `Literary subject ${subject.id} branchId mismatch`);
    assertEqual(
      subject.branchReferencePath,
      'branches/literary',
      `Literary subject ${subject.id} branchReferencePath mismatch`
    );
    assertTrue(subject.isPublished, `Literary subject ${subject.id} must be published`);
  });
});

runCheck('all SEED_SECTIONS remain linked to valid subjects in SEED_SUBJECTS', () => {
  const subjectIds = new Set(SEED_SUBJECTS.map((s) => s.id));
  SEED_SECTIONS.forEach((section) => {
    assertTrue(
      subjectIds.has(section.subjectId),
      `Section ${section.id} references missing subject ${section.subjectId}`
    );
    assertEqual(
      section.subjectReferencePath,
      `subjects/${section.subjectId}`,
      `Section ${section.id} subjectReferencePath mismatch`
    );
  });
});
