import React, { useState } from 'react';
import { MainTab } from './domain/models';
import {
  LibraryRoute,
  resolveLibraryBackRoute,
  resolveLibraryNavigation,
  resolvePublishedStudyFile,
} from './domain/catalogNavigation';
import { HaqibatiProvider, useHaqibati } from './state/HaqibatiContext';
import {
  DarkModeActionButton,
  HaqibatiBottomBar,
  HaqibatiEmptyState,
  HaqibatiTopBar,
} from './components/LayoutChrome';
import { FileText } from 'lucide-react';
import { SplashScreen } from './screens/SplashScreen';
import { HomeScreen } from './screens/HomeScreen';
import { LibraryScreen } from './screens/LibraryScreen';
import { DownloadsScreen } from './screens/DownloadsScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { AboutScreen } from './screens/AboutScreen';
import { ForgotPasswordScreen, SignInScreen, SignUpScreen } from './screens/AuthScreens';
import { AdminPortalScreen } from './screens/AdminPortalScreen';
import { PdfReaderScreen } from './screens/PdfReaderScreen';

type AppScreen =
  | { name: 'SPLASH' }
  | { name: 'MAIN' }
  | { name: 'ABOUT' }
  | { name: 'ADMIN_PORTAL' }
  | { name: 'SIGN_IN' }
  | { name: 'SIGN_UP' }
  | { name: 'FORGOT_PASSWORD' }
  | { name: 'PDF_READER'; fileId: string };

const HaqibatiShell: React.FC = () => {
  const { preferences, toggleDarkMode, catalog } = useHaqibati();
  const [screen, setScreen] = useState<AppScreen>({ name: 'SPLASH' });
  const [currentTab, setCurrentTab] = useState<MainTab>('HOME');
  const [libraryRoute, setLibraryRoute] = useState<LibraryRoute>({ level: 'BRANCHES' });

  if (screen.name === 'SPLASH') {
    return <SplashScreen onComplete={() => setScreen({ name: 'MAIN' })} />;
  }

  if (screen.name === 'PDF_READER') {
    const activeFile = resolvePublishedStudyFile(catalog, screen.fileId);
    if (!activeFile) {
      return (
        <div className="mx-auto flex min-h-screen max-w-2xl flex-col border-x border-[var(--outline-variant)]/50 bg-[var(--bg)]">
          <HaqibatiTopBar
            title="قارئ PDF"
            onNavigateBack={() => setScreen({ name: 'MAIN' })}
          />
          <div className="flex flex-1 flex-col items-center justify-center gap-4 p-6">
            <HaqibatiEmptyState
              icon={<FileText className="h-7 w-7" />}
              title="هذا الملف غير متاح حالياً"
              message="تم إلغاء نشر هذا الملف أو أحد عناصره الأبوية أو أرشفته من لوحة الإدارة."
            />
            <button
              type="button"
              onClick={() => setScreen({ name: 'MAIN' })}
              className="rounded-xl bg-[var(--primary)] px-5 py-2.5 text-sm font-semibold text-[var(--on-primary)]"
            >
              رجوع
            </button>
          </div>
        </div>
      );
    }

    return (
      <PdfReaderScreen
        key={activeFile.id}
        file={activeFile}
        onNavigateBack={() => setScreen({ name: 'MAIN' })}
      />
    );
  }

  if (screen.name === 'ABOUT') {
    return (
      <div className="mx-auto min-h-screen max-w-2xl border-x border-[var(--outline-variant)]/50 bg-[var(--bg)]">
        <AboutScreen onNavigateBack={() => setScreen({ name: 'MAIN' })} />
      </div>
    );
  }

  if (screen.name === 'ADMIN_PORTAL') {
    return (
      <div className="mx-auto min-h-screen max-w-2xl border-x border-[var(--outline-variant)]/50 bg-[var(--bg)]">
        <AdminPortalScreen onNavigateBack={() => setScreen({ name: 'MAIN' })} />
      </div>
    );
  }

  if (screen.name === 'SIGN_IN') {
    return (
      <div className="mx-auto min-h-screen max-w-2xl border-x border-[var(--outline-variant)]/50 bg-[var(--bg)]">
        <SignInScreen
          onNavigateBack={() => setScreen({ name: 'MAIN' })}
          onNavigateToSignUp={() => setScreen({ name: 'SIGN_UP' })}
          onNavigateToForgotPassword={() => setScreen({ name: 'FORGOT_PASSWORD' })}
        />
      </div>
    );
  }

  if (screen.name === 'SIGN_UP') {
    return (
      <div className="mx-auto min-h-screen max-w-2xl border-x border-[var(--outline-variant)]/50 bg-[var(--bg)]">
        <SignUpScreen
          onNavigateBack={() => setScreen({ name: 'MAIN' })}
          onNavigateToSignIn={() => setScreen({ name: 'SIGN_IN' })}
          onAccountReady={() => setScreen({ name: 'MAIN' })}
        />
      </div>
    );
  }

  if (screen.name === 'FORGOT_PASSWORD') {
    return (
      <div className="mx-auto min-h-screen max-w-2xl border-x border-[var(--outline-variant)]/50 bg-[var(--bg)]">
        <ForgotPasswordScreen onNavigateBack={() => setScreen({ name: 'SIGN_IN' })} />
      </div>
    );
  }

  // MAIN shell
  const resolvedLibrary = resolveLibraryNavigation(catalog, libraryRoute);
  const canNavigateBackWithinLibrary =
    currentTab === 'LIBRARY' && libraryRoute.level !== 'BRANCHES';

  const handleLibraryBack = () => {
    setLibraryRoute((prev) => resolveLibraryBackRoute(catalog, prev));
  };

  const topBarTitle =
    currentTab === 'HOME'
      ? 'حقيبتي'
      : currentTab === 'DOWNLOADS'
      ? 'التنزيلات'
      : currentTab === 'SETTINGS'
      ? 'الإعدادات'
      : resolvedLibrary.topBarTitle;

  return (
    <div className="mx-auto flex min-h-screen max-w-2xl flex-col border-x border-[var(--outline-variant)]/50 bg-[var(--bg)]">
      <HaqibatiTopBar
        title={topBarTitle}
        showBrandIcon={currentTab === 'HOME'}
        onNavigateBack={canNavigateBackWithinLibrary ? handleLibraryBack : undefined}
        actions={
          <DarkModeActionButton
            isDarkMode={preferences.darkModeEnabled}
            onToggle={() => toggleDarkMode(!preferences.darkModeEnabled)}
          />
        }
      />

      <main className="flex-1 overflow-y-auto pb-4">
        {currentTab === 'HOME' && <HomeScreen onNavigateToTab={setCurrentTab} />}
        {currentTab === 'LIBRARY' && (
          <LibraryScreen
            route={libraryRoute}
            onNavigate={setLibraryRoute}
            onOpenStudyFile={(file) => setScreen({ name: 'PDF_READER', fileId: file.id })}
          />
        )}
        {currentTab === 'DOWNLOADS' && (
          <DownloadsScreen
            onNavigateToSignIn={() => setScreen({ name: 'SIGN_IN' })}
            onNavigateToSignUp={() => setScreen({ name: 'SIGN_UP' })}
            onOpenStudyFile={(file) => setScreen({ name: 'PDF_READER', fileId: file.id })}
          />
        )}
        {currentTab === 'SETTINGS' && (
          <SettingsScreen
            onNavigateToAbout={() => setScreen({ name: 'ABOUT' })}
            onNavigateToAdminPortal={() => setScreen({ name: 'ADMIN_PORTAL' })}
            onNavigateToSignIn={() => setScreen({ name: 'SIGN_IN' })}
            onNavigateToSignUp={() => setScreen({ name: 'SIGN_UP' })}
          />
        )}
      </main>

      <HaqibatiBottomBar currentTab={currentTab} onSelectTab={setCurrentTab} />
    </div>
  );
};

export default function App() {
  return (
    <HaqibatiProvider>
      <HaqibatiShell />
    </HaqibatiProvider>
  );
}
