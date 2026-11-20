import React, { useEffect } from 'react';
import { GraduationCap, Loader2 } from 'lucide-react';

export const SplashScreen: React.FC<{
  onComplete: () => void;
}> = ({ onComplete }) => {
  useEffect(() => {
    const timer = setTimeout(onComplete, 850);
    return () => clearTimeout(timer);
  }, [onComplete]);

  return (
    <div
      className="flex min-h-screen w-full flex-col items-center justify-center px-6 text-center text-white"
      style={{
        background:
          'linear-gradient(180deg, #0E5A4F 0%, rgba(14, 90, 79, 0.92) 55%, #355966 100%)',
      }}
    >
      <div className="flex flex-col items-center animate-[fadeIn_0.45s_ease-out]">
        <div className="mb-6 flex h-24 w-24 items-center justify-center rounded-[28px] bg-white/15 shadow-lg backdrop-blur-xs">
          <GraduationCap className="h-12 w-12 text-white" />
        </div>

        <h1 className="text-3xl font-extrabold tracking-tight text-white">حقيبتي</h1>
        <p className="mt-2 text-base text-white/85">مساحتك التعليمية المنظمة</p>

        <div className="mt-10 flex flex-col items-center gap-3">
          <Loader2 className="h-7 w-7 animate-spin text-[#FCEFD2]" />
          <span className="text-xs font-medium text-white/80">جاري تهيئة بيئة التطبيق…</span>
        </div>
      </div>
    </div>
  );
};
