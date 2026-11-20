import React, { act } from 'react';
import { HaqibatiProvider } from '../state/HaqibatiContext';
import { PdfReaderScreen } from './PdfReaderScreen';
import { DownloadsScreen } from './DownloadsScreen';
import { SignInScreen } from './AuthScreens';
import { SEED_BRANCHES, SEED_SECTIONS, SEED_STUDY_FILES, SEED_SUBJECTS } from '../data/seedCatalog';
import { StudyFileMetadata } from '../domain/models';
import {
  FakeElement,
  FakeEvent,
  FakeWheelEvent,
  installFakeDomEnvironment,
  setFakeInputValue,
} from '../test/minimalDom';

let createRoot: (container: Element | DocumentFragment) => {
  render: (children: React.ReactNode) => void;
  unmount: () => void;
};

function assertEqual<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message} (expected: ${String(expected)}, actual: ${String(actual)})`);
  }
}

function assertTrue(condition: boolean, message: string): void {
  if (!condition) {
    throw new Error(message);
  }
}

async function runAsyncTest(name: string, fn: () => Promise<void>): Promise<void> {
  try {
    await fn();
    console.log(`PASS DOM: ${name}`);
  } catch (err) {
    console.error(`FAIL DOM: ${name}`);
    throw err;
  }
}

function findButtonsWithText(root: FakeElement, substring: string): FakeElement[] {
  return root.querySelectorAll('button').filter((b) => b.textContent.includes(substring));
}

function wirePageScrollGeometry(
  mainEl: FakeElement,
  pageHeights: number | number[] = 800,
  gap = 20,
  viewportHeight = 700
): FakeElement[] {
  const pageCards = mainEl
    .querySelectorAll('div')
    .filter((d) => d.getAttribute('data-page-number') !== null);

  const offsets: number[] = [];
  const heights: number[] = [];
  let cursor = 0;
  pageCards.forEach((_card, index) => {
    const h = Array.isArray(pageHeights) ? pageHeights[index] ?? 800 : pageHeights;
    offsets.push(cursor);
    heights.push(h);
    cursor += h + gap;
  });

  mainEl.clientHeight = viewportHeight;
  mainEl.scrollHeight = Math.max(cursor, viewportHeight);
  mainEl.__rectOverride = () => ({
    top: 0,
    bottom: viewportHeight,
    left: 0,
    right: 400,
    width: 400,
    height: viewportHeight,
  });

  pageCards.forEach((card, index) => {
    const startOffset = offsets[index];
    const h = heights[index];
    card.__rectOverride = () => {
      const top = startOffset - mainEl.scrollTop;
      return {
        top,
        bottom: top + h,
        left: 0,
        right: 380,
        width: 380,
        height: h,
      };
    };
    card.__scrollIntoViewHook = () => {
      mainEl.scrollTop = Math.min(startOffset, Math.max(0, mainEl.scrollHeight - mainEl.clientHeight));
    };
  });

  return pageCards;
}

async function main(): Promise<void> {
  const bootstrapDom = installFakeDomEnvironment();
  ({ createRoot } = await import('react-dom/client'));
  bootstrapDom.cleanup();

  const targetFile = SEED_STUDY_FILES[0]; // 5 pages seeded

  await runAsyncTest(
    'PdfReaderScreen mounts with bounded h-dvh scroll viewport, tracks real <main> scroll, and saves reading progress',
    async () => {
      const dom = installFakeDomEnvironment();
      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      const root = createRoot(host as unknown as Element);

      try {
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: targetFile,
                onNavigateBack: () => undefined,
              })
            )
          );
        });

        const readerRootDiv = host.firstChild as FakeElement;
        assertTrue(readerRootDiv.classList.contains('h-dvh'), 'Root reader container must use h-dvh');
        assertTrue(
          readerRootDiv.classList.contains('overflow-hidden'),
          'Root reader container must lock outer height with overflow-hidden'
        );
        assertTrue(
          !readerRootDiv.classList.contains('min-h-screen'),
          'Root reader container must not use min-h-screen'
        );

        const mainEl = host.querySelector('main');
        assertTrue(mainEl !== null, '<main> scroll container must exist');
        assertTrue(mainEl!.classList.contains('min-h-0'), '<main> must have min-h-0');
        assertTrue(mainEl!.classList.contains('overflow-y-auto'), '<main> must have overflow-y-auto');

        const pageCards = wirePageScrollGeometry(mainEl!);
        assertTrue(pageCards.length >= 4, 'Expected multi-page study file cards in DOM');
        assertTrue(mainEl!.scrollHeight > mainEl!.clientHeight, '<main> must scroll inside viewport');

        // Scroll <main> to page 3 (startOffset = 2 * 820 = 1640)
        await act(async () => {
          mainEl!.scrollTop = 1640;
          mainEl!.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });

        const pageBtn = findButtonsWithText(host, 'صفحة ')[0];
        assertTrue(pageBtn !== undefined, 'Expected page indicator button');
        assertTrue(
          pageBtn.textContent.includes('صفحة 3 من'),
          `Expected indicator to update to page 3 after real <main> scroll, got "${pageBtn.textContent}"`
        );

        const storedProgress = JSON.parse(
          dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}'
        ) as Record<string, { lastPageNumber: number }>;
        assertEqual(
          storedProgress[targetFile.id]?.lastPageNumber,
          3,
          'Reading progress in localStorage must persist page 3 after <main> scroll'
        );

        // Hovering (mouseover/mouseenter) over page 4 must NOT change currentPage or saved progress.
        const page4Card = pageCards[3];
        assertTrue(page4Card !== undefined, 'Expected page 4 DOM card');
        await act(async () => {
          page4Card.dispatchEvent(new FakeEvent('mouseover', { bubbles: true }));
          page4Card.dispatchEvent(new FakeEvent('mouseenter', { bubbles: false }));
          dom.flushAnimationFrames();
        });

        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 3 من'),
          'Mouse hover over page 4 must NOT change active page from 3'
        );
        const progressAfterHover = JSON.parse(
          dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}'
        ) as Record<string, { lastPageNumber: number }>;
        assertEqual(
          progressAfterHover[targetFile.id]?.lastPageNumber,
          3,
          'Reading progress must remain 3 when moving pointer over another page'
        );
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );

  await runAsyncTest(
    'PdfReaderScreen preserves resumed saved page on initial mount without overwriting with page 1',
    async () => {
      const dom = installFakeDomEnvironment();
      dom.localStorage.setItem(
        'haqibati_reading_progress_v1',
        JSON.stringify({
          [targetFile.id]: {
            fileId: targetFile.id,
            lastPageNumber: 4,
            totalPages: 4,
            updatedAtEpochMillis: Date.now(),
          },
        })
      );

      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      const root = createRoot(host as unknown as Element);

      try {
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: targetFile,
                onNavigateBack: () => undefined,
              })
            )
          );
        });

        const mainEl = host.querySelector('main')!;
        const pageCards = wirePageScrollGeometry(mainEl);
        assertTrue(pageCards.length >= 4, 'Expected at least 4 page cards');

        // Trigger IntersectionObserver immediately while container still simulates mid-layout at scrollTop 0 (page 1 rect).
        await act(async () => {
          dom.observers.forEach((obs) => obs.triggerAll());
          dom.flushAnimationFrames();
        });

        assertTrue(
          host.textContent.includes('تمت العودة إلى آخر صفحة قرأتها (4)'),
          'Resumed page banner for page 4 must be visible'
        );
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من'),
          'Initial IntersectionObserver firing before scroll arrives must NOT overwrite saved page 4 with page 1'
        );

        const stored = JSON.parse(
          dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}'
        ) as Record<string, { lastPageNumber: number }>;
        assertEqual(
          stored[targetFile.id]?.lastPageNumber,
          4,
          'Saved progress in localStorage must still equal resumed page 4'
        );

        // Simulate tapping Previous Page (jump to page 3) then mid-scroll event while still on page 1 rect:
        // programmatic lock must keep target page 3 until target page arrives.
        const prevBtn = host
          .querySelectorAll('button')
          .find((b) => b.getAttribute('aria-label') === 'الصفحة السابقة')!;
        await act(async () => {
          prevBtn.dispatchEvent(new FakeEvent('click', { bubbles: true }));
        });
        // Manually simulate mid-animation frame still near top (page 1 rect) before smooth scroll reaches page 3.
        await act(async () => {
          mainEl.scrollTop = 0;
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 3 من'),
          'Programmatic jump must NOT unlock and overwrite target page 3 with intermediate page 1 mid-scroll'
        );
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );

  await runAsyncTest(
    'DownloadsScreen blocks and hides downloaded files when parent branch/subject/section is unpublished',
    async () => {
      const dom = installFakeDomEnvironment();
      const targetSection = SEED_SECTIONS.find((s) => s.id === targetFile.sectionId)!;
      const targetSubject = SEED_SUBJECTS.find((s) => s.id === targetSection.subjectId)!;

      dom.localStorage.setItem(
        'haqibati_session_v1',
        JSON.stringify({
          isSignedIn: true,
          uid: 'student_001',
          email: 'student@haqibati.edu',
          role: 'NONE',
          hasLoadedTokenClaims: true,
          areClaimsConfirmedCurrent: true,
        })
      );
      dom.localStorage.setItem('haqibati_downloads_v1', JSON.stringify([targetFile.id]));
      dom.localStorage.setItem(
        'haqibati_catalog_v2',
        JSON.stringify({
          branches: SEED_BRANCHES,
          subjects: SEED_SUBJECTS.map((s) =>
            s.id === targetSubject.id ? { ...s, isPublished: false } : s
          ),
          sections: SEED_SECTIONS,
          studyFiles: SEED_STUDY_FILES,
        })
      );

      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      const root = createRoot(host as unknown as Element);
      let openedFile: StudyFileMetadata | null = null;

      try {
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(DownloadsScreen, {
                onNavigateToSignIn: () => undefined,
                onNavigateToSignUp: () => undefined,
                onOpenStudyFile: (f) => {
                  openedFile = f;
                },
              })
            )
          );
        });

        assertTrue(
          !host.textContent.includes(targetFile.title),
          'Downloaded file under an unpublished parent subject must not appear or be openable in DownloadsScreen'
        );
        assertEqual(openedFile, null, 'onOpenStudyFile must not trigger for unpublished parent hierarchy');
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );

  await runAsyncTest(
    'PdfReaderScreen preserves short trailing last page on jump and resume, while letting subsequent natural scroll transition smoothly',
    async () => {
      const dom = installFakeDomEnvironment();
      const shortLastFile = SEED_STUDY_FILES[0]; // 4 pages seeded
      const shortHeights = [800, 800, 800, 250];
      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      let root = createRoot(host as unknown as Element);

      try {
        // Part A: Jump to short last page (4)
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: shortLastFile,
                onNavigateBack: () => undefined,
              })
            )
          );
        });

        const mainEl = host.querySelector('main')!;
        const pageCards = wirePageScrollGeometry(mainEl, shortHeights, 20);
        assertEqual(pageCards.length, 4, 'Expected 4 seeded pages');

        // Step to page 4 via Next Page button (1 -> 2 -> 3 -> 4)
        const nextBtn = () =>
          host.querySelectorAll('button').find((b) => b.getAttribute('aria-label') === 'الصفحة التالية')!;
        for (let step = 0; step < 3; step++) {
          await act(async () => {
            nextBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
            mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
            dom.flushAnimationFrames();
          });
        }

        // Now container is at maxScrollTop; page 3 occupies more pixels (410px > 250px).
        // Fire scroll, horizontal pan, IntersectionObserver, and scrollend at maxScrollTop -> MUST stay on page 4!
        await act(async () => {
          mainEl.scrollLeft = 140;
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.observers.forEach((obs) => obs.triggerAll());
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });

        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من 4'),
          `Short last page must NOT revert to page 3 at maxScrollTop, got "${findButtonsWithText(host, 'صفحة ')[0].textContent}"`
        );
        const progressAtBottom = JSON.parse(
          dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}'
        ) as Record<string, { lastPageNumber: number }>;
        assertEqual(
          progressAtBottom[shortLastFile.id]?.lastPageNumber,
          4,
          'Saved progress must remain page 4 on short trailing page'
        );

        // Part B: Natural scroll UP after jump finishes MUST transition back to page 3
        await act(async () => {
          mainEl.scrollTop = 1640; // squarely inside page 3
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 3 من 4'),
          'Natural scroll upward after arriving at short last page must update to page 3'
        );

        // Part C: Resume directly from saved short last page 4
        await act(async () => {
          root.unmount();
        });
        dom.localStorage.setItem(
          'haqibati_reading_progress_v1',
          JSON.stringify({
            [shortLastFile.id]: {
              fileId: shortLastFile.id,
              lastPageNumber: 4,
              totalPages: 4,
              updatedAtEpochMillis: Date.now(),
            },
          })
        );

        root = createRoot(host as unknown as Element);
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: shortLastFile,
                onNavigateBack: () => undefined,
              })
            )
          );
        });
        const resumedMain = host.querySelector('main')!;
        wirePageScrollGeometry(resumedMain, shortHeights, 20);
        // Simulate auto-resume scroll landing at maxScrollTop
        resumedMain.scrollTop = Math.max(0, resumedMain.scrollHeight - resumedMain.clientHeight);
        await act(async () => {
          dom.observers.forEach((obs) => obs.triggerAll());
          resumedMain.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من 4'),
          'Resuming short last page must remain on page 4 after observer & scrollend settle'
        );

        // Part D: Natural upward scroll after resuming short last page transitions cleanly to earlier pages
        await act(async () => {
          resumedMain.scrollTop = 820; // page 2
          resumedMain.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 2 من 4'),
          'Natural upward scroll after resuming short last page must transition cleanly to page 2'
        );
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );

  await runAsyncTest(
    'PdfReaderScreen ignores horizontal/premature scrollend, handles stall recovery/cancel cleanly, and resets locks on file switch',
    async () => {
      const dom = installFakeDomEnvironment();
      const fileA = SEED_STUDY_FILES[0];
      const fileB = SEED_STUDY_FILES[1];
      dom.localStorage.setItem(
        'haqibati_reading_progress_v1',
        JSON.stringify({
          [fileB.id]: {
            fileId: fileB.id,
            lastPageNumber: 2,
            totalPages: 4,
            updatedAtEpochMillis: Date.now(),
          },
        })
      );

      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      const root = createRoot(host as unknown as Element);

      try {
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: fileA,
                onNavigateBack: () => undefined,
              })
            )
          );
        });

        const mainEl = host.querySelector('main')!;
        const pageCards = wirePageScrollGeometry(mainEl, 800, 20);
        // Disable automatic scrollTop movement so we can test delayed scrollend, horizontal pan, and stall
        pageCards.forEach((c) => {
          c.__scrollIntoViewHook = () => undefined;
        });

        const nextBtn = host
          .querySelectorAll('button')
          .find((b) => b.getAttribute('aria-label') === 'الصفحة التالية')!;

        // Click Next -> targets page 2, while scrollTop stays at 0
        await act(async () => {
          nextBtn.dispatchEvent(new FakeEvent('click', { bubbles: true }));
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 2 من'),
          'Page indicator should show target page 2'
        );

        // 1. Horizontal scroll, horizontal wheel pan & scrollend while zoomed must NOT break vertical lock or revert page 2 -> 1
        await act(async () => {
          mainEl.dispatchEvent(new FakeWheelEvent('wheel', { bubbles: true, deltaX: 90, deltaY: 0 }));
          mainEl.scrollLeft = 180;
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 2 من'),
          'Horizontal wheel, scrollLeft change, and horizontal scrollend must NOT release vertical programmatic lock'
        );

        // 2. Unrelated scrollend before vertical scroll started must NOT unlock
        await act(async () => {
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 2 من'),
          'Premature/delayed scrollend with zero vertical movement must keep target lock intact'
        );

        // 3. Stall timeout (2500ms) when target never arrives must keep actual visible page 1 in localStorage
        // (never saving unreached target page 2) and must show safe recoverable banner
        await act(async () => {
          dom.advanceTimersBy(2600);
          dom.flushAnimationFrames();
        });
        assertTrue(
          host.textContent.includes('لم يكتمل التمرير التلقائي إلى صفحة 2.'),
          'Stall fallback expiry must show safe recovery banner'
        );
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 1 من'),
          'After stall, indicator must reflect actual reached page 1 rather than unreached page 2'
        );
        const progressAfterTimeout = JSON.parse(
          dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}'
        ) as Record<string, { lastPageNumber: number }>;
        assertEqual(
          progressAfterTimeout[fileA.id]?.lastPageNumber,
          1,
          'Emergency stall timeout must NOT persist unreached target page 2 into localStorage'
        );

        // Click "إلغاء" -> safely cancels target and keeps actual visible page 1 saved
        await act(async () => {
          findButtonsWithText(host, 'إلغاء')[0].dispatchEvent(new FakeEvent('click', { bubbles: true }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          !host.textContent.includes('لم يكتمل التمرير التلقائي'),
          'Cancelling stalled state should dismiss recovery banner'
        );
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 1 من'),
          'Cancelling stalled jump should sync to visible page 1'
        );

        // 3b. Partial vertical scroll that stops short (scrollTop=100) followed by scrollend also enters recovery safely
        await act(async () => {
          nextBtn.dispatchEvent(new FakeEvent('click', { bubbles: true }));
        });
        await act(async () => {
          mainEl.scrollTop = 100;
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          host.textContent.includes('لم يكتمل التمرير التلقائي إلى صفحة 2.'),
          'Vertical scrollend stopping short of target must show safe recovery banner'
        );
        await act(async () => {
          findButtonsWithText(host, 'إلغاء')[0].dispatchEvent(new FakeEvent('click', { bubbles: true }));
          dom.flushAnimationFrames();
        });

        // 4. Test switching fileA -> fileB while programmatic lock & stall timer are active on fileA (locked on page 3):
        // old lock/timers must clear cleanly and NOT overwrite fileB's saved page 2!
        await act(async () => {
          nextBtn.dispatchEvent(new FakeEvent('click', { bubbles: true })); // locks page 2 on fileA
        });
        await act(async () => {
          nextBtn.dispatchEvent(new FakeEvent('click', { bubbles: true })); // locks page 3 on fileA + arms 2500ms timer
        });
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: fileB,
                onNavigateBack: () => undefined,
              })
            )
          );
        });
        await act(async () => {
          dom.advanceTimersBy(3000);
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 2 من'),
          'Switching file while locked must load new file saved page 2 instead of carrying previous lock'
        );
        assertTrue(
          !host.textContent.includes('لم يكتمل التمرير التلقائي إلى صفحة 3'),
          'Stall timer from previous fileA must be cancelled on file switch and never surface on fileB'
        );
        const progressAfterSwitch = JSON.parse(
          dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}'
        ) as Record<string, { lastPageNumber: number }>;
        assertEqual(
          progressAfterSwitch[fileB.id]?.lastPageNumber,
          2,
          'Switching files mid-lock must preserve fileB saved page 2 without contamination'
        );
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );

  await runAsyncTest(
    'PdfReaderScreen resolves scrollend cleanly across preceding/interleaved horizontal pan, delayed scrollend, consecutive jumps, and free horizontal wheel',
    async () => {
      const dom = installFakeDomEnvironment();
      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      const root = createRoot(host as unknown as Element);

      try {
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: targetFile,
                onNavigateBack: () => undefined,
              })
            )
          );
        });

        const mainEl = host.querySelector('main')!;
        const pageCards = wirePageScrollGeometry(mainEl, 800, 20);
        // Let scrollIntoView remain decoupled so we control smooth scroll frames explicitly.
        pageCards.forEach((c) => {
          c.__scrollIntoViewHook = () => undefined;
        });

        const nextBtn = () =>
          host.querySelectorAll('button').find((b) => b.getAttribute('aria-label') === 'الصفحة التالية')!;

        // 6. Free horizontal wheel/scroll without active vertical jump leaves page 1 intact.
        await act(async () => {
          mainEl.dispatchEvent(new FakeWheelEvent('wheel', { bubbles: true, deltaX: 120, deltaY: 0 }));
          mainEl.scrollLeft = 120;
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 1 من'),
          'Free horizontal wheel/pan with no active jump must not change page'
        );

        // 1 & 3. Horizontal movement preceding a vertical jump must NOT trap vertical scrollend -> unlocks immediately on arrival.
        await act(async () => {
          nextBtn().dispatchEvent(new FakeEvent('click', { bubbles: true })); // targets page 2
        });
        await act(async () => {
          mainEl.scrollTop = 820; // arrived at page 2
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          !host.textContent.includes('لم يكتمل التمرير التلقائي'),
          'Completed vertical scrollend after earlier horizontal pan must unlock immediately without stall banner'
        );
        const progressAtPage2 = JSON.parse(
          dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}'
        ) as Record<string, { lastPageNumber: number }>;
        assertEqual(progressAtPage2[targetFile.id]?.lastPageNumber, 2, 'Arrived page 2 must be persisted');

        // 2. Interleaved horizontal pan during active vertical jump + horizontal scrollend keeps jump lock,
        // and once vertical arrival happens, scrollend unlocks cleanly without waiting 2500ms.
        await act(async () => {
          nextBtn().dispatchEvent(new FakeEvent('click', { bubbles: true })); // targets page 3
        });
        await act(async () => {
          mainEl.scrollTop = 980; // partial vertical move
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          mainEl.scrollLeft = 240; // interleaved horizontal pan
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false })); // horizontal pan ends
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 3 من'),
          'Interleaved horizontal scrollend during vertical jump must preserve target 3 lock'
        );
        assertTrue(
          !host.textContent.includes('لم يكتمل التمرير التلقائي'),
          'Interleaved horizontal scrollend must not falsely trigger stall'
        );
        // Complete vertical movement to page 3 (with another horizontal drift right at end) -> scrollend must unlock immediately.
        await act(async () => {
          mainEl.scrollTop = 1640; // squarely on page 3
          mainEl.scrollLeft = 260;
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        await act(async () => {
          dom.advanceTimersBy(2600);
        });
        assertTrue(
          !host.textContent.includes('لم يكتمل التمرير التلقائي'),
          'Completed vertical jump must unlock on scrollend without firing 2500ms stall later'
        );
        assertEqual(
          JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[targetFile.id]?.lastPageNumber,
          3,
          'Page 3 must be persisted once reached'
        );

        // 4 & 5. Two consecutive jumps (3 -> 4, then immediately -> 5) plus delayed scrollend from jump 1 before jump 2 moves.
        // Wait: test jumping 3 -> 4, moving slightly, then jumping 4 -> 2, receiving delayed scrollend before moving toward 2.
        await act(async () => {
          nextBtn().dispatchEvent(new FakeEvent('click', { bubbles: true })); // targets page 4
        });
        await act(async () => {
          mainEl.scrollTop = 1700;
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
        });
        // Second consecutive jump before first finishes (target page 2 via Previous twice: 4 -> 3 -> 2)
        const prevBtn = host
          .querySelectorAll('button')
          .find((b) => b.getAttribute('aria-label') === 'الصفحة السابقة')!;
        await act(async () => {
          prevBtn.dispatchEvent(new FakeEvent('click', { bubbles: true })); // targets 3
          prevBtn.dispatchEvent(new FakeEvent('click', { bubbles: true })); // targets 2
        });
        // Delayed scrollend arrives before new jump moves vertically
        await act(async () => {
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 2 من'),
          'Delayed scrollend from previous jump must not terminate newer transition to page 2'
        );
        assertTrue(
          !host.textContent.includes('لم يكتمل التمرير التلقائي'),
          'Delayed scrollend before new jump movement must not trigger stall'
        );
        // Finally new jump reaches page 2 -> unlocks & persists 2
        await act(async () => {
          mainEl.scrollTop = 820;
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertEqual(
          JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[targetFile.id]?.lastPageNumber,
          2,
          'Consecutive jump target page 2 must be persisted upon arrival'
        );
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );

  await runAsyncTest(
    'PdfReaderScreen preserves short last page across zoom-out at bottom edge, then releases on manual scroll, jump, or file switch',
    async () => {
      const dom = installFakeDomEnvironment();
      const fileA = SEED_STUDY_FILES[0]; // 4 pages
      const fileB = SEED_STUDY_FILES[1];
      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      const root = createRoot(host as unknown as Element);

      // Helper to re-wire scaled page heights dynamically for zoomScale changes
      const wireScaledGeometry = (mainEl: FakeElement, scale: number) => {
        const baseHeights = [800, 800, 800, 220];
        const scaledHeights = baseHeights.map((h) => Math.round(h * scale));
        wirePageScrollGeometry(mainEl, scaledHeights, Math.round(20 * scale));
      };

      try {
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: fileA,
                onNavigateBack: () => undefined,
              })
            )
          );
        });

        const mainEl = host.querySelector('main')!;
        const zoomInBtn = () =>
          host.querySelectorAll('button').find((b) => b.getAttribute('aria-label') === 'تكبير')!;
        const zoomOutBtn = () =>
          host.querySelectorAll('button').find((b) => b.getAttribute('aria-label') === 'تصغير')!;
        const nextBtn = () =>
          host.querySelectorAll('button').find((b) => b.getAttribute('aria-label') === 'الصفحة التالية')!;
        const prevBtn = () =>
          host.querySelectorAll('button').find((b) => b.getAttribute('aria-label') === 'الصفحة السابقة')!;

        // 1. Zoom in to 160% (1.0 -> 1.15 -> 1.3 -> 1.45 -> 1.6)
        for (let i = 0; i < 4; i++) {
          await act(async () => {
            zoomInBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
          });
        }
        assertTrue(host.textContent.includes('160%'), 'Expected zoom indicator at 160%');
        wireScaledGeometry(mainEl, 1.6);

        // Jump to short last page 4 at 160%
        for (let step = 0; step < 3; step++) {
          await act(async () => {
            nextBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
            mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
            dom.flushAnimationFrames();
          });
        }
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من 4'),
          'Expected page 4 pinned at 160% zoom'
        );

        // 2. Repeatedly zoom out at bottom edge (160% -> 145% -> 130% -> 115% -> 100%)
        // Browser clamps scrollTop down as scrollHeight shrinks -> MUST stay on page 4!
        const scales = [1.45, 1.3, 1.15, 1.0];
        for (const s of scales) {
          await act(async () => {
            zoomOutBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
            wireScaledGeometry(mainEl, s);
            mainEl.scrollTop = Math.max(0, mainEl.scrollHeight - mainEl.clientHeight);
            mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
            dom.flushAnimationFrames();
          });
          assertTrue(
            findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من 4'),
            `Short last page must stay pinned when zooming out to ${Math.round(s * 100)}%`
          );
          assertEqual(
            JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[fileA.id]?.lastPageNumber,
            4,
            `Saved progress must remain page 4 after zoom out to ${Math.round(s * 100)}%`
          );
        }

        // 3. Manual upward scroll after zoom-out MUST transition cleanly back to page 3
        await act(async () => {
          mainEl.scrollTop = 1640;
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 3 من 4'),
          'Manual upward scroll after zoom-out must release short page pin and move to page 3'
        );

        // 4. Jump back to page 4, zoom out once, then jump to another page (Previous Page -> 3)
        await act(async () => {
          zoomInBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
          wireScaledGeometry(mainEl, 1.15);
          nextBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من 4'),
          'Expected jump back to short last page 4 before testing zoom-out + jump release'
        );
        await act(async () => {
          zoomOutBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
          wireScaledGeometry(mainEl, 1.0);
          mainEl.scrollTop = Math.max(0, mainEl.scrollHeight - mainEl.clientHeight);
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        await act(async () => {
          prevBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 3 من 4'),
          'Jumping to another page after zoom-out must work cleanly'
        );

        // 5. Re-pin short last page 4, then switch to fileB -> pin clears cleanly and fileB opens at page 1
        await act(async () => {
          nextBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من 4'),
          'Expected short last page 4 to re-pin before testing file switch'
        );
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: fileB,
                onNavigateBack: () => undefined,
              })
            )
          );
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 1 من'),
          'Switching file while last page was pinned must reset cleanly to new file page 1'
        );
        assertEqual(
          JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[fileB.id]?.lastPageNumber,
          1,
          'New file progress must start cleanly at 1'
        );
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );

  await runAsyncTest(
    'PdfReaderScreen never persists unreached target page on stall or close, saves actual visible page on cancel, and commits target after recovery',
    async () => {
      const dom = installFakeDomEnvironment();
      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      let root = createRoot(host as unknown as Element);

      try {
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: targetFile,
                onNavigateBack: () => undefined,
              })
            )
          );
        });

        const mainEl = host.querySelector('main')!;
        const pageCards = wirePageScrollGeometry(mainEl, 800, 20);
        // Break scrollIntoView initially so jump to page 4 stalls after partial move to page 2.
        pageCards.forEach((c) => {
          c.__scrollIntoViewHook = () => undefined;
        });

        // Open numeric jump dialog, enter "4" into <input>, and click "انتقال"
        const pageBtn = findButtonsWithText(host, 'صفحة ')[0];
        await act(async () => {
          pageBtn.dispatchEvent(new FakeEvent('click', { bubbles: true }));
        });
        const inputEl = host.querySelector('input')!;
        await act(async () => {
          setFakeInputValue(inputEl, '4');
        });
        await act(async () => {
          findButtonsWithText(host, 'انتقال')
            .find((b) => b.textContent.trim() === 'انتقال')!
            .dispatchEvent(new FakeEvent('click', { bubbles: true }));
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من'),
          'Submitting numeric jump dialog for page 4 must update displayed target to 4'
        );

        // 1. Before arrival: localStorage MUST still hold 1 (never 4!)
        assertEqual(
          JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[targetFile.id]?.lastPageNumber,
          1,
          'Pending jump to page 4 must NOT persist 4 before arrival'
        );

        // Partial vertical scroll reaches page 2 (scrollTop=820) and stops short -> scrollend stalls jump to 4.
        await act(async () => {
          mainEl.scrollTop = 820;
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          host.textContent.includes('لم يكتمل التمرير التلقائي إلى صفحة 4.'),
          'Stopping short of page 4 must preserve target 4 in stall banner'
        );

        // 2. Closing/unmounting reader while stalled must leave visible page 2 saved, NEVER unreached 4!
        await act(async () => {
          root.unmount();
        });
        assertEqual(
          JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[targetFile.id]?.lastPageNumber,
          2,
          'Closing reader during stall must leave actual visible page 2 in localStorage, never unreached page 4'
        );

        // Remount from saved page 2, verify it opens at page 2 (not 4), then test Cancel & Resume-Recovery
        root = createRoot(host as unknown as Element);
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: targetFile,
                onNavigateBack: () => undefined,
              })
            )
          );
        });
        const reopenedMain = host.querySelector('main')!;
        const reopenedCards = wirePageScrollGeometry(reopenedMain, 800, 20);
        reopenedMain.scrollTop = 820;
        await act(async () => {
          dom.observers.forEach((obs) => obs.triggerAll());
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 2 من'),
          'Reopening after stalled jump must resume at actual reached page 2'
        );

        // Simulate jump to page 4 failing via timeout while stationary on page 2, then cancel -> saves 2
        reopenedCards.forEach((c) => {
          c.__scrollIntoViewHook = () => undefined;
        });
        const nextBtnReopened = host
          .querySelectorAll('button')
          .find((b) => b.getAttribute('aria-label') === 'الصفحة التالية')!;
        await act(async () => {
          nextBtnReopened.dispatchEvent(new FakeEvent('click', { bubbles: true })); // 3
          nextBtnReopened.dispatchEvent(new FakeEvent('click', { bubbles: true })); // 4
          dom.advanceTimersBy(2600);
          dom.flushAnimationFrames();
        });
        assertTrue(
          host.textContent.includes('لم يكتمل التمرير التلقائي إلى صفحة 4.'),
          'Expected stall recovery banner for unreached page 4 before cancel test'
        );
        await act(async () => {
          findButtonsWithText(host, 'إلغاء')[0].dispatchEvent(new FakeEvent('click', { bubbles: true }));
          dom.flushAnimationFrames();
        });
        assertEqual(
          JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[targetFile.id]?.lastPageNumber,
          2,
          'Cancelling stalled jump must persist actual visible page 2'
        );

        // 4 & 5. Trigger jump to page 4, let it stall, restore real scrollIntoView, click "متابعة الانتقال" -> reaches 4 & saves 4!
        await act(async () => {
          nextBtnReopened.dispatchEvent(new FakeEvent('click', { bubbles: true })); // 3
          nextBtnReopened.dispatchEvent(new FakeEvent('click', { bubbles: true })); // 4
          dom.advanceTimersBy(2600);
          dom.flushAnimationFrames();
        });
        assertTrue(
          host.textContent.includes('لم يكتمل التمرير التلقائي إلى صفحة 4.'),
          'Expected stall recovery banner for unreached page 4 before recovery test'
        );

        wirePageScrollGeometry(reopenedMain, 800, 20); // restore working scrollIntoView
        await act(async () => {
          findButtonsWithText(host, 'متابعة الانتقال')[0].dispatchEvent(
            new FakeEvent('click', { bubbles: true })
          );
          reopenedMain.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          reopenedMain.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          !host.textContent.includes('لم يكتمل التمرير التلقائي'),
          'Continuing stalled transition and arriving at page 4 must dismiss recovery banner'
        );
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من'),
          'UI must show arrived page 4'
        );
        assertEqual(
          JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[targetFile.id]?.lastPageNumber,
          4,
          'Target page 4 must be persisted to localStorage only after actual arrival'
        );
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );

  await runAsyncTest(
    'PdfReaderScreen supports real numeric jump dialog input submission and validation',
    async () => {
      const dom = installFakeDomEnvironment();
      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      const root = createRoot(host as unknown as Element);

      try {
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: targetFile,
                onNavigateBack: () => undefined,
              })
            )
          );
        });

        const mainEl = host.querySelector('main')!;
        wirePageScrollGeometry(mainEl, 800, 20);

        // Open jump dialog, test invalid input error, then enter valid page 3 and submit "انتقال"
        await act(async () => {
          findButtonsWithText(host, 'صفحة ')[0].dispatchEvent(new FakeEvent('click', { bubbles: true }));
        });
        let inputEl = host.querySelector('input')!;
        assertTrue(inputEl !== null, 'Numeric jump dialog input must render when opened');
        await act(async () => {
          setFakeInputValue(inputEl, '99');
        });
        await act(async () => {
          findButtonsWithText(host, 'انتقال')
            .find((b) => b.textContent.trim() === 'انتقال')!
            .dispatchEvent(new FakeEvent('click', { bubbles: true }));
        });
        assertTrue(
          host.textContent.includes('أدخل رقماً صحيحاً بين 1 و 4.'),
          'Invalid page input must display validation error without closing dialog'
        );

        inputEl = host.querySelector('input')!;
        await act(async () => {
          setFakeInputValue(inputEl, '3');
        });
        await act(async () => {
          findButtonsWithText(host, 'انتقال')
            .find((b) => b.textContent.trim() === 'انتقال')!
            .dispatchEvent(new FakeEvent('click', { bubbles: true }));
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          mainEl.dispatchEvent(new FakeEvent('scrollend', { bubbles: false }));
          dom.flushAnimationFrames();
        });

        assertTrue(
          host.querySelector('input') === null,
          'Jump dialog must close after valid submission'
        );
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 3 من 4'),
          'Valid numeric dialog jump must arrive at page 3'
        );
        assertEqual(
          JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[targetFile.id]?.lastPageNumber,
          3,
          'Arriving at page 3 via numeric jump dialog must persist page 3'
        );
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );

  await runAsyncTest(
    'PdfReaderScreen pins short last page after manual scroll across zoom-out and container/window resize, releases on upward scroll, and clears on file switch',
    async () => {
      const dom = installFakeDomEnvironment();
      const fileA = SEED_STUDY_FILES[0]; // 4 pages
      const fileB = SEED_STUDY_FILES[1];
      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      const root = createRoot(host as unknown as Element);

      const wireScaledGeometry = (mainEl: FakeElement, scale: number, viewportHeight = 700) => {
        const baseHeights = [800, 800, 800, 220];
        const scaledHeights = baseHeights.map((h) => Math.round(h * scale));
        wirePageScrollGeometry(mainEl, scaledHeights, Math.round(20 * scale), viewportHeight);
      };

      try {
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: fileA,
                onNavigateBack: () => undefined,
              })
            )
          );
        });

        const mainEl = host.querySelector('main')!;
        const zoomInBtn = () =>
          host.querySelectorAll('button').find((b) => b.getAttribute('aria-label') === 'تكبير')!;
        const zoomOutBtn = () =>
          host.querySelectorAll('button').find((b) => b.getAttribute('aria-label') === 'تصغير')!;

        // 1. Zoom in to 160% and manually scroll to bottom edge (NO next/jump button used!)
        for (let i = 0; i < 4; i++) {
          await act(async () => {
            zoomInBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
          });
        }
        wireScaledGeometry(mainEl, 1.6, 700);
        await act(async () => {
          mainEl.scrollTop = Math.max(0, mainEl.scrollHeight - mainEl.clientHeight);
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من 4'),
          'Manual scroll to bottom at 160% zoom must activate short last page 4'
        );

        // Zoom out 160% -> 145% -> 130% -> 100% after manual arrival -> MUST stay on page 4!
        for (const scale of [1.45, 1.3, 1.15, 1.0]) {
          await act(async () => {
            zoomOutBtn().dispatchEvent(new FakeEvent('click', { bubbles: true }));
            wireScaledGeometry(mainEl, scale, 700);
            mainEl.scrollTop = Math.max(0, mainEl.scrollHeight - mainEl.clientHeight);
            mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
            dom.flushAnimationFrames();
          });
          assertTrue(
            findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من 4'),
            `Manual-scroll short last page must stay pinned across zoom-out to ${Math.round(scale * 100)}%`
          );
        }

        // 2. Container / window resize (viewport grows taller from 700 to 860 AND toolbar hides/shows, shrinking maxScrollTop)
        await act(async () => {
          mainEl.dispatchEvent(new FakeEvent('click', { bubbles: false })); // hides top/bottom toolbars
          wireScaledGeometry(mainEl, 1.0, 860);
          mainEl.scrollTop = Math.max(0, mainEl.scrollHeight - mainEl.clientHeight);
          dom.dispatchWindowResize();
          dom.resizeObservers.forEach((ro) => ro.triggerAll());
          dom.flushAnimationFrames();
        });
        await act(async () => {
          mainEl.dispatchEvent(new FakeEvent('click', { bubbles: false })); // shows toolbars again to inspect page label
          dom.resizeObservers.forEach((ro) => ro.triggerAll());
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من 4'),
          'Viewport/container resize + toolbar toggle at bottom edge must keep short last page 4 pinned'
        );
        assertEqual(
          JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[fileA.id]?.lastPageNumber,
          4,
          'Saved progress must remain page 4 after resize at bottom edge'
        );

        // 3. Slow manual upward scroll in small 15px steps -> MUST release pin (must NOT creep upward) and transition to page 3!
        wireScaledGeometry(mainEl, 1.0, 700);
        mainEl.scrollTop = Math.max(0, mainEl.scrollHeight - mainEl.clientHeight);
        await act(async () => {
          dom.resizeObservers.forEach((ro) => ro.triggerAll());
          dom.flushAnimationFrames();
        });
        for (const stepTop of [
          mainEl.scrollTop - 15,
          mainEl.scrollTop - 30,
          mainEl.scrollTop - 45,
        ]) {
          await act(async () => {
            mainEl.scrollTop = stepTop;
            mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
            dom.flushAnimationFrames();
          });
        }
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 3 من 4'),
          'Manual upward scroll (>24px cumulative) must release short last page pin cleanly without creeping'
        );

        // 4. Scroll manually back to bottom edge to re-pin page 4, then switch to fileB -> no pin leak!
        await act(async () => {
          mainEl.scrollTop = Math.max(0, mainEl.scrollHeight - mainEl.clientHeight);
          mainEl.dispatchEvent(new FakeEvent('scroll', { bubbles: false }));
          dom.flushAnimationFrames();
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 4 من 4'),
          'Scrolling manually back to bottom edge must re-pin page 4'
        );

        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(PdfReaderScreen, {
                file: fileB,
                onNavigateBack: () => undefined,
              })
            )
          );
        });
        assertTrue(
          findButtonsWithText(host, 'صفحة ')[0].textContent.includes('صفحة 1 من'),
          'Switching files after manual-scroll bottom pin must reset cleanly to new file page 1'
        );
        assertEqual(
          JSON.parse(dom.localStorage.getItem('haqibati_reading_progress_v1') || '{}')[fileB.id]?.lastPageNumber,
          1,
          'New file must not inherit manual pin from previous file'
        );
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );

  await runAsyncTest(
    'SignInScreen hides demo credentials outside explicit preview-demo mode',
    async () => {
      const dom = installFakeDomEnvironment();
      const host = dom.document.createElement('div');
      dom.document.body.appendChild(host);
      const root = createRoot(host as unknown as Element);

      try {
        await act(async () => {
          root.render(
            React.createElement(
              HaqibatiProvider,
              null,
              React.createElement(SignInScreen, {
                onNavigateBack: () => undefined,
                onNavigateToSignUp: () => undefined,
                onNavigateToForgotPassword: () => undefined,
              })
            )
          );
        });

        const forbidden = [
          'owner@haqibati.edu',
          'Owner1234',
          'admin@haqibati.edu',
          'Admin1234',
          'student@haqibati.edu',
          'Student1234',
        ];
        for (const token of forbidden) {
          assertTrue(
            !host.textContent.includes(token),
            `Production/default SignInScreen must not render demo token: ${token}`
          );
        }
        assertTrue(
          host.textContent.includes('تم إخفاء حسابات المعاينة التجريبية خارج وضع التطوير'),
          'SignInScreen should display non-preview notice when demo credentials are disabled'
        );
      } finally {
        await act(async () => {
          root.unmount();
        });
        dom.cleanup();
      }
    }
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
