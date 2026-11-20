import React from 'react';
import {
  BookOpen,
  Cloud,
  FolderOpen,
  Globe,
  GraduationCap,
  Layers,
  Settings,
} from 'lucide-react';
import { FIREBASE_PROJECT_INFO, MainTab } from '../domain/models';
import {
  CatalogNavigationCard,
  HaqibatiSectionHeader,
  HaqibatiSurfaceCard,
} from '../components/LayoutChrome';

export const HomeScreen: React.FC<{
  onNavigateToTab: (tab: MainTab) => void;
}> = ({ onNavigateToTab }) => {
  return (
    <div className="flex flex-col gap-4 px-4 py-4">
      {/* Welcome Hero Card */}
      <div className="rounded-2xl bg-[var(--primary)] p-5 text-[var(--on-primary)] shadow-sm">
        <div className="inline-block rounded-xl bg-white/15 px-3 py-1 text-xs font-medium">
          مساحتك التعليمية المنظمة
        </div>
        <h2 className="mt-3 text-2xl font-bold leading-snug">مرحباً بك في حقيبتي</h2>
        <p className="mt-1.5 text-sm leading-relaxed opacity-90">
          تم تأسيس البنية الأساسية ونظام التنقل والواجهات الرئيسية للتطبيق باللغة العربية واتجاه RTL.
        </p>
      </div>

      {/* Quick Navigation Section */}
      <HaqibatiSectionHeader title="الأقسام الرئيسية" />

      <div className="flex flex-col gap-3">
        <CatalogNavigationCard
          icon={<BookOpen className="h-5 w-5" />}
          title="المكتبة"
          subtitle="استعراض الفروع والمواد والأقسام الدراسية"
          onClick={() => onNavigateToTab('LIBRARY')}
        />
        <CatalogNavigationCard
          icon={<FolderOpen className="h-5 w-5" />}
          title="التنزيلات"
          subtitle="إدارة الملفات المحفوظة للقراءة دون اتصال"
          onClick={() => onNavigateToTab('DOWNLOADS')}
        />
        <CatalogNavigationCard
          icon={<Settings className="h-5 w-5" />}
          title="الإعدادات"
          subtitle="المظهر، اللغة، وحالة الربط السحابي"
          onClick={() => onNavigateToTab('SETTINGS')}
        />
      </div>

      {/* Foundation Overview Card */}
      <HaqibatiSurfaceCard>
        <div className="flex flex-col gap-3.5">
          <h3 className="text-base font-bold text-[var(--on-surface)]">أساس التطبيق</h3>

          <InfoRow
            icon={<Layers className="h-5 w-5" />}
            label="البنية الهندسية"
            value="Clean Architecture + MVVM"
          />

          <div className="h-px bg-[var(--outline-variant)]" />

          <InfoRow
            icon={<Globe className="h-5 w-5" />}
            label="دعم اللغة والاتجاه"
            value="العربية الكاملة (RTL)"
          />

          <div className="h-px bg-[var(--outline-variant)]" />

          <InfoRow
            icon={<GraduationCap className="h-5 w-5" />}
            label="معرّف الحزمة"
            value={FIREBASE_PROJECT_INFO.packageId}
          />
        </div>
      </HaqibatiSurfaceCard>

      {/* Firebase Readiness Card */}
      <HaqibatiSurfaceCard>
        <div className="flex flex-col gap-3.5">
          <div className="flex items-center gap-2.5">
            <Cloud className="h-5 w-5 text-[var(--primary)]" />
            <h3 className="text-base font-bold text-[var(--on-surface)]">جاهزية الربط مع Firebase</h3>
          </div>

          <InfoRow
            icon={<Cloud className="h-5 w-5" />}
            label="المشروع المعتمد"
            value={FIREBASE_PROJECT_INFO.projectId}
          />

          <div className="h-px bg-[var(--outline-variant)]" />

          <InfoRow
            icon={<Globe className="h-5 w-5" />}
            label="موقع قاعدة البيانات"
            value={`${FIREBASE_PROJECT_INFO.firestoreDatabaseId} • ${FIREBASE_PROJECT_INFO.firestoreLocation}`}
          />

          <div className="rounded-xl bg-[var(--primary-container)] p-3.5 text-sm leading-relaxed text-[var(--on-primary-container)]">
            يعمل التطبيق حالياً بوضع المعاينة التفاعلية المتكاملة في المتصفح مع تخزين محلي للبيانات وتقدم القراءة.
          </div>
        </div>
      </HaqibatiSurfaceCard>
    </div>
  );
};

const InfoRow: React.FC<{
  icon: React.ReactNode;
  label: string;
  value: string;
}> = ({ icon, label, value }) => (
  <div className="flex items-center gap-3">
    <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-[var(--surface-variant)] text-[var(--primary)]">
      {icon}
    </div>
    <div className="min-w-0 flex-1">
      <div className="text-xs font-medium text-[var(--on-surface-variant)]">{label}</div>
      <div className="truncate text-sm font-semibold text-[var(--on-surface)]">{value}</div>
    </div>
  </div>
);
