import React, { useState } from 'react';
import {
  Archive,
  ArchiveRestore,
  ArrowDown,
  ArrowUp,
  Edit3,
  FileText,
  Lock,
  LogOut,
  Plus,
  RefreshCw,
  Shield,
  ShieldAlert,
  X,
} from 'lucide-react';
import {
  AdminManagementSection,
  StudyBranch,
  StudyFileMetadata,
  StudySection,
  StudySubject,
  canPerformAdminActions,
  hasRecognizedAdminRole,
} from '../domain/models';
import { useHaqibati } from '../state/HaqibatiContext';
import {
  AuthFeedbackBanner,
  HaqibatiSurfaceCard,
  HaqibatiTopBar,
} from '../components/LayoutChrome';

type EditorModalState =
  | { type: 'BRANCH'; editing: StudyBranch | null }
  | { type: 'SUBJECT'; branch: StudyBranch; editing: StudySubject | null }
  | { type: 'SECTION'; subject: StudySubject; editing: StudySection | null }
  | { type: 'FILE'; section: StudySection; editing: StudyFileMetadata | null }
  | null;

type ConfirmAction =
  | { kind: 'UNPUBLISH_BRANCH'; item: StudyBranch }
  | { kind: 'UNPUBLISH_SUBJECT'; item: StudySubject }
  | { kind: 'UNPUBLISH_SECTION'; item: StudySection }
  | { kind: 'UNPUBLISH_FILE'; item: StudyFileMetadata }
  | { kind: 'ARCHIVE_FILE'; item: StudyFileMetadata }
  | null;

export const AdminPortalScreen: React.FC<{
  onNavigateBack: () => void;
}> = ({ onNavigateBack }) => {
  const {
    session,
    signIn,
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
  } = useHaqibati();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [authError, setAuthError] = useState<string | null>(null);
  const [isSigningIn, setIsSigningIn] = useState(false);

  const [activeTab, setActiveTab] = useState<AdminManagementSection>('BRANCHES');
  const [selectedBranchId, setSelectedBranchId] = useState<string | null>(null);
  const [selectedSubjectId, setSelectedSubjectId] = useState<string | null>(null);
  const [selectedSectionId, setSelectedSectionId] = useState<string | null>(null);

  const [feedback, setFeedback] = useState<{ text: string; isError: boolean } | null>(null);
  const [editorModal, setEditorModal] = useState<EditorModalState>(null);
  const [confirmAction, setConfirmAction] = useState<ConfirmAction>(null);

  const recognizedAdmin = hasRecognizedAdminRole(session);
  const canMutate = canPerformAdminActions(session);

  const selectedBranch = catalog.branches.find((b) => b.id === selectedBranchId) || null;
  const selectedSubject = catalog.subjects.find((s) => s.id === selectedSubjectId) || null;
  const selectedSection = catalog.sections.find((sec) => sec.id === selectedSectionId) || null;

  const sortedBranches = [...catalog.branches].sort((a, b) => a.order - b.order);
  const sortedSubjects = selectedBranch
    ? catalog.subjects.filter((s) => s.branchId === selectedBranch.id).sort((a, b) => a.order - b.order)
    : [];
  const sortedSections = selectedSubject
    ? catalog.sections.filter((s) => s.subjectId === selectedSubject.id).sort((a, b) => a.order - b.order)
    : [];
  const sortedFiles = selectedSection
    ? catalog.studyFiles.filter((f) => f.sectionId === selectedSection.id).sort((a, b) => a.order - b.order)
    : [];

  const handleAdminSignIn = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthError(null);
    setIsSigningIn(true);
    try {
      await signIn(email, password);
    } catch (err) {
      setAuthError(err instanceof Error ? err.message : 'تعذّر تسجيل الدخول.');
    } finally {
      setIsSigningIn(false);
    }
  };

  const handleConfirm = () => {
    if (!confirmAction) return;
    switch (confirmAction.kind) {
      case 'UNPUBLISH_BRANCH':
        toggleBranchPublished(confirmAction.item.id);
        setFeedback({ text: 'تم تحديث حالة النشر.', isError: false });
        break;
      case 'UNPUBLISH_SUBJECT':
        toggleSubjectPublished(confirmAction.item.id);
        setFeedback({ text: 'تم تحديث حالة النشر.', isError: false });
        break;
      case 'UNPUBLISH_SECTION':
        toggleSectionPublished(confirmAction.item.id);
        setFeedback({ text: 'تم تحديث حالة النشر.', isError: false });
        break;
      case 'UNPUBLISH_FILE':
        toggleStudyFilePublished(confirmAction.item.id);
        setFeedback({ text: 'تم تحديث حالة النشر.', isError: false });
        break;
      case 'ARCHIVE_FILE':
        toggleStudyFileArchived(confirmAction.item.id);
        setFeedback({ text: 'تم تحديث حالة الأرشفة.', isError: false });
        break;
    }
    setConfirmAction(null);
  };

  return (
    <div className="flex min-h-screen flex-col bg-[var(--bg)]">
      <HaqibatiTopBar
        title="لوحة المشرفين"
        onNavigateBack={onNavigateBack}
        actions={
          session.isSignedIn ? (
            <>
              <button
                type="button"
                onClick={refreshSession}
                title="تحديث الصلاحيات"
                className="flex h-10 w-10 items-center justify-center rounded-full text-[var(--on-surface-variant)] hover:bg-[var(--surface-variant)]"
              >
                <RefreshCw className="h-5 w-5" />
              </button>
              <button
                type="button"
                onClick={signOut}
                title="تسجيل الخروج"
                className="flex h-10 w-10 items-center justify-center rounded-full text-[var(--on-surface-variant)] hover:bg-[var(--surface-variant)]"
              >
                <LogOut className="h-5 w-5" />
              </button>
            </>
          ) : null
        }
      />

      {!session.isSignedIn ? (
        <div className="px-4 py-4">
          <HaqibatiSurfaceCard className="p-5">
            <form onSubmit={handleAdminSignIn} className="flex flex-col gap-4">
              <div className="flex items-center gap-2.5">
                <Shield className="h-6 w-6 text-[var(--primary)]" />
                <h2 className="text-xl font-bold text-[var(--on-surface)]">دخول الإدارة والمشرفين</h2>
              </div>

              <p className="text-sm text-[var(--on-surface-variant)]">
                في تطبيق Android الفعلي يقتصر الدخول على المالك والمشرفين المعتمدين عبر Firebase Authentication وCustom Claims وقواعد Firestore. أمّا في الويب فهذه الواجهة محاكاة بصرية في المتصفح ولا يمثّل حفظ الدور في localStorage أو البصمة المحلية أي حماية أمنية فعلية.
              </p>

              {typeof __HAQIBATI_PREVIEW_DEMO_ENABLED__ === 'boolean' &&
              __HAQIBATI_PREVIEW_DEMO_ENABLED__ &&
              typeof import.meta.env === 'object' &&
              import.meta.env.MODE !== 'production' &&
              import.meta.env.VITE_ENABLE_PREVIEW_DEMO === 'true' ? (
                <div className="rounded-xl border border-[var(--outline-variant)] bg-[var(--surface-variant)]/70 p-3 text-xs leading-relaxed text-[var(--on-surface-variant)]">
                  <strong className="mb-1 block text-[var(--on-surface)]">
                    بيانات معاينة وتطوير محلية فقط (مُزالة تلقائياً في بناء الاستضافة):
                  </strong>
                  مالك (<span className="font-mono">owner@haqibati.edu</span> /{' '}
                  <span className="font-mono">Owner1234</span>) — مشرف (
                  <span className="font-mono">admin@haqibati.edu</span> /{' '}
                  <span className="font-mono">Admin1234</span>)
                </div>
              ) : (
                <div className="rounded-xl border border-[var(--outline-variant)] bg-[var(--surface-variant)]/70 p-3 text-xs leading-relaxed text-[var(--on-surface-variant)]">
                  بيانات المعاينة التجريبية معطّلة في بناء الاستضافة. يتطلب الوصول الفعلي للإدارة ربط Firebase Auth والتحقق الخادمي في تطبيق Android.
                </div>
              )}

              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-semibold text-[var(--on-surface-variant)]">
                  البريد الإلكتروني
                </label>
                <input
                  type="email"
                  dir="ltr"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="w-full rounded-xl border border-[var(--outline)] bg-[var(--surface)] px-3.5 py-2.5 text-sm text-[var(--on-surface)] focus:border-[var(--primary)] focus:outline-none"
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-semibold text-[var(--on-surface-variant)]">
                  كلمة المرور
                </label>
                <input
                  type="password"
                  dir="ltr"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full rounded-xl border border-[var(--outline)] bg-[var(--surface)] px-3.5 py-2.5 text-sm text-[var(--on-surface)] focus:border-[var(--primary)] focus:outline-none"
                />
              </div>

              {authError && <AuthFeedbackBanner text={authError} isError />}

              <button
                type="submit"
                disabled={isSigningIn}
                className="w-full rounded-xl bg-[var(--primary)] px-4 py-2.5 text-sm font-semibold text-[var(--on-primary)]"
              >
                تسجيل الدخول
              </button>

              <div className="h-px bg-[var(--outline-variant)]" />
              <p className="text-xs leading-relaxed text-[var(--on-surface-variant)]">
                لا تكفي واجهة التطبيق وحدها لمنح الصلاحية؛ يقرأ التطبيق Claims فقط بينما تتحقق الخوادم وتفرض قواعد أمان Firestore صلاحيات الكتابة الفعلية.
              </p>
            </form>
          </HaqibatiSurfaceCard>
        </div>
      ) : !recognizedAdmin ? (
        <div className="flex flex-1 items-center justify-center px-4 py-8">
          <HaqibatiSurfaceCard className="p-6 text-center">
            <div className="mx-auto mb-3 flex h-16 w-16 items-center justify-center rounded-2xl bg-[var(--error-container)] text-[var(--on-error-container)]">
              <Lock className="h-7 w-7" />
            </div>
            <h2 className="text-lg font-bold text-[var(--on-surface)]">
              غير مصرّح بالوصول إلى لوحة الإدارة
            </h2>
            <p className="mt-2 text-sm text-[var(--on-surface-variant)]">
              الحساب المسجّل حالياً ({session.email}) لا يملك صلاحية المالك أو المشرف المعتمدة في الرمز الأمني، لذلك تم حجب أدوات الإدارة.
            </p>
            <div className="mt-5 grid grid-cols-2 gap-2.5">
              <button
                type="button"
                onClick={refreshSession}
                className="rounded-xl border border-[var(--outline)] px-3 py-2.5 text-sm font-semibold text-[var(--on-surface)]"
              >
                تحديث الصلاحيات
              </button>
              <button
                type="button"
                onClick={signOut}
                className="rounded-xl bg-[var(--primary)] px-3 py-2.5 text-sm font-semibold text-[var(--on-primary)]"
              >
                تسجيل الخروج
              </button>
            </div>
          </HaqibatiSurfaceCard>
        </div>
      ) : (
        <div className="flex flex-1 flex-col">
          {/* Tabs */}
          <div className="grid grid-cols-4 border-b border-[var(--outline-variant)] bg-[var(--surface)]">
            {(
              [
                { id: 'BRANCHES', label: 'الفروع' },
                { id: 'SUBJECTS', label: 'المواد' },
                { id: 'SECTIONS', label: 'الأقسام' },
                { id: 'PDF_FILES', label: 'ملفات PDF' },
              ] as { id: AdminManagementSection; label: string }[]
            ).map((tab) => {
              const active = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setActiveTab(tab.id)}
                  className={`border-b-2 py-3 text-xs font-bold transition sm:text-sm ${
                    active
                      ? 'border-[var(--primary)] text-[var(--primary)]'
                      : 'border-transparent text-[var(--on-surface-variant)]'
                  }`}
                >
                  {tab.label}
                </button>
              );
            })}
          </div>

          <div className="flex flex-col gap-4 px-4 py-4">
            {/* Session header */}
            <HaqibatiSurfaceCard>
              <div className="flex items-center gap-2 text-[var(--primary)]">
                <ShieldAlert className="h-5 w-5" />
                <span className="font-bold">
                  {session.role === 'OWNER' ? 'المالك (Owner)' : 'مشرف (Admin)'}
                </span>
              </div>
              <div className="mt-1 text-sm text-[var(--on-surface)]">{session.email}</div>
              <p className="mt-1.5 text-xs leading-relaxed text-[var(--on-surface-variant)]">
                الحالة الأمنية: تخضع عمليات القراءة والكتابة الإدارية لقواعد أمان Firestore المعتمدة على الخادم.
              </p>
            </HaqibatiSurfaceCard>

            {feedback && (
              <div
                className={`flex items-center justify-between rounded-xl px-4 py-3 text-sm ${
                  feedback.isError
                    ? 'bg-[var(--error-container)] text-[var(--on-error-container)]'
                    : 'bg-[var(--primary-container)] text-[var(--on-primary-container)]'
                }`}
              >
                <span>{feedback.text}</span>
                <button type="button" onClick={() => setFeedback(null)}>
                  <X className="h-4 w-4" />
                </button>
              </div>
            )}

            {/* BRANCHES */}
            {activeTab === 'BRANCHES' && (
              <>
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold text-[var(--on-surface-variant)]">
                    إدارة الفروع الدراسية
                  </span>
                  <button
                    type="button"
                    disabled={!canMutate}
                    onClick={() => setEditorModal({ type: 'BRANCH', editing: null })}
                    className="inline-flex items-center gap-1.5 rounded-xl bg-[var(--primary)] px-3.5 py-2 text-xs font-semibold text-[var(--on-primary)]"
                  >
                    <Plus className="h-4 w-4" />
                    إضافة فرع
                  </button>
                </div>

                {sortedBranches.length === 0 ? (
                  <AdminEmptyBox message="لا توجد فروع مضافة بعد في قاعدة البيانات." />
                ) : (
                  sortedBranches.map((branch, idx) => (
                    <AdminItemCard
                      key={branch.id}
                      title={branch.name}
                      referenceBadge={`المرجع: ${branch.referencePath}`}
                      order={branch.order}
                      isPublished={branch.isPublished}
                      canMoveUp={idx > 0 && canMutate}
                      canMoveDown={idx < sortedBranches.length - 1 && canMutate}
                      onMoveUp={() => {
                        moveBranch(branch.id, true);
                        setFeedback({ text: 'تم تحديث الترتيب بنجاح.', isError: false });
                      }}
                      onMoveDown={() => {
                        moveBranch(branch.id, false);
                        setFeedback({ text: 'تم تحديث الترتيب بنجاح.', isError: false });
                      }}
                      onEdit={() => setEditorModal({ type: 'BRANCH', editing: branch })}
                      onTogglePublish={() => {
                        if (branch.isPublished) {
                          setConfirmAction({ kind: 'UNPUBLISH_BRANCH', item: branch });
                        } else {
                          toggleBranchPublished(branch.id);
                          setFeedback({ text: 'تم تحديث حالة النشر.', isError: false });
                        }
                      }}
                      drillLabel="إدارة المواد"
                      onDrill={() => {
                        setSelectedBranchId(branch.id);
                        setSelectedSubjectId(null);
                        setSelectedSectionId(null);
                        setActiveTab('SUBJECTS');
                      }}
                    />
                  ))
                )}
              </>
            )}

            {/* SUBJECTS */}
            {activeTab === 'SUBJECTS' && (
              <>
                {!selectedBranch ? (
                  <AdminEmptyBox message="اختر فرعاً من تبويب الفروع أولاً لإدارة مواده." />
                ) : (
                  <>
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-semibold text-[var(--primary)]">
                        الفرع المختار: {selectedBranch.name}
                      </span>
                      <button
                        type="button"
                        disabled={!canMutate}
                        onClick={() =>
                          setEditorModal({ type: 'SUBJECT', branch: selectedBranch, editing: null })
                        }
                        className="inline-flex shrink-0 items-center gap-1.5 rounded-xl bg-[var(--primary)] px-3.5 py-2 text-xs font-semibold text-[var(--on-primary)]"
                      >
                        <Plus className="h-4 w-4" />
                        إضافة مادة
                      </button>
                    </div>

                    {sortedSubjects.length === 0 ? (
                      <AdminEmptyBox message="لا توجد مواد مسجّلة ضمن هذا الفرع." />
                    ) : (
                      sortedSubjects.map((subject, idx) => (
                        <AdminItemCard
                          key={subject.id}
                          title={subject.name}
                          referenceBadge={`المرجع: ${subject.branchReferencePath}`}
                          order={subject.order}
                          isPublished={subject.isPublished}
                          canMoveUp={idx > 0 && canMutate}
                          canMoveDown={idx < sortedSubjects.length - 1 && canMutate}
                          onMoveUp={() => {
                            moveSubject(subject.id, selectedBranch.id, true);
                            setFeedback({ text: 'تم تحديث الترتيب بنجاح.', isError: false });
                          }}
                          onMoveDown={() => {
                            moveSubject(subject.id, selectedBranch.id, false);
                            setFeedback({ text: 'تم تحديث الترتيب بنجاح.', isError: false });
                          }}
                          onEdit={() =>
                            setEditorModal({ type: 'SUBJECT', branch: selectedBranch, editing: subject })
                          }
                          onTogglePublish={() => {
                            if (subject.isPublished) {
                              setConfirmAction({ kind: 'UNPUBLISH_SUBJECT', item: subject });
                            } else {
                              toggleSubjectPublished(subject.id);
                              setFeedback({ text: 'تم تحديث حالة النشر.', isError: false });
                            }
                          }}
                          drillLabel="إدارة الأقسام"
                          onDrill={() => {
                            setSelectedSubjectId(subject.id);
                            setSelectedSectionId(null);
                            setActiveTab('SECTIONS');
                          }}
                        />
                      ))
                    )}
                  </>
                )}
              </>
            )}

            {/* SECTIONS */}
            {activeTab === 'SECTIONS' && (
              <>
                {!selectedSubject ? (
                  <AdminEmptyBox message="اختر مادة من تبويب المواد أولاً لإدارة أقسامها." />
                ) : (
                  <>
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-semibold text-[var(--primary)]">
                        المادة المختارة: {selectedSubject.name}
                      </span>
                      <button
                        type="button"
                        disabled={!canMutate}
                        onClick={() =>
                          setEditorModal({
                            type: 'SECTION',
                            subject: selectedSubject,
                            editing: null,
                          })
                        }
                        className="inline-flex shrink-0 items-center gap-1.5 rounded-xl bg-[var(--primary)] px-3.5 py-2 text-xs font-semibold text-[var(--on-primary)]"
                      >
                        <Plus className="h-4 w-4" />
                        إضافة قسم
                      </button>
                    </div>

                    {sortedSections.length === 0 ? (
                      <AdminEmptyBox message="لا توجد أقسام مسجّلة ضمن هذه المادة." />
                    ) : (
                      sortedSections.map((section, idx) => (
                        <AdminItemCard
                          key={section.id}
                          title={section.name}
                          referenceBadge={`المرجع: ${section.subjectReferencePath}`}
                          order={section.order}
                          isPublished={section.isPublished}
                          canMoveUp={idx > 0 && canMutate}
                          canMoveDown={idx < sortedSections.length - 1 && canMutate}
                          onMoveUp={() => {
                            moveSection(section.id, selectedSubject.id, true);
                            setFeedback({ text: 'تم تحديث الترتيب بنجاح.', isError: false });
                          }}
                          onMoveDown={() => {
                            moveSection(section.id, selectedSubject.id, false);
                            setFeedback({ text: 'تم تحديث الترتيب بنجاح.', isError: false });
                          }}
                          onEdit={() =>
                            setEditorModal({
                              type: 'SECTION',
                              subject: selectedSubject,
                              editing: section,
                            })
                          }
                          onTogglePublish={() => {
                            if (section.isPublished) {
                              setConfirmAction({ kind: 'UNPUBLISH_SECTION', item: section });
                            } else {
                              toggleSectionPublished(section.id);
                              setFeedback({ text: 'تم تحديث حالة النشر.', isError: false });
                            }
                          }}
                          drillLabel="ملفات PDF"
                          onDrill={() => {
                            setSelectedSectionId(section.id);
                            setActiveTab('PDF_FILES');
                          }}
                        />
                      ))
                    )}
                  </>
                )}
              </>
            )}

            {/* PDF FILES */}
            {activeTab === 'PDF_FILES' && (
              <>
                {!selectedSection ? (
                  <AdminEmptyBox message="اختر قسماً من تبويب الأقسام أولاً لإعداد ملفات PDF الخاصة به." />
                ) : (
                  <>
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-semibold text-[var(--primary)]">
                        القسم المختار: {selectedSection.name}
                      </span>
                      <button
                        type="button"
                        disabled={!canMutate}
                        onClick={() =>
                          setEditorModal({ type: 'FILE', section: selectedSection, editing: null })
                        }
                        className="inline-flex shrink-0 items-center gap-1.5 rounded-xl bg-[var(--primary)] px-3.5 py-2 text-xs font-semibold text-[var(--on-primary)]"
                      >
                        <Plus className="h-4 w-4" />
                        إعداد ملف PDF
                      </button>
                    </div>

                    <div className="rounded-xl bg-[var(--secondary-container)] p-3 text-xs leading-relaxed text-[var(--on-secondary-container)]">
                      تنبيه: تعدّ هذه اللوحة هوية الملف الأساسية (study_files) وتنظيم نشره وأرشفته وترتيبه.
                    </div>

                    {sortedFiles.length === 0 ? (
                      <AdminEmptyBox message="لا توجد بيانات ملفات PDF مسجّلة ضمن هذا القسم." />
                    ) : (
                      sortedFiles.map((file, idx) => (
                        <HaqibatiSurfaceCard key={file.id}>
                          <div className="flex flex-col gap-2.5">
                            <div className="flex items-start gap-2.5">
                              <FileText className="mt-0.5 h-5 w-5 shrink-0 text-[var(--primary)]" />
                              <div className="min-w-0 flex-1">
                                <div className="text-base font-semibold text-[var(--on-surface)]">
                                  {file.title}
                                </div>
                                {file.description && (
                                  <div className="mt-0.5 text-xs text-[var(--on-surface-variant)]">
                                    {file.description}
                                  </div>
                                )}
                                <div className="mt-1 text-xs text-[var(--on-surface-variant)]">
                                  المرجع: {file.sectionReferencePath}
                                </div>
                              </div>
                            </div>

                            <div className="flex flex-wrap gap-1.5 text-xs">
                              <StatusChip
                                label={file.isPublished ? 'منشور' : 'غير منشور'}
                                highlighted={file.isPublished}
                              />
                              {file.isArchived && <StatusChip label="مؤرشف" />}
                              <StatusChip label={`الترتيب: ${file.order}`} />
                              {file.activeVersionLabel && file.activeVersionNumber ? (
                                <StatusChip
                                  label={`الإصدار الفعّال ${file.activeVersionLabel} (رقم ${file.activeVersionNumber})`}
                                />
                              ) : (
                                <StatusChip label="بانتظار إضافة إصدار PDF" />
                              )}
                            </div>

                            <div className="flex items-center justify-between pt-1">
                              <div className="flex flex-wrap gap-1.5">
                                <button
                                  type="button"
                                  onClick={() =>
                                    setEditorModal({
                                      type: 'FILE',
                                      section: selectedSection,
                                      editing: file,
                                    })
                                  }
                                  className="inline-flex items-center gap-1 rounded-lg border border-[var(--outline)] px-2.5 py-1.5 text-xs font-semibold"
                                >
                                  <Edit3 className="h-3.5 w-3.5" />
                                  تعديل
                                </button>
                                <button
                                  type="button"
                                  onClick={() => {
                                    if (file.isPublished) {
                                      setConfirmAction({ kind: 'UNPUBLISH_FILE', item: file });
                                    } else {
                                      toggleStudyFilePublished(file.id);
                                      setFeedback({ text: 'تم تحديث حالة النشر.', isError: false });
                                    }
                                  }}
                                  className="rounded-lg border border-[var(--outline)] px-2.5 py-1.5 text-xs font-semibold"
                                >
                                  {file.isPublished ? 'إلغاء النشر' : 'نشر'}
                                </button>
                                <button
                                  type="button"
                                  onClick={() => {
                                    if (!file.isArchived) {
                                      setConfirmAction({ kind: 'ARCHIVE_FILE', item: file });
                                    } else {
                                      toggleStudyFileArchived(file.id);
                                      setFeedback({ text: 'تم تحديث حالة الأرشفة.', isError: false });
                                    }
                                  }}
                                  className="inline-flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-semibold text-[var(--primary)] hover:bg-[var(--surface-variant)]"
                                >
                                  {file.isArchived ? (
                                    <>
                                      <ArchiveRestore className="h-3.5 w-3.5" />
                                      إلغاء الأرشفة
                                    </>
                                  ) : (
                                    <>
                                      <Archive className="h-3.5 w-3.5" />
                                      أرشفة
                                    </>
                                  )}
                                </button>
                              </div>

                              <div className="flex items-center gap-1">
                                <button
                                  type="button"
                                  disabled={idx === 0}
                                  onClick={() => {
                                    moveStudyFile(file.id, selectedSection.id, true);
                                    setFeedback({ text: 'تم تحديث الترتيب بنجاح.', isError: false });
                                  }}
                                  className="rounded-lg p-1.5 text-[var(--on-surface-variant)] disabled:opacity-30"
                                >
                                  <ArrowUp className="h-4 w-4" />
                                </button>
                                <button
                                  type="button"
                                  disabled={idx === sortedFiles.length - 1}
                                  onClick={() => {
                                    moveStudyFile(file.id, selectedSection.id, false);
                                    setFeedback({ text: 'تم تحديث الترتيب بنجاح.', isError: false });
                                  }}
                                  className="rounded-lg p-1.5 text-[var(--on-surface-variant)] disabled:opacity-30"
                                >
                                  <ArrowDown className="h-4 w-4" />
                                </button>
                              </div>
                            </div>
                          </div>
                        </HaqibatiSurfaceCard>
                      ))
                    )}
                  </>
                )}
              </>
            )}
          </div>
        </div>
      )}

      {/* Editor Modal */}
      {editorModal && (
        <AdminEditorDialog
          modal={editorModal}
          suggestedOrder={
            editorModal.type === 'BRANCH'
              ? sortedBranches.length
              : editorModal.type === 'SUBJECT'
              ? sortedSubjects.length
              : editorModal.type === 'SECTION'
              ? sortedSections.length
              : sortedFiles.length
          }
          onDismiss={() => setEditorModal(null)}
          onSubmit={({ customId, name, description, order, isPublished }) => {
            try {
              if (editorModal.type === 'BRANCH') {
                saveBranch({
                  editingId: editorModal.editing?.id,
                  customId,
                  name,
                  order,
                  isPublished,
                });
                setFeedback({
                  text: editorModal.editing ? 'تم تحديث بيانات الفرع.' : 'تمت إضافة الفرع بنجاح.',
                  isError: false,
                });
              } else if (editorModal.type === 'SUBJECT') {
                saveSubject({
                  branchId: editorModal.branch.id,
                  editingId: editorModal.editing?.id,
                  customId,
                  name,
                  order,
                  isPublished,
                });
                setFeedback({
                  text: editorModal.editing
                    ? 'تم تحديث بيانات المادة.'
                    : 'تمت إضافة المادة وربطها بالفرع بنجاح.',
                  isError: false,
                });
              } else if (editorModal.type === 'SECTION') {
                saveSection({
                  subjectId: editorModal.subject.id,
                  editingId: editorModal.editing?.id,
                  customId,
                  name,
                  order,
                  isPublished,
                });
                setFeedback({
                  text: editorModal.editing
                    ? 'تم تحديث بيانات القسم.'
                    : 'تمت إضافة القسم وربطه بالمادة بنجاح.',
                  isError: false,
                });
              } else if (editorModal.type === 'FILE') {
                saveStudyFile({
                  sectionId: editorModal.section.id,
                  editingId: editorModal.editing?.id,
                  customId,
                  title: name,
                  description,
                  order,
                  isPublished,
                });
                setFeedback({
                  text: editorModal.editing
                    ? 'تم تحديث بيانات ملف PDF الأساسية دون المساس بالإصدارات.'
                    : 'تم حفظ الهوية الأساسية لملف PDF بنجاح.',
                  isError: false,
                });
              }
              setEditorModal(null);
            } catch (err) {
              setFeedback({
                text: err instanceof Error ? err.message : 'تعذّر الحفظ.',
                isError: true,
              });
            }
          }}
        />
      )}

      {/* Confirmation Dialog */}
      {confirmAction && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-sm rounded-2xl bg-[var(--surface)] p-5 shadow-xl">
            <h3 className="text-lg font-bold text-[var(--on-surface)]">
              {confirmAction.kind === 'ARCHIVE_FILE'
                ? 'تأكيد أرشفة الملف'
                : 'تأكيد إلغاء النشر'}
            </h3>
            <p className="mt-2 text-sm leading-relaxed text-[var(--on-surface-variant)]">
              {confirmAction.kind === 'ARCHIVE_FILE'
                ? `هل تريد أرشفة «${confirmAction.item.title}»؟ تُستخدم الأرشفة لحماية المحتوى من الحذف العرضي مع إمكانية استعادته لاحقاً.`
                : `هل تريد إلغاء نشر «${
                    confirmAction.kind === 'UNPUBLISH_FILE'
                      ? confirmAction.item.title
                      : confirmAction.item.name
                  }»؟ سيختفي عن الطلاب دون حذف بياناته من قاعدة البيانات.`}
            </p>
            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setConfirmAction(null)}
                className="rounded-xl px-4 py-2 text-sm font-semibold text-[var(--on-surface-variant)] hover:bg-[var(--surface-variant)]"
              >
                إلغاء
              </button>
              <button
                type="button"
                onClick={handleConfirm}
                className="rounded-xl bg-[var(--primary)] px-4 py-2 text-sm font-semibold text-[var(--on-primary)]"
              >
                تأكيد
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

const AdminItemCard: React.FC<{
  title: string;
  referenceBadge: string;
  order: number;
  isPublished: boolean;
  canMoveUp: boolean;
  canMoveDown: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onEdit: () => void;
  onTogglePublish: () => void;
  drillLabel: string;
  onDrill: () => void;
}> = ({
  title,
  referenceBadge,
  order,
  isPublished,
  canMoveUp,
  canMoveDown,
  onMoveUp,
  onMoveDown,
  onEdit,
  onTogglePublish,
  drillLabel,
  onDrill,
}) => (
  <HaqibatiSurfaceCard>
    <div className="flex flex-col gap-2.5">
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="text-base font-semibold text-[var(--on-surface)]">{title}</div>
          <div className="text-xs text-[var(--on-surface-variant)]">{referenceBadge}</div>
        </div>
        <StatusChip label={isPublished ? 'منشور' : 'غير منشور'} highlighted={isPublished} />
      </div>

      <div className="flex items-center justify-between">
        <StatusChip label={`الترتيب: ${order}`} />
        <div className="flex items-center gap-1">
          <button
            type="button"
            disabled={!canMoveUp}
            onClick={onMoveUp}
            className="rounded-lg p-1.5 text-[var(--on-surface-variant)] disabled:opacity-30"
          >
            <ArrowUp className="h-4 w-4" />
          </button>
          <button
            type="button"
            disabled={!canMoveDown}
            onClick={onMoveDown}
            className="rounded-lg p-1.5 text-[var(--on-surface-variant)] disabled:opacity-30"
          >
            <ArrowDown className="h-4 w-4" />
          </button>
        </div>
      </div>

      <div className="flex flex-wrap gap-2 pt-1">
        <button
          type="button"
          onClick={onEdit}
          className="inline-flex items-center gap-1 rounded-xl border border-[var(--outline)] px-3 py-1.5 text-xs font-semibold"
        >
          <Edit3 className="h-3.5 w-3.5" />
          تعديل
        </button>
        <button
          type="button"
          onClick={onTogglePublish}
          className="rounded-xl border border-[var(--outline)] px-3 py-1.5 text-xs font-semibold"
        >
          {isPublished ? 'إلغاء النشر' : 'نشر'}
        </button>
        <button
          type="button"
          onClick={onDrill}
          className="rounded-xl bg-[var(--secondary-container)] px-3 py-1.5 text-xs font-semibold text-[var(--on-secondary-container)]"
        >
          {drillLabel}
        </button>
      </div>
    </div>
  </HaqibatiSurfaceCard>
);

const StatusChip: React.FC<{ label: string; highlighted?: boolean }> = ({
  label,
  highlighted = false,
}) => (
  <span
    className={`inline-block rounded-lg px-2.5 py-0.5 text-xs font-medium ${
      highlighted
        ? 'bg-[var(--primary-container)] text-[var(--on-primary-container)]'
        : 'bg-[var(--surface-variant)] text-[var(--on-surface-variant)]'
    }`}
  >
    {label}
  </span>
);

const AdminEmptyBox: React.FC<{ message: string }> = ({ message }) => (
  <HaqibatiSurfaceCard className="p-6 text-center text-sm text-[var(--on-surface-variant)]">
    {message}
  </HaqibatiSurfaceCard>
);

const AdminEditorDialog: React.FC<{
  modal: NonNullable<EditorModalState>;
  suggestedOrder: number;
  onDismiss: () => void;
  onSubmit: (data: {
    customId: string | null;
    name: string;
    description: string | null;
    order: number;
    isPublished: boolean;
  }) => void;
}> = ({ modal, suggestedOrder, onDismiss, onSubmit }) => {
  const isEditing = Boolean(modal.editing);
  const initialName =
    modal.type === 'FILE' ? modal.editing?.title ?? '' : modal.editing?.name ?? '';
  const initialDesc = modal.type === 'FILE' ? modal.editing?.description ?? '' : '';
  const initialOrder = modal.editing?.order ?? suggestedOrder;
  const initialPublished = modal.editing?.isPublished ?? true;

  const [customId, setCustomId] = useState('');
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(initialDesc);
  const [orderText, setOrderText] = useState(String(initialOrder));
  const [isPublished, setIsPublished] = useState(initialPublished);

  const title =
    modal.type === 'BRANCH'
      ? isEditing
        ? 'تعديل الفرع'
        : 'إضافة فرع'
      : modal.type === 'SUBJECT'
      ? isEditing
        ? 'تعديل المادة'
        : 'إضافة مادة'
      : modal.type === 'SECTION'
      ? isEditing
        ? 'تعديل القسم'
        : 'إضافة قسم'
      : isEditing
      ? 'تعديل بيانات ملف PDF'
      : 'إعداد ملف PDF';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="w-full max-w-md rounded-2xl bg-[var(--surface)] p-5 shadow-xl">
        <h3 className="text-lg font-bold text-[var(--on-surface)]">{title}</h3>

        <form
          onSubmit={(e) => {
            e.preventDefault();
            onSubmit({
              customId: customId.trim() || null,
              name,
              description: description.trim() || null,
              order: orderText === '' ? -1 : Number(orderText),
              isPublished,
            });
          }}
          className="mt-4 flex flex-col gap-3"
        >
          {!isEditing && (
            <div className="flex flex-col gap-1">
              <label className="text-xs font-semibold text-[var(--on-surface-variant)]">
                المعرّف التقني الاختياري (أحرف إنجليزية/أرقام/_/-)
              </label>
              <input
                type="text"
                dir="ltr"
                value={customId}
                onChange={(e) => setCustomId(e.target.value)}
                className="w-full rounded-xl border border-[var(--outline)] bg-[var(--bg)] px-3 py-2 text-sm"
              />
            </div>
          )}

          <div className="flex flex-col gap-1">
            <label className="text-xs font-semibold text-[var(--on-surface-variant)]">
              {modal.type === 'FILE' ? 'عنوان الملف التعليمي' : 'الاسم بالعربية'}
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-xl border border-[var(--outline)] bg-[var(--bg)] px-3 py-2 text-sm"
            />
          </div>

          {modal.type === 'FILE' && (
            <div className="flex flex-col gap-1">
              <label className="text-xs font-semibold text-[var(--on-surface-variant)]">
                وصف مختصر (اختياري)
              </label>
              <textarea
                rows={2}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className="w-full rounded-xl border border-[var(--outline)] bg-[var(--bg)] px-3 py-2 text-sm"
              />
            </div>
          )}

          <div className="flex flex-col gap-1">
            <label className="text-xs font-semibold text-[var(--on-surface-variant)]">
              الترتيب (0 أو أكبر)
            </label>
            <input
              type="number"
              min={0}
              value={orderText}
              onChange={(e) => setOrderText(e.target.value)}
              className="w-full rounded-xl border border-[var(--outline)] bg-[var(--bg)] px-3 py-2 text-sm"
            />
          </div>

          {!isEditing && (
            <label className="flex items-center justify-between pt-1 text-sm">
              <span>نشر العنصر للطلاب مباشرة</span>
              <input
                type="checkbox"
                checked={isPublished}
                onChange={(e) => setIsPublished(e.target.checked)}
                className="h-4 w-4 accent-[var(--primary)]"
              />
            </label>
          )}

          <div className="mt-3 flex justify-end gap-2">
            <button
              type="button"
              onClick={onDismiss}
              className="rounded-xl px-4 py-2 text-sm font-semibold text-[var(--on-surface-variant)]"
            >
              إلغاء
            </button>
            <button
              type="submit"
              className="rounded-xl bg-[var(--primary)] px-4 py-2 text-sm font-semibold text-[var(--on-primary)]"
            >
              حفظ
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
