import React from 'react';
import { FIREBASE_PROJECT_INFO } from '../domain/models';
import { HaqibatiSurfaceCard, HaqibatiTopBar } from '../components/LayoutChrome';

export const AboutScreen: React.FC<{
  onNavigateBack: () => void;
}> = ({ onNavigateBack }) => {
  return (
    <div className="flex min-h-screen flex-col bg-[var(--bg)]">
      <HaqibatiTopBar title="حول التطبيق" onNavigateBack={onNavigateBack} />

      <div className="flex flex-col gap-4 px-4 py-4">
        <HaqibatiSurfaceCard>
          <h2 className="text-2xl font-bold text-[var(--primary)]">حقيبتي</h2>
          <p className="mt-1 text-base text-[var(--on-surface-variant)]">مساحتك التعليمية المنظمة</p>
        </HaqibatiSurfaceCard>

        <HaqibatiSurfaceCard>
          <div className="flex flex-col gap-3.5">
            <AboutItem label="رقم الإصدار" value={FIREBASE_PROJECT_INFO.versionName} />
            <div className="h-px bg-[var(--outline-variant)]" />
            <AboutItem label="معرّف الحزمة" value={FIREBASE_PROJECT_INFO.packageId} />
            <div className="h-px bg-[var(--outline-variant)]" />
            <AboutItem label="البنية الهندسية" value="Clean Architecture + MVVM" />
            <div className="h-px bg-[var(--outline-variant)]" />
            <AboutItem
              label="المرحلة الحالية"
              value="المرحلة السادسة — قارئ PDF المدمج وتقدم القراءة المحلي"
            />
          </div>
        </HaqibatiSurfaceCard>
      </div>
    </div>
  );
};

const AboutItem: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <div>
    <div className="text-xs font-medium text-[var(--on-surface-variant)]">{label}</div>
    <div className="mt-0.5 text-sm font-semibold text-[var(--on-surface)]">{value}</div>
  </div>
);
