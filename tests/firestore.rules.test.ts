import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  type Firestore,
} from 'firebase/firestore';

const PROJECT_ID = 'student-learning-platform-2026';

function resolveEmulatorHostPort(): { host: string; port: number } {
  const envHost = process.env.FIRESTORE_EMULATOR_HOST;
  if (envHost && envHost.includes(':')) {
    const [host, rawPort] = envHost.split(':');
    const parsedPort = Number(rawPort);
    if (host && Number.isFinite(parsedPort)) {
      return { host, port: parsedPort };
    }
  }
  return { host: '127.0.0.1', port: 8080 };
}

let testEnv: RulesTestEnvironment;

function ownerDb(): Firestore {
  return testEnv
    .authenticatedContext('owner-1', { role: 'owner' })
    .firestore() as unknown as Firestore;
}

function adminDb(): Firestore {
  return testEnv
    .authenticatedContext('admin-1', { role: 'admin' })
    .firestore() as unknown as Firestore;
}

function studentDb(): Firestore {
  return testEnv
    .authenticatedContext('student-1', { role: 'student' })
    .firestore() as unknown as Firestore;
}

function anonymousDb(): Firestore {
  return testEnv
    .authenticatedContext('anon-1', {
      firebase: { sign_in_provider: 'anonymous' },
    })
    .firestore() as unknown as Firestore;
}

function guestDb(): Firestore {
  return testEnv.unauthenticatedContext().firestore() as unknown as Firestore;
}

async function seedCatalogHierarchy(options?: {
  subjectPublished?: boolean;
  filePublished?: boolean;
}) {
  const subjectPublished = options?.subjectPublished ?? false;
  const filePublished = options?.filePublished ?? false;

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore() as unknown as Firestore;
    const now = Timestamp.fromMillis(1_700_000_000_000);

    await setDoc(doc(db, 'branches', 'branch-1'), {
      name: 'الفرع العلمي',
      order: 0,
      isPublished: true,
      createdByUid: 'owner-1',
      updatedByUid: 'owner-1',
      createdAt: now,
      updatedAt: now,
    });

    await setDoc(doc(db, 'subjects', 'subj-1'), {
      branchId: doc(db, 'branches', 'branch-1'),
      name: 'الفيزياء',
      order: 0,
      isPublished: subjectPublished,
      createdByUid: 'owner-1',
      updatedByUid: 'owner-1',
      createdAt: now,
      updatedAt: now,
    });

    await setDoc(doc(db, 'sections', 'sec-1'), {
      subjectId: doc(db, 'subjects', 'subj-1'),
      name: 'الميكانيك',
      order: 0,
      isPublished: true,
      createdByUid: 'owner-1',
      updatedByUid: 'owner-1',
      createdAt: now,
      updatedAt: now,
    });

    await setDoc(doc(db, 'study_files', 'file-1'), {
      sectionId: doc(db, 'sections', 'sec-1'),
      title: 'ملخص الحركة',
      description: 'شرح القوانين الأساسية',
      order: 0,
      isPublished: filePublished,
      isArchived: false,
      mimeType: 'application/pdf',
      storageProvider: 'cloudflare_r2',
      activeVersionId: filePublished ? 'ver-1' : null,
      activeVersionNumber: filePublished ? 1 : null,
      activeVersionLabel: filePublished ? 'الإصدار 1' : null,
      activeVersionPublishedAt: filePublished ? now : null,
      registeredVersionNumbers: [1],
      createdByUid: 'admin-1',
      updatedByUid: 'admin-1',
      createdAt: now,
      updatedAt: now,
    });

    await setDoc(doc(db, 'study_files', 'file-1', 'versions', 'ver-1'), {
      versionNumber: 1,
      versionLabel: 'الإصدار 1',
      notes: 'نسخة أولى',
      isPublished: filePublished,
      isArchived: false,
      r2ObjectKey: 'study-files/file-1/versions/ver-1/abcdef0123456789_sess_att.pdf',
      fileSizeBytes: 102400,
      checksumSha256: 'a'.repeat(64),
      binaryVerificationStatus: 'VERIFIED',
      createdByUid: 'admin-1',
      updatedByUid: 'admin-1',
      createdAt: now,
      updatedAt: now,
    });

    await setDoc(doc(db, 'upload_sessions', 'sess-1'), {
      fileId: 'file-1',
      versionId: 'ver-1',
      status: 'PENDING',
      createdByUid: 'admin-1',
    });
  });
}

describe('Haqibati Firestore Security Rules (Stage 8 - D1)', () => {
  before(async () => {
    const { host, port } = resolveEmulatorHostPort();
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        rules: readFileSync('firestore.rules', 'utf8'),
        host,
        port,
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  // =========================================================================
  // 1 & 2. Role Matrix & Total Block of Student / Guest / Unknown Collections
  // =========================================================================
  describe('1 & 2. RBAC Read/Write Matrix (Owner, Admin, Student, Anonymous, Guest)', () => {
    it('blocks student, anonymous, and unauthenticated guest from reading or writing all collections', async () => {
      await seedCatalogHierarchy({ subjectPublished: true, filePublished: true });

      const blockedClients = [
        { name: 'student', db: studentDb() },
        { name: 'anonymous', db: anonymousDb() },
        { name: 'guest', db: guestDb() },
      ];

      for (const client of blockedClients) {
        // Direct document reads & collection queries
        await assertFails(getDoc(doc(client.db, 'branches', 'branch-1')));
        await assertFails(getDocs(collection(client.db, 'branches')));
        await assertFails(getDoc(doc(client.db, 'subjects', 'subj-1')));
        await assertFails(getDocs(collection(client.db, 'subjects')));
        await assertFails(getDoc(doc(client.db, 'sections', 'sec-1')));
        await assertFails(getDocs(collection(client.db, 'sections')));
        await assertFails(getDoc(doc(client.db, 'study_files', 'file-1')));
        await assertFails(getDocs(collection(client.db, 'study_files')));
        await assertFails(getDoc(doc(client.db, 'study_files', 'file-1', 'versions', 'ver-1')));
        await assertFails(getDoc(doc(client.db, 'upload_sessions', 'sess-1')));
        await assertFails(getDoc(doc(client.db, 'unknown_collection', 'doc-1')));

        // Direct writes
        await assertFails(
          setDoc(doc(client.db, 'branches', 'b-new'), {
            name: 'فرع غير مصرح',
            order: 1,
            isPublished: false,
            createdByUid: 'student-1',
            updatedByUid: 'student-1',
            createdAt: serverTimestamp(),
            updatedAt: serverTimestamp(),
          }),
        );
        await assertFails(
          updateDoc(doc(client.db, 'study_files', 'file-1'), {
            title: 'تعديل مرفوض',
          }),
        );
        await assertFails(
          setDoc(doc(client.db, 'unknown_collection', 'doc-1'), { foo: 'bar' }),
        );
      }
    });

    it('allows Owner and Admin to read catalog collections & versions, while closing unknown collections & upload_sessions', async () => {
      await seedCatalogHierarchy({ subjectPublished: true, filePublished: true });

      for (const db of [ownerDb(), adminDb()]) {
        await assertSucceeds(getDoc(doc(db, 'branches', 'branch-1')));
        await assertSucceeds(getDoc(doc(db, 'subjects', 'subj-1')));
        await assertSucceeds(getDoc(doc(db, 'sections', 'sec-1')));
        await assertSucceeds(getDoc(doc(db, 'study_files', 'file-1')));
        await assertSucceeds(getDoc(doc(db, 'study_files', 'file-1', 'versions', 'ver-1')));

        // Closed collections even for owner/admin
        await assertFails(getDoc(doc(db, 'upload_sessions', 'sess-1')));
        await assertFails(
          setDoc(doc(db, 'upload_sessions', 'sess-2'), { status: 'PENDING' }),
        );
        await assertFails(getDoc(doc(db, 'unknown_collection', 'any-1')));
        await assertFails(
          setDoc(doc(db, 'unknown_collection', 'any-1'), { a: 1 }),
        );
      }
    });
  });

  // =========================================================================
  // 3. Blocking Client Tampering of Protected / Server-Owned Fields & Versions
  // =========================================================================
  describe('3. Immutability of Versions, Publish State, Active Version & R2 Proof Fields', () => {
    it('blocks both Owner and Admin from creating or updating versions subcollection documents', async () => {
      await seedCatalogHierarchy();

      for (const db of [ownerDb(), adminDb()]) {
        await assertFails(
          setDoc(doc(db, 'study_files', 'file-1', 'versions', 'ver-2'), {
            versionNumber: 2,
            versionLabel: 'تزوير',
            isPublished: false,
            isArchived: false,
          }),
        );
        await assertFails(
          updateDoc(doc(db, 'study_files', 'file-1', 'versions', 'ver-1'), {
            versionLabel: 'تعديل مرفوض',
          }),
        );
        await assertFails(
          updateDoc(doc(db, 'study_files', 'file-1', 'versions', 'ver-1'), {
            r2ObjectKey: 'study-files/tampered.pdf',
            checksumSha256: 'b'.repeat(64),
            fileSizeBytes: 999,
          }),
        );
      }
    });

    it('blocks both Owner and Admin from modifying activeVersion*, registeredVersionNumbers, or setting study_files.isPublished = true', async () => {
      await seedCatalogHierarchy({ filePublished: false });

      const forbiddenStudyFileUpdates: Array<Record<string, unknown>> = [
        {
          isPublished: true,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
        {
          activeVersionId: 'ver-1',
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
        {
          activeVersionNumber: 1,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
        {
          activeVersionLabel: 'الإصدار 1',
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
        {
          activeVersionPublishedAt: serverTimestamp(),
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
        {
          registeredVersionNumbers: [1, 2],
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
        {
          r2ObjectKey: 'study-files/injected.pdf',
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
        {
          checksumSha256: 'c'.repeat(64),
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
        {
          fileSizeBytes: 12345,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
        {
          mimeType: 'text/plain',
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
        {
          storageProvider: 'firebase_storage',
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        },
      ];

      for (const payload of forbiddenStudyFileUpdates) {
        await assertFails(updateDoc(doc(ownerDb(), 'study_files', 'file-1'), payload));
        await assertFails(
          updateDoc(doc(adminDb(), 'study_files', 'file-1'), {
            ...payload,
            updatedByUid: 'admin-1',
          }),
        );
      }
    });

    it('rejects study_files.create when trying to spoof published state, non-null activeVersion*, or extra keys', async () => {
      await seedCatalogHierarchy();

      const validDraft = {
        sectionId: doc(adminDb(), 'sections', 'sec-1'),
        title: 'ملف دراسة جديد',
        description: null,
        order: 1,
        isPublished: false,
        isArchived: false,
        mimeType: 'application/pdf',
        storageProvider: 'cloudflare_r2',
        activeVersionId: null,
        activeVersionNumber: null,
        activeVersionLabel: null,
        activeVersionPublishedAt: null,
        createdByUid: 'admin-1',
        updatedByUid: 'admin-1',
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      };

      await assertFails(
        setDoc(doc(adminDb(), 'study_files', 'file-bad-1'), {
          ...validDraft,
          isPublished: true,
        }),
      );
      await assertFails(
        setDoc(doc(adminDb(), 'study_files', 'file-bad-2'), {
          ...validDraft,
          activeVersionId: 'ver-spoofed',
        }),
      );
      await assertFails(
        setDoc(doc(adminDb(), 'study_files', 'file-bad-3'), {
          ...validDraft,
          registeredVersionNumbers: [1],
        }),
      );
    });
  });

  // =========================================================================
  // 4. Exact Valid Android Payloads & Invalid Field Validation
  // =========================================================================
  describe('4. Valid Android Payloads vs Malformed / Extra / Oversized Fields', () => {
    it('accepts full valid hierarchy creation matching exact Android payloads', async () => {
      await assertSucceeds(
        setDoc(doc(ownerDb(), 'branches', 'branch-android-1'), {
          name: 'الأحيائي',
          order: 0,
          isPublished: false,
          createdByUid: 'owner-1',
          updatedByUid: 'owner-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      await assertSucceeds(
        setDoc(doc(adminDb(), 'subjects', 'subj-android-1'), {
          branchId: doc(adminDb(), 'branches', 'branch-android-1'),
          name: 'الكيمياء',
          order: 0,
          isPublished: false,
          createdByUid: 'admin-1',
          updatedByUid: 'admin-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      await assertSucceeds(
        setDoc(doc(adminDb(), 'sections', 'sec-android-1'), {
          subjectId: doc(adminDb(), 'subjects', 'subj-android-1'),
          name: 'الكيمياء العضوية',
          order: 0,
          isPublished: true,
          createdByUid: 'admin-1',
          updatedByUid: 'admin-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      // With description: null
      await assertSucceeds(
        setDoc(doc(adminDb(), 'study_files', 'file-android-null'), {
          sectionId: doc(adminDb(), 'sections', 'sec-android-1'),
          title: 'ملزمة العضوية',
          description: null,
          order: 0,
          isPublished: false,
          isArchived: false,
          mimeType: 'application/pdf',
          storageProvider: 'cloudflare_r2',
          activeVersionId: null,
          activeVersionNumber: null,
          activeVersionLabel: null,
          activeVersionPublishedAt: null,
          createdByUid: 'admin-1',
          updatedByUid: 'admin-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      // With non-null description
      await assertSucceeds(
        setDoc(doc(ownerDb(), 'study_files', 'file-android-desc'), {
          sectionId: doc(ownerDb(), 'sections', 'sec-android-1'),
          title: 'أسئلة وزارية',
          description: 'مجموعة مسائل محلولة مع الملاحظات',
          order: 1,
          isPublished: false,
          isArchived: false,
          mimeType: 'application/pdf',
          storageProvider: 'cloudflare_r2',
          activeVersionId: null,
          activeVersionNumber: null,
          activeVersionLabel: null,
          activeVersionPublishedAt: null,
          createdByUid: 'owner-1',
          updatedByUid: 'owner-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );
    });

    it('allows study_files metadata update, archive, restore (keeping isPublished=false), and emergency unpublish', async () => {
      await seedCatalogHierarchy({ filePublished: true });

      // Update metadata & order
      await assertSucceeds(
        updateDoc(doc(adminDb(), 'study_files', 'file-1'), {
          title: 'ملخص الحركة المحدث',
          description: null,
          order: 3,
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Archive atomically sets isArchived=true, isPublished=false
      await assertSucceeds(
        updateDoc(doc(adminDb(), 'study_files', 'file-1'), {
          isArchived: true,
          isPublished: false,
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Restore keeps isPublished=false
      await assertSucceeds(
        updateDoc(doc(adminDb(), 'study_files', 'file-1'), {
          isArchived: false,
          isPublished: false,
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Trying to restore + publish at once MUST fail
      await assertFails(
        updateDoc(doc(ownerDb(), 'study_files', 'file-1'), {
          isArchived: false,
          isPublished: true,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Emergency unpublish (isPublished=false) MUST succeed
      await assertSucceeds(
        updateDoc(doc(adminDb(), 'study_files', 'file-1'), {
          isPublished: false,
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );
    });

    it('rejects oversized strings, negative order, extra fields, client timestamps, uid spoofing, and invalid doc IDs', async () => {
      await seedCatalogHierarchy();

      // Name > 120 chars
      await assertFails(
        setDoc(doc(ownerDb(), 'branches', 'b-long-name'), {
          name: 'أ'.repeat(121),
          order: 0,
          isPublished: false,
          createdByUid: 'owner-1',
          updatedByUid: 'owner-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      // Description > 1000 chars
      await assertFails(
        updateDoc(doc(adminDb(), 'study_files', 'file-1'), {
          description: 'س'.repeat(1001),
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Negative order
      await assertFails(
        updateDoc(doc(ownerDb(), 'branches', 'branch-1'), {
          order: -1,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Extra unexpected key on branch update
      await assertFails(
        updateDoc(doc(ownerDb(), 'branches', 'branch-1'), {
          name: 'اسم جديد',
          extraHack: true,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Spoofed updatedByUid or createdAt (non-serverTimestamp)
      await assertFails(
        setDoc(doc(ownerDb(), 'branches', 'b-spoof-uid'), {
          name: 'فرع',
          order: 0,
          isPublished: false,
          createdByUid: 'someone-else',
          updatedByUid: 'owner-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      await assertFails(
        setDoc(doc(ownerDb(), 'branches', 'b-client-time'), {
          name: 'فرع',
          order: 0,
          isPublished: false,
          createdByUid: 'owner-1',
          updatedByUid: 'owner-1',
          createdAt: Timestamp.fromMillis(1_700_000_000_000),
          updatedAt: serverTimestamp(),
        }),
      );

      // Invalid document ID with spaces
      await assertFails(
        setDoc(doc(ownerDb(), 'branches', 'invalid branch id'), {
          name: 'فرع',
          order: 0,
          isPublished: false,
          createdByUid: 'owner-1',
          updatedByUid: 'owner-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );
    });
  });

  // =========================================================================
  // 5 & 6. Legacy Documents Compatibility vs Strict New Document Creation
  // =========================================================================
  describe('5 & 6. Legacy Docs Update/Reorder Compatibility & Strict New Doc Enforcement', () => {
    it('allows Owner to reorder and toggle publish on a legacy branch lacking name, createdByUid, and createdAt', async () => {
      await testEnv.withSecurityRulesDisabled(async (ctx) => {
        const db = ctx.firestore() as unknown as Firestore;
        await setDoc(doc(db, 'branches', 'legacy-branch-1'), {
          nameAr: 'الفرع التطبيقي القديم',
          order: 0,
          isPublished: true,
        });
      });

      // Reorder legacy branch without adding name or createdByUid/createdAt
      await assertSucceeds(
        updateDoc(doc(ownerDb(), 'branches', 'legacy-branch-1'), {
          order: 4,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Unpublish legacy branch without forcing schema backfill in same client write
      await assertSucceeds(
        updateDoc(doc(ownerDb(), 'branches', 'legacy-branch-1'), {
          isPublished: false,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Reject inventing/backfilling createdByUid or createdAt on legacy doc from client
      await assertFails(
        updateDoc(doc(ownerDb(), 'branches', 'legacy-branch-1'), {
          order: 5,
          createdByUid: 'owner-1',
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );
    });

    it('allows reordering legacy study_files with nameAr/name (no title) & blocks altering createdByUid/createdAt on modern docs', async () => {
      await seedCatalogHierarchy();

      await testEnv.withSecurityRulesDisabled(async (ctx) => {
        const db = ctx.firestore() as unknown as Firestore;
        await setDoc(doc(db, 'study_files', 'legacy-file-1'), {
          sectionId: doc(db, 'sections', 'sec-1'),
          nameAr: 'ملف قديم محفوظ',
          order: 0,
          isPublished: false,
          isArchived: false,
        });
      });

      // Reorder legacy study file cleanly
      await assertSucceeds(
        updateDoc(doc(adminDb(), 'study_files', 'legacy-file-1'), {
          order: 2,
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Block modifying existing createdByUid on modern doc
      await assertFails(
        updateDoc(doc(ownerDb(), 'branches', 'branch-1'), {
          order: 2,
          createdByUid: 'admin-1',
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );
    });

    it('rejects creating NEW documents missing name, createdByUid, or createdAt (or using legacy nameAr)', async () => {
      await seedCatalogHierarchy();

      // Legacy-shaped create on branches MUST FAIL
      await assertFails(
        setDoc(doc(ownerDb(), 'branches', 'new-bad-legacy-shape'), {
          nameAr: 'اسم قديم',
          order: 0,
          isPublished: false,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Missing createdAt
      await assertFails(
        setDoc(doc(ownerDb(), 'branches', 'new-missing-createdAt'), {
          name: 'فرع جديد',
          order: 0,
          isPublished: false,
          createdByUid: 'owner-1',
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Missing createdByUid on subject
      await assertFails(
        setDoc(doc(adminDb(), 'subjects', 'new-subj-missing-audit'), {
          branchId: doc(adminDb(), 'branches', 'branch-1'),
          name: 'أحياء',
          order: 0,
          isPublished: false,
          updatedByUid: 'admin-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );
    });
  });

  // =========================================================================
  // 7. Strict DocumentReference Validation
  // =========================================================================
  describe('7. Strict DocumentReference Validation on Subjects, Sections & Study Files', () => {
    it('accepts valid DocumentReference and rejects plain strings, wrong collections, nested subcollections, and non-existent targets', async () => {
      await seedCatalogHierarchy();

      // 7a. Plain string instead of DocumentReference -> REJECT
      await assertFails(
        setDoc(doc(adminDb(), 'subjects', 'subj-str-ref'), {
          branchId: 'branches/branch-1',
          name: 'مادة بنص',
          order: 1,
          isPublished: false,
          createdByUid: 'admin-1',
          updatedByUid: 'admin-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      // 7b. Wrong collection DocumentReference (sections instead of branches) -> REJECT
      await assertFails(
        setDoc(doc(adminDb(), 'subjects', 'subj-wrong-col'), {
          branchId: doc(adminDb(), 'sections', 'sec-1'),
          name: 'مادة بمرجع خاطئ',
          order: 1,
          isPublished: false,
          createdByUid: 'admin-1',
          updatedByUid: 'admin-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      // 7c. Non-existent target branch DocumentReference -> REJECT
      await assertFails(
        setDoc(doc(adminDb(), 'subjects', 'subj-missing-target'), {
          branchId: doc(adminDb(), 'branches', 'non-existent-branch'),
          name: 'مادة لفرع غير موجود',
          order: 1,
          isPublished: false,
          createdByUid: 'admin-1',
          updatedByUid: 'admin-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      // 7d. Nested subcollection DocumentReference -> REJECT
      await assertFails(
        setDoc(doc(adminDb(), 'study_files', 'file-nested-ref'), {
          sectionId: doc(adminDb(), 'study_files', 'file-1', 'versions', 'ver-1'),
          title: 'ملف بمرجع متداخل',
          description: null,
          order: 1,
          isPublished: false,
          isArchived: false,
          mimeType: 'application/pdf',
          storageProvider: 'cloudflare_r2',
          activeVersionId: null,
          activeVersionNumber: null,
          activeVersionLabel: null,
          activeVersionPublishedAt: null,
          createdByUid: 'admin-1',
          updatedByUid: 'admin-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      // 7e. Forbidden extra branchId field on sections -> REJECT
      await assertFails(
        setDoc(doc(adminDb(), 'sections', 'sec-extra-branch'), {
          subjectId: doc(adminDb(), 'subjects', 'subj-1'),
          branchId: doc(adminDb(), 'branches', 'branch-1'),
          name: 'قسم مع حقل ممنوع',
          order: 1,
          isPublished: false,
          createdByUid: 'admin-1',
          updatedByUid: 'admin-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );
    });
  });

  // =========================================================================
  // 8. Admin vs Owner Differences (Branches & Subject Unpublishing)
  // =========================================================================
  describe('8. Owner vs Admin Role Boundaries', () => {
    it('blocks Admin from creating or updating branches while allowing Owner', async () => {
      await seedCatalogHierarchy();

      // Admin create branch -> REJECT
      await assertFails(
        setDoc(doc(adminDb(), 'branches', 'branch-by-admin'), {
          name: 'فرع من مشرف',
          order: 1,
          isPublished: false,
          createdByUid: 'admin-1',
          updatedByUid: 'admin-1',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        }),
      );

      // Admin update branch -> REJECT
      await assertFails(
        updateDoc(doc(adminDb(), 'branches', 'branch-1'), {
          name: 'تعديل مشرف',
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Owner update branch -> ALLOW
      await assertSucceeds(
        updateDoc(doc(ownerDb(), 'branches', 'branch-1'), {
          name: 'الفرع العلمي المحدث',
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );
    });

    it('blocks Admin from unpublishing a published subject (true -> false) while allowing rename/reorder/publish and Owner unpublish', async () => {
      // Seed unpublished subject first: Admin CAN publish it
      await seedCatalogHierarchy({ subjectPublished: false });

      await assertSucceeds(
        updateDoc(doc(adminDb(), 'subjects', 'subj-1'), {
          isPublished: true,
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Admin CAN edit name & order of an already published subject
      await assertSucceeds(
        updateDoc(doc(adminDb(), 'subjects', 'subj-1'), {
          name: 'الفيزياء الحديثة',
          order: 2,
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Admin CANNOT unpublish (true -> false)
      await assertFails(
        updateDoc(doc(adminDb(), 'subjects', 'subj-1'), {
          isPublished: false,
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Owner CAN unpublish (true -> false)
      await assertSucceeds(
        updateDoc(doc(ownerDb(), 'subjects', 'subj-1'), {
          isPublished: false,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );
    });
  });

  // =========================================================================
  // 9. Direct Client Delete Prohibited Everywhere
  // =========================================================================
  describe('9. Direct Client Delete Prohibited on All Collections', () => {
    it('blocks delete on all collections even when requested by Owner', async () => {
      await seedCatalogHierarchy({ subjectPublished: true, filePublished: true });

      const targets = [
        doc(ownerDb(), 'branches', 'branch-1'),
        doc(ownerDb(), 'subjects', 'subj-1'),
        doc(ownerDb(), 'sections', 'sec-1'),
        doc(ownerDb(), 'study_files', 'file-1'),
        doc(ownerDb(), 'study_files', 'file-1', 'versions', 'ver-1'),
        doc(ownerDb(), 'upload_sessions', 'sess-1'),
      ];

      for (const ref of targets) {
        await assertFails(deleteDoc(ref));
      }
    });
  });

  // =========================================================================
  // 10. Study File Archive Tri-State Hardening (Fix Pack 2G — F-2G-2)
  // =========================================================================
  describe('10. Study File Archive Rules & Tri-State Hardening (F-2G-2)', () => {
    it('blocks modifying isArchived when current resource isArchived is missing, null, or non-boolean', async () => {
      await seedCatalogHierarchy({ subjectPublished: true, filePublished: false });

      await testEnv.withSecurityRulesDisabled(async (ctx) => {
        const db = ctx.firestore() as unknown as Firestore;
        const now = Timestamp.fromMillis(1_700_000_000_000);
        const baseDoc = {
          sectionId: doc(db, 'sections', 'sec-1'),
          description: 'وثيقة قديمة',
          order: 1,
          isPublished: true,
          mimeType: 'application/pdf',
          storageProvider: 'cloudflare_r2',
          activeVersionId: 'ver-1',
          activeVersionNumber: 1,
          activeVersionLabel: '1.0',
          activeVersionPublishedAt: now,
          createdByUid: 'admin-1',
          updatedByUid: 'admin-1',
          createdAt: now,
          updatedAt: now,
        };

        await setDoc(doc(db, 'study_files', 'file-missing-archived'), {
          ...baseDoc,
          title: 'ملف بدون حقل أرشفة',
        });

        await setDoc(doc(db, 'study_files', 'file-null-archived'), {
          ...baseDoc,
          title: 'ملف بحقل أرشفة فارغ',
          isArchived: null,
        });

        await setDoc(doc(db, 'study_files', 'file-string-archived'), {
          ...baseDoc,
          title: 'ملف بنص أرشفة',
          isArchived: 'false',
        });
      });

      const unspecifiedIds = [
        'file-missing-archived',
        'file-null-archived',
        'file-string-archived',
      ];

      for (const id of unspecifiedIds) {
        // Cannot set isArchived = false on UNSPECIFIED document
        await assertFails(
          updateDoc(doc(ownerDb(), 'study_files', id), {
            isArchived: false,
            isPublished: false,
            updatedByUid: 'owner-1',
            updatedAt: serverTimestamp(),
          }),
        );

        // Cannot set isArchived = true on UNSPECIFIED document
        await assertFails(
          updateDoc(doc(adminDb(), 'study_files', id), {
            isArchived: true,
            isPublished: false,
            updatedByUid: 'admin-1',
            updatedAt: serverTimestamp(),
          }),
        );

        // Non-sensitive metadata edits and explicit unpublish remain allowed on legacy docs
        await assertSucceeds(
          updateDoc(doc(adminDb(), 'study_files', id), {
            title: 'عنوان مراجع',
            updatedByUid: 'admin-1',
            updatedAt: serverTimestamp(),
          }),
        );
        await assertSucceeds(
          updateDoc(doc(adminDb(), 'study_files', id), {
            isPublished: false,
            updatedByUid: 'admin-1',
            updatedAt: serverTimestamp(),
          }),
        );
      }
    });

    it('allows explicit boolean archive/restore transitions while blocking restore combined with publish', async () => {
      await seedCatalogHierarchy({ subjectPublished: true, filePublished: true });

      // Published file with explicit isArchived: false CAN be archived atomically (isPublished -> false)
      await assertSucceeds(
        updateDoc(doc(adminDb(), 'study_files', 'file-1'), {
          isArchived: true,
          isPublished: false,
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Archived file (isArchived: true) CANNOT be restored simultaneously with isPublished: true
      await assertFails(
        updateDoc(doc(ownerDb(), 'study_files', 'file-1'), {
          isArchived: false,
          isPublished: true,
          updatedByUid: 'owner-1',
          updatedAt: serverTimestamp(),
        }),
      );

      // Archived file (isArchived: true) CAN be restored while keeping isPublished: false
      await assertSucceeds(
        updateDoc(doc(adminDb(), 'study_files', 'file-1'), {
          isArchived: false,
          isPublished: false,
          updatedByUid: 'admin-1',
          updatedAt: serverTimestamp(),
        }),
      );
    });
  });
});
