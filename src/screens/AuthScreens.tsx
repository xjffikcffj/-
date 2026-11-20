import React, { useState } from 'react';
import {
  CheckCircle2,
  Eye,
  EyeOff,
  KeyRound,
  LogIn,
  UserPlus,
} from 'lucide-react';
import { getEffectiveUserRole } from '../domain/models';
import { useHaqibati } from '../state/HaqibatiContext';
import {
  AuthFeedbackBanner,
  HaqibatiSurfaceCard,
  HaqibatiTopBar,
} from '../components/LayoutChrome';
import { resolveRoleLabel } from './SettingsScreen';

export const SignInScreen: React.FC<{
  onNavigateBack: () => void;
  onNavigateToSignUp: () => void;
  onNavigateToForgotPassword: () => void;
}> = ({ onNavigateBack, onNavigateToSignUp, onNavigateToForgotPassword }) => {
  const { session, signIn, signOut } = useHaqibati();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      await signIn(email, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'تعذّر تسجيل الدخول.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="flex min-h-screen flex-col bg-[var(--bg)]">
      <HaqibatiTopBar title="تسجيل الدخول" onNavigateBack={onNavigateBack} />

      <div className="flex flex-col gap-4 px-4 py-4">
        {session.isSignedIn ? (
          <HaqibatiSurfaceCard className="p-5">
            <div className="flex flex-col gap-3.5">
              <div className="flex items-center gap-2.5">
                <CheckCircle2 className="h-6 w-6 text-[var(--primary)]" />
                <h2 className="text-xl font-bold text-[var(--on-surface)]">أهلاً بك في حقيبتي</h2>
              </div>
              <p className="text-sm text-[var(--on-surface)]">
                تم تسجيل الدخول بالحساب ({session.email}) — الدور الحالي:{' '}
                {resolveRoleLabel(getEffectiveUserRole(session))}.
              </p>
              <p className="text-xs text-[var(--on-surface-variant)]">
                بيانات الاستخدام والتفضيلات الشخصية وتقدم القراءة والتنزيلات تبقى محفوظة محلياً لكل جهاز فقط ولا تُمسح عند تسجيل الخروج.
              </p>
              <button
                type="button"
                onClick={onNavigateBack}
                className="w-full rounded-xl bg-[var(--primary)] px-4 py-2.5 text-sm font-semibold text-[var(--on-primary)]"
              >
                متابعة إلى حقيبتي
              </button>
              <button
                type="button"
                onClick={signOut}
                className="w-full rounded-xl border border-[var(--outline)] px-4 py-2.5 text-sm font-semibold text-[var(--on-surface)]"
              >
                تسجيل الخروج
              </button>
            </div>
          </HaqibatiSurfaceCard>
        ) : (
          <HaqibatiSurfaceCard className="p-5">
            <form onSubmit={handleSubmit} className="flex flex-col gap-4">
              <div className="flex items-center gap-2.5">
                <LogIn className="h-6 w-6 text-[var(--primary)]" />
                <h2 className="text-xl font-bold text-[var(--on-surface)]">تسجيل الدخول</h2>
              </div>

              <p className="text-sm leading-relaxed text-[var(--on-surface-variant)]">
                سجّل الدخول بحسابك في حقيبتي. يحصل الحساب العادي على صلاحية طالب، بينما تُضبط صلاحيات المشرف والمالك من الخادم فقط.
              </p>

              {typeof __HAQIBATI_PREVIEW_DEMO_ENABLED__ === 'boolean' &&
              __HAQIBATI_PREVIEW_DEMO_ENABLED__ &&
              typeof import.meta.env === 'object' &&
              import.meta.env.MODE !== 'production' &&
              import.meta.env.VITE_ENABLE_PREVIEW_DEMO === 'true' ? (
                <div className="rounded-xl border border-[var(--outline-variant)] bg-[var(--surface-variant)]/70 p-3 text-xs leading-relaxed text-[var(--on-surface-variant)]">
                  <strong className="mb-1 block text-[var(--on-surface)]">
                    حسابات محاكاة محلية لوضع التطوير والمعاينة فقط (ليست أماناً حقيقياً ولا توجد في الإنتاج أو Android):
                  </strong>
                  طالب (<span className="font-mono">student@haqibati.edu</span> /{' '}
                  <span className="font-mono">Student1234</span>) — مشرف (
                  <span className="font-mono">admin@haqibati.edu</span> /{' '}
                  <span className="font-mono">Admin1234</span>)
                </div>
              ) : (
                <div className="rounded-xl border border-[var(--outline-variant)] bg-[var(--surface-variant)]/70 p-3 text-xs leading-relaxed text-[var(--on-surface-variant)]">
                  تم إخفاء حسابات المعاينة التجريبية خارج وضع التطوير؛ محاكاة الجلسة المحلية في الويب ليست مصادقة حقيقية.
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
                  placeholder="name@example.com"
                  className="w-full rounded-xl border border-[var(--outline)] bg-[var(--surface)] px-3.5 py-2.5 text-sm text-[var(--on-surface)] focus:border-[var(--primary)] focus:outline-none"
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-semibold text-[var(--on-surface-variant)]">
                  كلمة المرور
                </label>
                <div className="relative">
                  <input
                    type={showPassword ? 'text' : 'password'}
                    dir="ltr"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="w-full rounded-xl border border-[var(--outline)] bg-[var(--surface)] px-3.5 py-2.5 pl-10 text-sm text-[var(--on-surface)] focus:border-[var(--primary)] focus:outline-none"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((v) => !v)}
                    aria-label={showPassword ? 'إخفاء كلمة المرور' : 'إظهار كلمة المرور'}
                    className="absolute inset-y-0 left-2 flex items-center px-1 text-[var(--on-surface-variant)]"
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
              </div>

              {error && <AuthFeedbackBanner text={error} isError />}

              <button
                type="submit"
                disabled={isSubmitting}
                className="w-full rounded-xl bg-[var(--primary)] px-4 py-2.5 text-sm font-semibold text-[var(--on-primary)] transition hover:opacity-90 disabled:opacity-50"
              >
                تسجيل الدخول
              </button>

              <button
                type="button"
                onClick={onNavigateToForgotPassword}
                className="w-full py-1 text-center text-sm font-medium text-[var(--primary)] hover:underline"
              >
                نسيت كلمة المرور؟ استعادة كلمة المرور
              </button>

              <div className="h-px bg-[var(--outline-variant)]" />

              <button
                type="button"
                onClick={onNavigateToSignUp}
                className="w-full rounded-xl border border-[var(--outline)] px-4 py-2.5 text-sm font-semibold text-[var(--primary)] transition hover:bg-[var(--surface-variant)]"
              >
                ليس لديك حساب؟ إنشاء حساب طالب جديد
              </button>

              <button
                type="button"
                onClick={onNavigateBack}
                className="w-full py-1 text-center text-sm text-[var(--on-surface-variant)] hover:underline"
              >
                المتابعة كزائر
              </button>
            </form>
          </HaqibatiSurfaceCard>
        )}
      </div>
    </div>
  );
};

export const SignUpScreen: React.FC<{
  onNavigateBack: () => void;
  onNavigateToSignIn: () => void;
  onAccountReady: () => void;
}> = ({ onNavigateBack, onNavigateToSignIn, onAccountReady }) => {
  const { session, signUpStudent } = useHaqibati();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [succeeded, setSucceeded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      await signUpStudent(email, password, confirmPassword);
      setSucceeded(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'تعذّر إنشاء الحساب.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="flex min-h-screen flex-col bg-[var(--bg)]">
      <HaqibatiTopBar title="إنشاء حساب طالب" onNavigateBack={onNavigateBack} />

      <div className="flex flex-col gap-4 px-4 py-4">
        {succeeded && session.isSignedIn ? (
          <HaqibatiSurfaceCard className="p-5">
            <div className="flex flex-col gap-3.5">
              <div className="flex items-center gap-2.5">
                <CheckCircle2 className="h-6 w-6 text-[var(--primary)]" />
                <h2 className="text-xl font-bold text-[var(--on-surface)]">
                  تم إنشاء حساب الطالب بنجاح
                </h2>
              </div>
              <AuthFeedbackBanner
                text={`تم تسجيل دخولك بالحساب (${session.email}) بصلاحية طالب افتراضية.`}
                isError={false}
              />
              <p className="text-xs text-[var(--on-surface-variant)]">
                بيانات الاستخدام والتفضيلات الشخصية وتقدم القراءة والتنزيلات تبقى محفوظة محلياً لكل جهاز فقط ولا تُمسح عند تسجيل الخروج.
              </p>
              <button
                type="button"
                onClick={onAccountReady}
                className="w-full rounded-xl bg-[var(--primary)] px-4 py-2.5 text-sm font-semibold text-[var(--on-primary)]"
              >
                متابعة إلى حقيبتي
              </button>
            </div>
          </HaqibatiSurfaceCard>
        ) : (
          <HaqibatiSurfaceCard className="p-5">
            <form onSubmit={handleSubmit} className="flex flex-col gap-4">
              <div className="flex items-center gap-2.5">
                <UserPlus className="h-6 w-6 text-[var(--primary)]" />
                <h2 className="text-xl font-bold text-[var(--on-surface)]">إنشاء حساب طالب</h2>
              </div>

              <p className="text-sm text-[var(--on-surface-variant)]">
                أنشئ حساب طالب جديد باستخدام البريد الإلكتروني وكلمة المرور دون الحاجة لبيانات شخصية إضافية.
              </p>

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
                <div className="relative">
                  <input
                    type={showPassword ? 'text' : 'password'}
                    dir="ltr"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="w-full rounded-xl border border-[var(--outline)] bg-[var(--surface)] px-3.5 py-2.5 pl-10 text-sm text-[var(--on-surface)] focus:border-[var(--primary)] focus:outline-none"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((v) => !v)}
                    className="absolute inset-y-0 left-2 flex items-center px-1 text-[var(--on-surface-variant)]"
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
                <span className="text-xs text-[var(--on-surface-variant)]">
                  8 أحرف على الأقل وتتضمن حرفاً ورقماً على الأقل.
                </span>
              </div>

              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-semibold text-[var(--on-surface-variant)]">
                  تأكيد كلمة المرور
                </label>
                <input
                  type={showPassword ? 'text' : 'password'}
                  dir="ltr"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  className="w-full rounded-xl border border-[var(--outline)] bg-[var(--surface)] px-3.5 py-2.5 text-sm text-[var(--on-surface)] focus:border-[var(--primary)] focus:outline-none"
                />
              </div>

              {error && <AuthFeedbackBanner text={error} isError />}

              <button
                type="submit"
                disabled={isSubmitting}
                className="w-full rounded-xl bg-[var(--primary)] px-4 py-2.5 text-sm font-semibold text-[var(--on-primary)] transition hover:opacity-90 disabled:opacity-50"
              >
                إنشاء حساب طالب
              </button>

              <div className="h-px bg-[var(--outline-variant)]" />

              <p className="text-xs leading-relaxed text-[var(--on-surface-variant)]">
                ملاحظة أمنية: تُنشأ الحسابات الجديدة بدور «طالب» افتراضياً، ولا يمكن ترقية أي حساب إلى مشرف أو مالك من واجهة التطبيق.
              </p>

              <button
                type="button"
                onClick={onNavigateToSignIn}
                className="w-full rounded-xl border border-[var(--outline)] px-4 py-2.5 text-sm font-semibold text-[var(--primary)] transition hover:bg-[var(--surface-variant)]"
              >
                لديك حساب بالفعل؟ تسجيل الدخول
              </button>

              <button
                type="button"
                onClick={onNavigateBack}
                className="w-full py-1 text-center text-sm text-[var(--on-surface-variant)] hover:underline"
              >
                المتابعة كزائر
              </button>
            </form>
          </HaqibatiSurfaceCard>
        )}
      </div>
    </div>
  );
};

export const ForgotPasswordScreen: React.FC<{
  onNavigateBack: () => void;
}> = ({ onNavigateBack }) => {
  const { sendPasswordReset } = useHaqibati();
  const [email, setEmail] = useState('');
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSentTo(null);
    try {
      const normalized = await sendPasswordReset(email);
      setSentTo(normalized);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'تعذّر إرسال الرابط.');
    }
  };

  return (
    <div className="flex min-h-screen flex-col bg-[var(--bg)]">
      <HaqibatiTopBar title="استعادة كلمة المرور" onNavigateBack={onNavigateBack} />

      <div className="flex flex-col gap-4 px-4 py-4">
        <HaqibatiSurfaceCard className="p-5">
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex items-center gap-2.5">
              <KeyRound className="h-6 w-6 text-[var(--primary)]" />
              <h2 className="text-xl font-bold text-[var(--on-surface)]">استعادة كلمة المرور</h2>
            </div>

            <p className="text-sm text-[var(--on-surface-variant)]">
              أدخل بريدك الإلكتروني لإرسال رابط إعادة تعيين كلمة المرور عبر Firebase Authentication.
            </p>

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

            {error && <AuthFeedbackBanner text={error} isError />}
            {sentTo && (
              <AuthFeedbackBanner
                text={`أُرسلت رسالة إعادة تعيين كلمة المرور إلى (${sentTo}) إن كان البريد مرتبطاً بحساب فعّال. تحقّق من صندوق الوارد أو البريد غير المرغوب.`}
                isError={false}
              />
            )}

            <button
              type="submit"
              className="w-full rounded-xl bg-[var(--primary)] px-4 py-2.5 text-sm font-semibold text-[var(--on-primary)]"
            >
              إرسال رابط الاستعادة
            </button>

            <button
              type="button"
              onClick={onNavigateBack}
              className="w-full rounded-xl border border-[var(--outline)] px-4 py-2.5 text-sm font-semibold text-[var(--on-surface)]"
            >
              العودة إلى تسجيل الدخول
            </button>
          </form>
        </HaqibatiSurfaceCard>
      </div>
    </div>
  );
};
