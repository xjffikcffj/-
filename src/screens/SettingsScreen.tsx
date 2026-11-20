import React, { useState } from 'react';
import {
  ChevronLeft,
  Cloud,
  Globe,
  Info,
  LogOut,
  Moon,
  ShieldCheck,
  UserCircle2,
} from 'lucide-react';
import {
  AppUserRole,
  getEffectiveUserRole,
  hasRecognizedAdminRole,
} from '../domain/models';
import { useHaqibati } from '../state/HaqibatiContext';
import {
  AuthFeedbackBanner,
  HaqibatiSectionHeader,
  HaqibatiSurfaceCard,
} from '../components/LayoutChrome';

export function resolveRoleLabel(role: AppUserRole): string {
  switch (role) {
    case 'GUEST':
      return 'زائر (Guest)';
    case 'STUDENT':
      return 'طالب (Student)';
    case 'ADMIN':
      return 'مشرف (Admin)';
    case 'OWNER':
      return 'مالك (Owner)';
  }
}

export const SettingsScreen: React.FC<{
  onNavigateToAbout: () => void;
  onNavigateToAdminPortal: () => void;
  onNavigateToSignIn: () => void;
  onNavigateToSignUp: () => void;
}> = ({
  onNavigateToAbout,
  onNavigateToAdminPortal,
  onNavigateToSignIn,
  onNavigateToSignUp,
}) => {
  const { preferences, toggleDarkMode, session, signOut } = useHaqibati();
  const [signedOutMsg, setSignedOutMsg] = useState(false);

  const effectiveRole = getEffectiveUserRole(session);
  const recognizedAdmin = hasRecognizedAdminRole(session);
  const showAdminPortalEntry = !session.isSignedIn || !session.hasLoadedTokenClaims || recognizedAdmin;

  const roleSummary =
    effectiveRole === 'GUEST'
      ? 'يمكنك استعراض الفروع والمواد والأقسام المنشورة عبر الإنترنت دون تسجيل دخول. سجّل الدخول بحساب طالب لفتح ميزة التنزيلات دون اتصال.'
      : effectiveRole === 'STUDENT'
      ? 'صلاحية طالب: تصفح المحتوى المنشور، إدارة حسابك، والاستفادة من التنزيلات المحلية. لا يشمل صلاحيات الإدارة.'
      : effectiveRole === 'ADMIN'
      ? 'صلاحية مشرف معتمدة من الخادم: إدارة المحتوى الدراسي دون صلاحيات المالك الحصرية.'
      : 'صلاحية المالك الكاملة المعتمدة من الخادم.';

  const adminSubtitle = !session.isSignedIn
    ? 'تسجيل دخول المالك أو المشرفين لإدارة الفروع والمواد والأقسام'
    : recognizedAdmin
    ? `تم التعرّف على صلاحية (${session.role === 'OWNER' ? 'المالك (Owner)' : 'مشرف (Admin)'}) — فتح لوحة إدارة المحتوى`
    : 'تم تسجيل الدخول بحساب لا يحمل صلاحية مشرف أو مالك';

  const handleSignOut = () => {
    signOut();
    setSignedOutMsg(true);
  };

  return (
    <div className="flex flex-col gap-4 px-4 py-4">
      <HaqibatiSectionHeader title="الحساب والصلاحيات" />

      <HaqibatiSurfaceCard>
        <div className="flex flex-col gap-3">
          <div className="flex items-center gap-3">
            <UserCircle2 className="h-8 w-8 shrink-0 text-[var(--primary)]" />
            <div className="min-w-0 flex-1">
              <div className="text-base font-bold text-[var(--primary)]">
                {resolveRoleLabel(effectiveRole)}
              </div>
              <div className="truncate text-sm text-[var(--on-surface)]">
                {session.isSignedIn && session.email ? session.email : 'تتصفح التطبيق كزائر'}
              </div>
            </div>
          </div>

          <p className="text-xs leading-relaxed text-[var(--on-surface-variant)]">{roleSummary}</p>

          <p className="text-xs leading-relaxed text-[var(--on-surface-variant)]">
            بيانات الاستخدام والتفضيلات الشخصية وتقدم القراءة والتنزيلات تبقى محفوظة محلياً لكل جهاز فقط ولا تُمسح عند تسجيل الخروج.
          </p>

          {signedOutMsg && !session.isSignedIn && (
            <AuthFeedbackBanner
              text="تم تسجيل الخروج بنجاح مع بقاء إعداداتك وبياناتك المحلية محفوظة على هذا الجهاز."
              isError={false}
            />
          )}

          {!session.isSignedIn ? (
            <div className="grid grid-cols-2 gap-2.5 pt-1">
              <button
                type="button"
                onClick={() => {
                  setSignedOutMsg(false);
                  onNavigateToSignIn();
                }}
                className="rounded-xl bg-[var(--primary)] px-4 py-2.5 text-sm font-semibold text-[var(--on-primary)] transition hover:opacity-90"
              >
                تسجيل الدخول
              </button>
              <button
                type="button"
                onClick={() => {
                  setSignedOutMsg(false);
                  onNavigateToSignUp();
                }}
                className="rounded-xl border border-[var(--outline)] px-4 py-2.5 text-sm font-semibold text-[var(--primary)] transition hover:bg-[var(--surface-variant)]"
              >
                إنشاء حساب طالب
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={handleSignOut}
              className="flex w-full items-center justify-center gap-2 rounded-xl border border-[var(--outline)] px-4 py-2.5 text-sm font-semibold text-[var(--on-surface)] transition hover:bg-[var(--surface-variant)]"
            >
              <LogOut className="h-4 w-4" />
              تسجيل الخروج
            </button>
          )}
        </div>
      </HaqibatiSurfaceCard>

      <HaqibatiSectionHeader title="المظهر واللغة" />

      <HaqibatiSurfaceCard>
        <div className="flex flex-col gap-3.5">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <Moon className="h-5 w-5 shrink-0 text-[var(--primary)]" />
              <div>
                <div className="text-base font-semibold text-[var(--on-surface)]">الوضع الداكن</div>
                <div className="text-xs text-[var(--on-surface-variant)]">
                  تبديل ألوان الواجهة بين الوضع الفاتح والداكن
                </div>
              </div>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={preferences.darkModeEnabled}
              onClick={() => toggleDarkMode(!preferences.darkModeEnabled)}
              className={`relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full transition-colors ${
                preferences.darkModeEnabled ? 'bg-[var(--primary)]' : 'bg-[var(--outline)]'
              }`}
            >
              <span
                className={`inline-block h-5 w-5 transform rounded-full bg-white shadow-xs transition-transform ${
                  preferences.darkModeEnabled ? '-translate-x-6' : '-translate-x-1'
                }`}
              />
            </button>
          </div>

          <div className="h-px bg-[var(--outline-variant)]" />

          <div className="flex items-center gap-3">
            <Globe className="h-5 w-5 shrink-0 text-[var(--primary)]" />
            <div>
              <div className="text-base font-semibold text-[var(--on-surface)]">
                لغة التطبيق والاتجاه
              </div>
              <div className="text-xs text-[var(--on-surface-variant)]">
                العربية (من اليمين إلى اليسار RTL)
              </div>
            </div>
          </div>
        </div>
      </HaqibatiSurfaceCard>

      <HaqibatiSectionHeader title="التطبيق والربط" />

      <HaqibatiSurfaceCard>
        <div className="flex items-center gap-3">
          <Cloud className="h-5 w-5 shrink-0 text-[var(--primary)]" />
          <div>
            <div className="text-base font-semibold text-[var(--on-surface)]">
              جاهزية الربط مع Firebase
            </div>
            <div className="text-xs leading-relaxed text-[var(--on-surface-variant)]">
              تمت تهيئة البنية للمشروع المعتمد student-learning-platform-2026 مع بيانات محلية تفاعلية.
            </div>
          </div>
        </div>
      </HaqibatiSurfaceCard>

      <HaqibatiSurfaceCard onClick={onNavigateToAbout}>
        <div className="flex items-center gap-3">
          <Info className="h-5 w-5 shrink-0 text-[var(--primary)]" />
          <div className="flex-1 text-right">
            <div className="text-base font-semibold text-[var(--on-surface)]">حول تطبيق حقيبتي</div>
            <div className="text-xs text-[var(--on-surface-variant)]">
              معلومات الإصدار والهوية التقنية
            </div>
          </div>
          <ChevronLeft className="h-5 w-5 shrink-0 text-[var(--on-surface-variant)]" />
        </div>
      </HaqibatiSurfaceCard>

      {showAdminPortalEntry && (
        <>
          <HaqibatiSectionHeader title="الإدارة والمشرفون" />
          <HaqibatiSurfaceCard onClick={onNavigateToAdminPortal}>
            <div className="flex items-center gap-3">
              <ShieldCheck className="h-5 w-5 shrink-0 text-[var(--primary)]" />
              <div className="flex-1 text-right">
                <div className="text-base font-semibold text-[var(--on-surface)]">
                  لوحة المشرفين وإدارة المحتوى
                </div>
                <div className="text-xs text-[var(--on-surface-variant)]">{adminSubtitle}</div>
              </div>
              <ChevronLeft className="h-5 w-5 shrink-0 text-[var(--on-surface-variant)]" />
            </div>
          </HaqibatiSurfaceCard>
        </>
      )}
    </div>
  );
};
