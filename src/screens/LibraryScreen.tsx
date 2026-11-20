import React from 'react';
import {
  Book,
  BookOpen,
  CheckCircle2,
  ChevronLeft,
  Download,
  FileText,
  Folders,
  GitBranch,
  ListTree,
} from 'lucide-react';
import { StudyFileMetadata, canDownloadOfflineFiles } from '../domain/models';
import {
  LibraryRoute,
  resolveLibraryBackRoute,
  resolveLibraryNavigation,
} from '../domain/catalogNavigation';
import { useHaqibati } from '../state/HaqibatiContext';
import {
  CatalogNavigationCard,
  HaqibatiEmptyState,
  HaqibatiSectionHeader,
  HaqibatiSurfaceCard,
} from '../components/LayoutChrome';

export type { LibraryRoute };

export const LibraryScreen: React.FC<{
  route: LibraryRoute;
  onNavigate: (next: LibraryRoute) => void;
  onOpenStudyFile: (file: StudyFileMetadata) => void;
}> = ({ route, onNavigate, onOpenStudyFile }) => {
  const { catalog, session, downloadedFileIds, toggleDownloadFile, readingProgressMap } = useHaqibati();
  const canDownload = canDownloadOfflineFiles(session);
  const resolved = resolveLibraryNavigation(catalog, route);

  if (resolved.isUnavailable) {
    return (
      <div className="flex flex-col gap-4 px-4 py-4">
        <HaqibatiEmptyState
          icon={<Folders className="h-7 w-7" />}
          title="هذا المحتوى غير متاح حالياً"
          message="ربما تم تعديل هذا العنصر أو إلغاء نشره من لوحة الإدارة. يمكنك العودة إلى المكتبة لعرض المحتوى المحدّث."
        />
        <button
          type="button"
          onClick={() => onNavigate(resolveLibraryBackRoute(catalog, route))}
          className="mx-auto rounded-xl bg-[var(--primary)] px-5 py-2.5 text-sm font-semibold text-[var(--on-primary)] transition hover:opacity-90"
        >
          العودة للخلف
        </button>
      </div>
    );
  }

  if (resolved.level === 'BRANCHES') {
    const branches = resolved.branches;

    return (
      <div className="flex flex-col gap-3.5 px-4 py-4">
        <HaqibatiSectionHeader title="الفروع الدراسية" />
        <p className="text-sm text-[var(--on-surface-variant)]">
          اختر الفرع الدراسي لعرض المواد التابعة له
        </p>

        {branches.length === 0 ? (
          <HaqibatiEmptyState
            icon={<BookOpen className="h-7 w-7" />}
            title="لا توجد فروع منشورة حالياً"
            message="لم يتم نشر أي فروع دراسية في قاعدة البيانات بعد."
          />
        ) : (
          <div className="flex flex-col gap-3">
            {branches.map((branch) => (
              <CatalogNavigationCard
                key={branch.id}
                icon={<GitBranch className="h-5 w-5" />}
                title={branch.name}
                onClick={() => onNavigate({ level: 'SUBJECTS', branchId: branch.id })}
              />
            ))}
          </div>
        )}
      </div>
    );
  }

  if (resolved.level === 'SUBJECTS' && resolved.branch) {
    const branchId = resolved.branch.id;
    const subjects = resolved.subjects;

    return (
      <div className="flex flex-col gap-3.5 px-4 py-4">
        <p className="text-sm text-[var(--on-surface-variant)]">
          اختر المادة الدراسية لعرض أقسامها المنشورة
        </p>

        {subjects.length === 0 ? (
          <HaqibatiEmptyState
            icon={<BookOpen className="h-7 w-7" />}
            title="لا توجد مواد منشورة لهذا الفرع"
            message="لم تُنشر مواد دراسية ضمن هذا الفرع حالياً."
          />
        ) : (
          <div className="flex flex-col gap-3">
            {subjects.map((subject) => (
              <CatalogNavigationCard
                key={subject.id}
                icon={<Book className="h-5 w-5" />}
                title={subject.name}
                onClick={() =>
                  onNavigate({ level: 'SECTIONS', branchId, subjectId: subject.id })
                }
              />
            ))}
          </div>
        )}
      </div>
    );
  }

  if (resolved.level === 'SECTIONS' && resolved.branch && resolved.subject) {
    const branchId = resolved.branch.id;
    const subjectId = resolved.subject.id;
    const sections = resolved.sections;

    return (
      <div className="flex flex-col gap-3.5 px-4 py-4">
        <p className="text-sm text-[var(--on-surface-variant)]">
          الأقسام الدراسية المنشورة ضمن هذه المادة
        </p>

        {sections.length === 0 ? (
          <HaqibatiEmptyState
            icon={<Folders className="h-7 w-7" />}
            title="لا توجد أقسام منشورة حالياً"
            message="ستظهر الأقسام الدراسية هنا فور إضافتها ونشرها من لوحة إدارة المشرفين."
          />
        ) : (
          <div className="flex flex-col gap-3">
            {sections.map((section) => (
              <CatalogNavigationCard
                key={section.id}
                icon={<ListTree className="h-5 w-5" />}
                title={section.name}
                onClick={() =>
                  onNavigate({
                    level: 'FILES',
                    branchId,
                    subjectId,
                    sectionId: section.id,
                  })
                }
              />
            ))}
          </div>
        )}
      </div>
    );
  }

  // FILES level
  const files = resolved.files;

  return (
    <div className="flex flex-col gap-3.5 px-4 py-4">
      <p className="text-sm text-[var(--on-surface-variant)]">
        الملفات التعليمية المنشورة ضمن هذا القسم
      </p>

      {files.length === 0 ? (
        <HaqibatiEmptyState
          icon={<FileText className="h-7 w-7" />}
          title="لا توجد ملفات منشورة في هذا القسم"
          message="لم يتم نشر ملفات تعليمية ضمن هذا القسم بعد."
        />
      ) : (
        <div className="flex flex-col gap-3">
          {files.map((file) => {
            const isDownloaded = downloadedFileIds.includes(file.id);
            const progress = readingProgressMap[file.id];
            const statusLabel = file.activeVersionLabel
              ? `الإصدار ${file.activeVersionLabel}`
              : file.activeVersionId
              ? 'فتح للقراءة'
              : 'بانتظار نشر نسخة PDF من الإدارة';

            return (
              <HaqibatiSurfaceCard key={file.id} className="p-0 overflow-hidden">
                <button
                  type="button"
                  onClick={() => onOpenStudyFile(file)}
                  className="flex w-full items-center gap-3 p-4 text-right transition hover:bg-[var(--surface-variant)]/40"
                >
                  <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-[var(--primary-container)] text-[var(--on-primary-container)]">
                    <FileText className="h-5 w-5" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="text-base font-semibold text-[var(--on-surface)]">{file.title}</div>
                    {file.description && (
                      <p className="mt-0.5 text-sm leading-snug text-[var(--on-surface-variant)]">
                        {file.description}
                      </p>
                    )}
                    <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs">
                      <span className="font-medium text-[var(--primary)]">{statusLabel}</span>
                      {progress && progress.totalPages > 0 && (
                        <span className="rounded-md bg-[var(--secondary-container)] px-2 py-0.5 font-medium text-[var(--on-secondary-container)]">
                          صفحة {progress.lastPageNumber} من {progress.totalPages}
                        </span>
                      )}
                    </div>
                  </div>
                  <ChevronLeft className="h-5 w-5 shrink-0 text-[var(--on-surface-variant)]" />
                </button>

                {canDownload && file.activeVersionId && (
                  <div className="flex items-center justify-between border-t border-[var(--outline-variant)] bg-[var(--surface-variant)]/35 px-4 py-2">
                    <span className="text-xs text-[var(--on-surface-variant)]">
                      {isDownloaded ? 'محفوظ على هذا الجهاز' : 'حفظ للقراءة دون اتصال'}
                    </span>
                    <button
                      type="button"
                      onClick={() => toggleDownloadFile(file.id)}
                      className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1 text-xs font-semibold transition ${
                        isDownloaded
                          ? 'bg-[var(--primary-container)] text-[var(--on-primary-container)]'
                          : 'bg-[var(--surface)] text-[var(--primary)] border border-[var(--outline)]'
                      }`}
                    >
                      {isDownloaded ? (
                        <>
                          <CheckCircle2 className="h-3.5 w-3.5" />
                          محفوظ
                        </>
                      ) : (
                        <>
                          <Download className="h-3.5 w-3.5" />
                          تنزيل
                        </>
                      )}
                    </button>
                  </div>
                )}
              </HaqibatiSurfaceCard>
            );
          })}
        </div>
      )}
    </div>
  );
};
