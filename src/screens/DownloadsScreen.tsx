import React from 'react';
import { ChevronLeft, FileText, FolderOpen, Lock, Trash2 } from 'lucide-react';
import { StudyFileMetadata, canDownloadOfflineFiles } from '../domain/models';
import { resolvePublishedStudyFile } from '../domain/catalogNavigation';
import { useHaqibati } from '../state/HaqibatiContext';
import {
  HaqibatiEmptyState,
  HaqibatiSectionHeader,
  HaqibatiSurfaceCard,
} from '../components/LayoutChrome';

export const DownloadsScreen: React.FC<{
  onNavigateToSignIn: () => void;
  onNavigateToSignUp: () => void;
  onOpenStudyFile: (file: StudyFileMetadata) => void;
}> = ({ onNavigateToSignIn, onNavigateToSignUp, onOpenStudyFile }) => {
  const { session, catalog, downloadedFileIds, toggleDownloadFile, readingProgressMap } =
    useHaqibati();
  const canDownload = canDownloadOfflineFiles(session);

  const savedFiles = catalog.studyFiles.filter(
    (f) =>
      downloadedFileIds.includes(f.id) &&
      resolvePublishedStudyFile(catalog, f.id) !== null
  );

  return (
    <div className="flex flex-col gap-4 px-4 py-4">
      <HaqibatiSectionHeader title="التنزيلات المحلية" />

      {!canDownload && (
        <HaqibatiSurfaceCard>
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-2.5">
              <Lock className="h-5 w-5 text-[var(--primary)]" />
              <h3 className="text-base font-semibold text-[var(--on-surface)]">
                التنزيلات متاحة للحسابات المسجّلة
              </h3>
            </div>
            <p className="text-sm leading-relaxed text-[var(--on-surface-variant)]">
              بصفتك زائراً يمكنك تصفح الفروع والمواد والأقسام بحرية. عند تسجيل الدخول بحساب طالب يمكنك حفظ الملفات على هذا الجهاز للوصول السريع.
            </p>
            <div className="grid grid-cols-2 gap-2.5 pt-1">
              <button
                type="button"
                onClick={onNavigateToSignIn}
                className="rounded-xl bg-[var(--primary)] px-4 py-2.5 text-sm font-semibold text-[var(--on-primary)] transition hover:opacity-90"
              >
                تسجيل الدخول
              </button>
              <button
                type="button"
                onClick={onNavigateToSignUp}
                className="rounded-xl border border-[var(--outline)] px-4 py-2.5 text-sm font-semibold text-[var(--primary)] transition hover:bg-[var(--surface-variant)]"
              >
                إنشاء حساب طالب
              </button>
            </div>
          </div>
        </HaqibatiSurfaceCard>
      )}

      {savedFiles.length === 0 ? (
        <HaqibatiEmptyState
          icon={<FolderOpen className="h-7 w-7" />}
          title="لا توجد ملفات محفوظة حالياً"
          message="ستظهر الملفات التعليمية التي يتم تنزيلها للوصول دون اتصال في هذا القسم عند حفظها من المكتبة."
        />
      ) : (
        <div className="flex flex-col gap-3">
          {savedFiles.map((file) => {
            const progress = readingProgressMap[file.id];
            return (
              <HaqibatiSurfaceCard key={file.id} className="p-0 overflow-hidden">
                <div className="flex items-center gap-3 p-4">
                  <button
                    type="button"
                    onClick={() => {
                      const liveFile = resolvePublishedStudyFile(catalog, file.id);
                      if (liveFile) {
                        onOpenStudyFile(liveFile);
                      }
                    }}
                    className="flex min-w-0 flex-1 items-center gap-3 text-right"
                  >
                    <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-[var(--primary-container)] text-[var(--on-primary-container)]">
                      <FileText className="h-5 w-5" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-base font-semibold text-[var(--on-surface)]">
                        {file.title}
                      </div>
                      <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-[var(--on-surface-variant)]">
                        {file.activeVersionLabel && <span>الإصدار {file.activeVersionLabel}</span>}
                        {progress && progress.totalPages > 0 && (
                          <span className="rounded bg-[var(--secondary-container)] px-1.5 py-0.5 text-[var(--on-secondary-container)]">
                            صفحة {progress.lastPageNumber} من {progress.totalPages}
                          </span>
                        )}
                      </div>
                    </div>
                    <ChevronLeft className="h-5 w-5 shrink-0 text-[var(--on-surface-variant)]" />
                  </button>

                  {canDownload && (
                    <button
                      type="button"
                      onClick={() => toggleDownloadFile(file.id)}
                      aria-label="إزالة من التنزيلات"
                      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-[var(--on-surface-variant)] transition hover:bg-[var(--error-container)] hover:text-[var(--on-error-container)]"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  )}
                </div>
              </HaqibatiSurfaceCard>
            );
          })}
        </div>
      )}
    </div>
  );
};
