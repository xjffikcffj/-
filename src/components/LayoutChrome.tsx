import React from 'react';
import {
  ArrowRight,
  BookOpen,
  ChevronLeft,
  FolderOpen,
  GraduationCap,
  Home,
  Moon,
  Settings,
  Sun,
} from 'lucide-react';
import { MainTab } from '../domain/models';

export const HaqibatiTopBar: React.FC<{
  title: string;
  showBrandIcon?: boolean;
  onNavigateBack?: () => void;
  actions?: React.ReactNode;
}> = ({ title, showBrandIcon = false, onNavigateBack, actions }) => {
  return (
    <header className="sticky top-0 z-20 flex h-14 items-center justify-between border-b border-[var(--outline-variant)] bg-[var(--bg)]/95 px-3 backdrop-blur-sm">
      <div className="flex min-w-10 items-center justify-start">
        {onNavigateBack && (
          <button
            type="button"
            onClick={onNavigateBack}
            aria-label="رجوع"
            className="flex h-10 w-10 items-center justify-center rounded-full text-[var(--on-bg)] transition hover:bg-[var(--surface-variant)]"
          >
            <ArrowRight className="h-5 w-5" />
          </button>
        )}
      </div>

      <div className="flex max-w-[65%] items-center justify-center gap-2">
        {showBrandIcon && (
          <GraduationCap className="h-6 w-6 shrink-0 text-[var(--primary)]" />
        )}
        <h1 className="truncate text-lg font-bold text-[var(--on-bg)]">{title}</h1>
      </div>

      <div className="flex min-w-10 items-center justify-end gap-1">{actions}</div>
    </header>
  );
};

export const DarkModeActionButton: React.FC<{
  isDarkMode: boolean;
  onToggle: () => void;
}> = ({ isDarkMode, onToggle }) => (
  <button
    type="button"
    onClick={onToggle}
    aria-label={isDarkMode ? 'الوضع الفاتح' : 'الوضع الداكن'}
    title={isDarkMode ? 'الوضع الفاتح' : 'الوضع الداكن'}
    className="flex h-10 w-10 items-center justify-center rounded-full text-[var(--on-surface-variant)] transition hover:bg-[var(--surface-variant)]"
  >
    {isDarkMode ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
  </button>
);

export const HaqibatiBottomBar: React.FC<{
  currentTab: MainTab;
  onSelectTab: (tab: MainTab) => void;
}> = ({ currentTab, onSelectTab }) => {
  const tabs: { id: MainTab; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
    { id: 'HOME', label: 'الرئيسية', icon: Home },
    { id: 'LIBRARY', label: 'المكتبة', icon: BookOpen },
    { id: 'DOWNLOADS', label: 'التنزيلات', icon: FolderOpen },
    { id: 'SETTINGS', label: 'الإعدادات', icon: Settings },
  ];

  return (
    <nav
      aria-label="التنقل الرئيسي"
      className="sticky bottom-0 z-20 grid grid-cols-4 border-t border-[var(--outline-variant)] bg-[var(--surface)] px-2 py-1.5"
    >
      {tabs.map((tab) => {
        const selected = tab.id === currentTab;
        const Icon = tab.icon;
        return (
          <button
            key={tab.id}
            type="button"
            onClick={() => onSelectTab(tab.id)}
            className="flex flex-col items-center justify-center gap-1 py-1 transition"
          >
            <span
              className={`flex h-8 w-14 items-center justify-center rounded-full transition-colors ${
                selected
                  ? 'bg-[var(--primary-container)] text-[var(--on-primary-container)]'
                  : 'text-[var(--on-surface-variant)] hover:bg-[var(--surface-variant)]'
              }`}
            >
              <Icon className="h-5 w-5" />
            </span>
            <span
              className={`text-xs font-medium ${
                selected ? 'font-bold text-[var(--primary)]' : 'text-[var(--on-surface-variant)]'
              }`}
            >
              {tab.label}
            </span>
          </button>
        );
      })}
    </nav>
  );
};

export const HaqibatiSurfaceCard: React.FC<{
  children: React.ReactNode;
  onClick?: () => void;
  className?: string;
}> = ({ children, onClick, className = '' }) => {
  const baseClass = `w-full rounded-2xl border border-[var(--outline-variant)] bg-[var(--surface)] p-4 text-right shadow-xs ${
    onClick ? 'cursor-pointer transition hover:border-[var(--primary)]/40 hover:shadow-sm active:scale-[0.995]' : ''
  } ${className}`;

  if (onClick) {
    return (
      <button type="button" onClick={onClick} className={baseClass}>
        {children}
      </button>
    );
  }
  return <div className={baseClass}>{children}</div>;
};

export const HaqibatiSectionHeader: React.FC<{ title: string }> = ({ title }) => (
  <h2 className="text-base font-bold text-[var(--on-bg)]">{title}</h2>
);

export const HaqibatiEmptyState: React.FC<{
  icon: React.ReactNode;
  title: string;
  message: string;
}> = ({ icon, title, message }) => (
  <HaqibatiSurfaceCard className="p-6">
    <div className="flex flex-col items-center gap-3 py-2 text-center">
      <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-[var(--surface-variant)] text-[var(--primary)]">
        {icon}
      </div>
      <h3 className="text-base font-bold text-[var(--on-surface)]">{title}</h3>
      <p className="text-sm leading-relaxed text-[var(--on-surface-variant)]">{message}</p>
    </div>
  </HaqibatiSurfaceCard>
);

export const CatalogNavigationCard: React.FC<{
  icon: React.ReactNode;
  title: string;
  subtitle?: string;
  onClick: () => void;
}> = ({ icon, title, subtitle, onClick }) => (
  <HaqibatiSurfaceCard onClick={onClick}>
    <div className="flex items-center gap-3">
      <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-[var(--primary-container)] text-[var(--on-primary-container)]">
        {icon}
      </div>
      <div className="min-w-0 flex-1 text-right">
        <div className="text-base font-semibold text-[var(--on-surface)]">{title}</div>
        {subtitle && (
          <div className="mt-0.5 text-sm text-[var(--on-surface-variant)]">{subtitle}</div>
        )}
      </div>
      <ChevronLeft className="h-5 w-5 shrink-0 text-[var(--on-surface-variant)]" />
    </div>
  </HaqibatiSurfaceCard>
);

export const AuthFeedbackBanner: React.FC<{
  text: string;
  isError: boolean;
}> = ({ text, isError }) => (
  <div
    className={`w-full rounded-xl px-3.5 py-2.5 text-sm leading-relaxed ${
      isError
        ? 'bg-[var(--error-container)] text-[var(--on-error-container)]'
        : 'bg-[var(--primary-container)] text-[var(--on-primary-container)]'
    }`}
  >
    {text}
  </div>
);
