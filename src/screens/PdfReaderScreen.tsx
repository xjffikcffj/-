import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowRight,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronUp,
  FileText,
  Moon,
  Search,
  Sun,
  X,
  ZoomIn,
  ZoomOut,
} from 'lucide-react';
import { PdfSearchResult, StudyFileMetadata } from '../domain/models';
import { getDocumentPages } from '../data/seedCatalog';
import {
  determineMostVisiblePage,
  hasReachedTargetPage,
  isPureHorizontalScroll,
  PageViewportRect,
  resolveSearchStatusMessage,
} from '../domain/pdfReaderHelpers';
import { normalizeArabicForSearch, searchArabicAcrossPages } from '../domain/validators';
import { useHaqibati } from '../state/HaqibatiContext';

const MIN_ZOOM = 0.75;
const MAX_ZOOM = 2.0;

export const PdfReaderScreen: React.FC<{
  file: StudyFileMetadata;
  onNavigateBack: () => void;
}> = ({ file, onNavigateBack }) => {
  const { readingProgressMap, saveReadingProgress } = useHaqibati();

  const pages = useMemo(() => (file.activeVersionId ? getDocumentPages(file) : []), [file]);
  const totalPages = pages.length;

  const computeSavedPageForFile = (targetFileId: string, pageCount: number): number => {
    const saved = readingProgressMap[targetFileId]?.lastPageNumber;
    return saved && saved >= 1 && saved <= pageCount ? saved : 1;
  };

  const initialPageOnMount = computeSavedPageForFile(file.id, totalPages);

  // Confirmed reached/visible page for this fileId (persisted to localStorage).
  const [confirmedPageState, setConfirmedPageState] = useState<{ fileId: string; page: number }>(
    () => ({
      fileId: file.id,
      page: initialPageOnMount,
    })
  );
  // Active requested programmatic jump target (never persisted until arrived).
  const [pendingJumpState, setPendingJumpState] = useState<{ fileId: string; page: number } | null>(
    null
  );
  const [resumedBanner, setResumedBanner] = useState<{ fileId: string; page: number | null }>(
    () => ({
      fileId: file.id,
      page: initialPageOnMount > 1 ? initialPageOnMount : null,
    })
  );

  const confirmedPage =
    confirmedPageState.fileId === file.id
      ? confirmedPageState.page
      : computeSavedPageForFile(file.id, totalPages);
  const pendingJumpPage =
    pendingJumpState?.fileId === file.id ? pendingJumpState.page : null;
  const currentPage = pendingJumpPage ?? confirmedPage;
  const latestDisplayPageRef = useRef<number>(currentPage);
  latestDisplayPageRef.current = currentPage;

  const resumedPage =
    resumedBanner.fileId === file.id
      ? resumedBanner.page
      : initialPageOnMount > 1
      ? initialPageOnMount
      : null;

  const setConfirmedPage = (updater: number | ((prev: number) => number)) => {
    setConfirmedPageState((prev) => {
      const base =
        prev.fileId === file.id ? prev.page : computeSavedPageForFile(file.id, totalPages);
      const next = typeof updater === 'function' ? updater(base) : updater;
      if (prev.fileId === file.id && prev.page === next) return prev;
      return { fileId: file.id, page: next };
    });
  };

  const [zoomScale, setZoomScale] = useState<number>(1.0);
  const [isNightMode, setIsNightMode] = useState<boolean>(false);
  const [areControlsVisible, setAreControlsVisible] = useState<boolean>(true);
  const [isSearchOpen, setIsSearchOpen] = useState<boolean>(false);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [searchResult, setSearchResult] = useState<PdfSearchResult>({
    query: '',
    status: 'IDLE',
    matches: [],
  });
  const [activeMatchIndex, setActiveMatchIndex] = useState<number>(0);

  const [isJumpDialogOpen, setIsJumpDialogOpen] = useState<boolean>(false);
  const [jumpInput, setJumpInput] = useState<string>(String(currentPage));
  const [jumpError, setJumpError] = useState<boolean>(false);

  const [stalledTargetState, setStalledTargetState] = useState<{
    fileId: string;
    page: number;
  } | null>(null);
  const stalledTargetPage =
    stalledTargetState?.fileId === file.id ? stalledTargetState.page : null;

  const mainScrollRef = useRef<HTMLElement | null>(null);
  const pageRefs = useRef<(HTMLDivElement | null)[]>([]);
  const transitionSeqRef = useRef<number>(0);
  const programmaticTransitionRef = useRef<{
    id: number;
    fileId: string;
    targetPage: number;
    startScrollTop: number;
    startScrollLeft: number;
    hasVerticalMovement: boolean;
    pendingHorizontalOnly: boolean;
    isInitialResume: boolean;
  } | null>(
    resumedPage
      ? {
          id: ++transitionSeqRef.current,
          fileId: file.id,
          targetPage: resumedPage,
          startScrollTop: 0,
          startScrollLeft: 0,
          hasVerticalMovement: false,
          pendingHorizontalOnly: false,
          isInitialResume: true,
        }
      : null
  );
  const pinnedShortPageRef = useRef<{
    fileId: string;
    pageNumber: number;
    scrollTopAtArrival: number;
    maxScrollTopAtArrival: number;
    clientHeightAtArrival: number;
    zoomScaleAtArrival: number;
  } | null>(null);
  const stalledTargetRef = useRef<{ fileId: string; page: number } | null>(null);
  const lastViewportPosRef = useRef<{ scrollTop: number; scrollLeft: number }>({
    scrollTop: 0,
    scrollLeft: 0,
  });
  const lastVerticalEvaluatedPosRef = useRef<{ scrollTop: number; scrollLeft: number }>({
    scrollTop: 0,
    scrollLeft: 0,
  });
  const zoomScaleRef = useRef<number>(zoomScale);
  zoomScaleRef.current = zoomScale;
  const scrollIdleTimeoutRef = useRef<number | null>(null);
  const rafScrollSyncRef = useRef<number | null>(null);
  const jumpCheckRafRef = useRef<number | null>(null);
  const mountedFileIdRef = useRef<string>(file.id);

  const updateStalledTarget = (next: { fileId: string; page: number } | null) => {
    stalledTargetRef.current = next;
    setStalledTargetState(next);
  };

  const clearProgrammaticIdleTimeout = () => {
    if (scrollIdleTimeoutRef.current !== null) {
      window.clearTimeout(scrollIdleTimeoutRef.current);
      scrollIdleTimeoutRef.current = null;
    }
  };

  const cancelPendingJumpRaf = () => {
    if (jumpCheckRafRef.current !== null) {
      window.cancelAnimationFrame(jumpCheckRafRef.current);
      jumpCheckRafRef.current = null;
    }
  };

  const evaluateViewportSync = (): { arrived: boolean; visiblePage: number | null } => {
    const container = mainScrollRef.current;
    if (!container || pages.length === 0) return { arrived: false, visiblePage: null };

    const containerRect = container.getBoundingClientRect();
    const currentMaxScrollTop = Math.max(0, container.scrollHeight - container.clientHeight);
    const pageRects: PageViewportRect[] = [];
    let targetRect: PageViewportRect | null = null;
    const activeTransition =
      programmaticTransitionRef.current?.fileId === file.id
        ? programmaticTransitionRef.current
        : null;

    for (let i = 0; i < pages.length; i++) {
      const el = pageRefs.current[i];
      if (!el) continue;
      const rect = el.getBoundingClientRect();
      const item: PageViewportRect = {
        pageNumber: pages[i].pageNumber,
        top: rect.top,
        bottom: rect.bottom,
      };
      pageRects.push(item);
      if (activeTransition && item.pageNumber === activeTransition.targetPage) {
        targetRect = item;
      }
    }

    const visiblePage = determineMostVisiblePage(
      containerRect.top,
      containerRect.bottom,
      pageRects
    );

    if (activeTransition) {
      const arrived = hasReachedTargetPage({
        containerTop: containerRect.top,
        containerBottom: containerRect.bottom,
        containerScrollTop: container.scrollTop,
        containerMaxScrollTop: currentMaxScrollTop,
        targetRect,
        mostVisiblePage: visiblePage,
        targetPage: activeTransition.targetPage,
      });

      if (!arrived) {
        return { arrived: false, visiblePage };
      }

      const targetPage = activeTransition.targetPage;
      programmaticTransitionRef.current = null;
      clearProgrammaticIdleTimeout();
      cancelPendingJumpRaf();
      setPendingJumpState(null);
      updateStalledTarget(null);
      lastVerticalEvaluatedPosRef.current = {
        scrollTop: container.scrollTop,
        scrollLeft: container.scrollLeft ?? 0,
      };

      const targetAtScrollBound =
        targetPage === totalPages && currentMaxScrollTop - container.scrollTop <= 24;
      if ((visiblePage !== null && visiblePage !== targetPage) || targetAtScrollBound) {
        // Keep the jumped/resumed last page active at the bottom scroll bound (even if taller
        // zoomed-in height temporarily made visiblePage === targetPage before a subsequent zoom-out).
        pinnedShortPageRef.current = {
          fileId: file.id,
          pageNumber: targetPage,
          scrollTopAtArrival: container.scrollTop,
          maxScrollTopAtArrival: currentMaxScrollTop,
          clientHeightAtArrival: container.clientHeight,
          zoomScaleAtArrival: zoomScaleRef.current,
        };
        setConfirmedPage(targetPage);
      } else {
        pinnedShortPageRef.current = null;
        if (visiblePage !== null) {
          setConfirmedPage(visiblePage);
        }
      }
      return { arrived: true, visiblePage };
    }

    const pinned =
      pinnedShortPageRef.current?.fileId === file.id ? pinnedShortPageRef.current : null;
    let justReleasedPin = false;
    if (pinned) {
      const deltaFromArrival = Math.abs(container.scrollTop - pinned.scrollTopAtArrival);
      const contentOrZoomChanged =
        Math.abs(currentMaxScrollTop - pinned.maxScrollTopAtArrival) > 1 ||
        Math.abs(container.clientHeight - pinned.clientHeightAtArrival) > 1 ||
        Math.abs(zoomScaleRef.current - pinned.zoomScaleAtArrival) > 0.01;
      const stillAtBottomEdge = currentMaxScrollTop - container.scrollTop <= 24;
      const targetPageStillInView = pageRects.some(
        (r) =>
          r.pageNumber === pinned.pageNumber &&
          Math.min(containerRect.bottom, r.bottom) > Math.max(containerRect.top, r.top)
      );

      if (
        targetPageStillInView &&
        (deltaFromArrival <= 24 ||
          (contentOrZoomChanged &&
            stillAtBottomEdge &&
            container.scrollTop <= pinned.scrollTopAtArrival + 24))
      ) {
        if (contentOrZoomChanged) {
          // Only re-anchor when viewport geometry (zoom/resize/toolbar height) changed,
          // so small manual upward steps cannot creep the pin away from the bottom bound.
          pinned.scrollTopAtArrival = container.scrollTop;
          pinned.maxScrollTopAtArrival = currentMaxScrollTop;
          pinned.clientHeightAtArrival = container.clientHeight;
          pinned.zoomScaleAtArrival = zoomScaleRef.current;
        }
        lastVerticalEvaluatedPosRef.current = {
          scrollTop: container.scrollTop,
          scrollLeft: container.scrollLeft ?? 0,
        };
        return { arrived: true, visiblePage: pinned.pageNumber };
      }
      pinnedShortPageRef.current = null;
      justReleasedPin = true;
    }

    const activeStall =
      stalledTargetRef.current?.fileId === file.id ? stalledTargetRef.current : null;
    if (activeStall !== null) {
      return { arrived: false, visiblePage };
    }

    const prevEvaluatedTop = lastVerticalEvaluatedPosRef.current.scrollTop;
    lastVerticalEvaluatedPosRef.current = {
      scrollTop: container.scrollTop,
      scrollLeft: container.scrollLeft ?? 0,
    };

    const lastPageRect = pageRects.find((r) => r.pageNumber === totalPages) ?? null;
    const atBottomEdgeNow =
      currentMaxScrollTop > 0 && currentMaxScrollTop - container.scrollTop <= 24;
    const scrollingDownOrSettled = container.scrollTop >= prevEvaluatedTop - 1;
    const lastPageInView =
      lastPageRect !== null &&
      Math.min(containerRect.bottom, lastPageRect.bottom) >
        Math.max(containerRect.top, lastPageRect.top);
    const lastPageReachedFully =
      lastPageRect !== null &&
      hasReachedTargetPage({
        containerTop: containerRect.top,
        containerBottom: containerRect.bottom,
        containerScrollTop: container.scrollTop,
        containerMaxScrollTop: currentMaxScrollTop,
        targetRect: lastPageRect,
        mostVisiblePage: visiblePage,
        targetPage: totalPages,
      });

    if (
      !justReleasedPin &&
      totalPages > 0 &&
      atBottomEdgeNow &&
      scrollingDownOrSettled &&
      lastPageInView &&
      (visiblePage === totalPages || lastPageReachedFully)
    ) {
      pinnedShortPageRef.current = {
        fileId: file.id,
        pageNumber: totalPages,
        scrollTopAtArrival: container.scrollTop,
        maxScrollTopAtArrival: currentMaxScrollTop,
        clientHeightAtArrival: container.clientHeight,
        zoomScaleAtArrival: zoomScaleRef.current,
      };
      setConfirmedPage(totalPages);
      return { arrived: true, visiblePage: totalPages };
    }

    if (visiblePage !== null) {
      setConfirmedPage(visiblePage);
    }
    return { arrived: true, visiblePage };
  };

  const evaluateViewportSyncRef = useRef(evaluateViewportSync);
  evaluateViewportSyncRef.current = evaluateViewportSync;

  const failActiveTransition = (
    transition: NonNullable<typeof programmaticTransitionRef.current>,
    visiblePage: number | null
  ) => {
    programmaticTransitionRef.current = null;
    clearProgrammaticIdleTimeout();
    cancelPendingJumpRaf();
    setPendingJumpState(null);
    updateStalledTarget({ fileId: transition.fileId, page: transition.targetPage });
    // Never write an unreached jump target into confirmed progress: sync to actual visible page
    // (for initial auto-resume stall, keep previously stored page until user cancels or scrolls).
    if (!transition.isInitialResume && visiblePage !== null) {
      setConfirmedPage(visiblePage);
    }
  };

  const armProgrammaticStallFallback = (transitionId: number, expectedFileId: string) => {
    clearProgrammaticIdleTimeout();
    scrollIdleTimeoutRef.current = window.setTimeout(() => {
      scrollIdleTimeoutRef.current = null;
      const transition = programmaticTransitionRef.current;
      if (
        !transition ||
        transition.id !== transitionId ||
        transition.fileId !== expectedFileId
      ) {
        return;
      }

      const { arrived, visiblePage } = evaluateViewportSyncRef.current();
      if (!arrived) {
        failActiveTransition(transition, visiblePage);
      }
    }, 2500);
  };

  const handleScrollEnd = () => {
    const container = mainScrollRef.current;
    if (!container) return;

    const currentTop = container.scrollTop;
    const currentLeft = container.scrollLeft ?? 0;
    const axisFromDelta = isPureHorizontalScroll({
      prevScrollTop: lastViewportPosRef.current.scrollTop,
      prevScrollLeft: lastViewportPosRef.current.scrollLeft,
      nextScrollTop: currentTop,
      nextScrollLeft: currentLeft,
    });
    lastViewportPosRef.current = { scrollTop: currentTop, scrollLeft: currentLeft };

    const transition =
      programmaticTransitionRef.current?.fileId === file.id
        ? programmaticTransitionRef.current
        : null;

    if (transition) {
      // Check actual vertical arrival first so an interleaved horizontal pan never strands a
      // completed vertical transition until the 2500ms fallback.
      const { arrived, visiblePage } = evaluateViewportSyncRef.current();
      if (arrived) {
        return;
      }

      if (axisFromDelta || transition.pendingHorizontalOnly) {
        transition.pendingHorizontalOnly = false;
        return;
      }

      const verticalMovedFromStart =
        transition.hasVerticalMovement || Math.abs(currentTop - transition.startScrollTop) > 1;
      if (!verticalMovedFromStart) {
        // Delayed scrollend from an earlier jump before this transition moved vertically: keep lock.
        return;
      }

      failActiveTransition(transition, visiblePage);
      return;
    }

    // No active transition: ignore free horizontal-only scrollend when vertical position is unchanged.
    const unchangedVerticallyFromSync =
      Math.abs(currentTop - lastVerticalEvaluatedPosRef.current.scrollTop) <= 0.5 &&
      Math.abs(currentLeft - lastVerticalEvaluatedPosRef.current.scrollLeft) > 0.5;
    if (axisFromDelta || unchangedVerticallyFromSync) {
      lastVerticalEvaluatedPosRef.current = {
        scrollTop: lastVerticalEvaluatedPosRef.current.scrollTop,
        scrollLeft: currentLeft,
      };
      return;
    }

    evaluateViewportSyncRef.current();
  };

  const handleScrollEndRef = useRef(handleScrollEnd);
  handleScrollEndRef.current = handleScrollEnd;

  // Initialize / reset reader state on mount and whenever file.id changes (even without remount).
  useEffect(() => {
    const isFileSwitch = mountedFileIdRef.current !== file.id;
    mountedFileIdRef.current = file.id;

    let initialCheckRaf: number | null = null;
    clearProgrammaticIdleTimeout();
    cancelPendingJumpRaf();
    if (rafScrollSyncRef.current !== null) {
      window.cancelAnimationFrame(rafScrollSyncRef.current);
      rafScrollSyncRef.current = null;
    }

    pinnedShortPageRef.current = null;
    setPendingJumpState(null);
    updateStalledTarget(null);

    if (isFileSwitch) {
      setIsSearchOpen(false);
      setSearchQuery('');
      setSearchResult({ query: '', status: 'IDLE', matches: [] });
      setActiveMatchIndex(0);
      setIsJumpDialogOpen(false);
    }

    const nextInitialPage = computeSavedPageForFile(file.id, totalPages);
    const nextResumed = nextInitialPage > 1 ? nextInitialPage : null;
    setConfirmedPageState({ fileId: file.id, page: nextInitialPage });
    setResumedBanner({ fileId: file.id, page: nextResumed });

    const container = mainScrollRef.current;
    if (nextResumed && pageRefs.current[nextResumed - 1]) {
      const transitionId = ++transitionSeqRef.current;
      programmaticTransitionRef.current = {
        id: transitionId,
        fileId: file.id,
        targetPage: nextResumed,
        startScrollTop: container?.scrollTop ?? 0,
        startScrollLeft: container?.scrollLeft ?? 0,
        hasVerticalMovement: false,
        pendingHorizontalOnly: false,
        isInitialResume: true,
      };
      pageRefs.current[nextResumed - 1]?.scrollIntoView({ behavior: 'auto', block: 'start' });
      const pos = {
        scrollTop: container?.scrollTop ?? 0,
        scrollLeft: container?.scrollLeft ?? 0,
      };
      lastViewportPosRef.current = pos;
      lastVerticalEvaluatedPosRef.current = pos;
      initialCheckRaf = window.requestAnimationFrame(() => {
        evaluateViewportSyncRef.current();
      });
      armProgrammaticStallFallback(transitionId, file.id);
    } else {
      programmaticTransitionRef.current = null;
      if (container && isFileSwitch) {
        container.scrollTop = 0;
        container.scrollLeft = 0;
      }
      const pos = {
        scrollTop: container?.scrollTop ?? 0,
        scrollLeft: container?.scrollLeft ?? 0,
      };
      lastViewportPosRef.current = pos;
      lastVerticalEvaluatedPosRef.current = pos;
    }

    return () => {
      if (initialCheckRaf !== null) {
        window.cancelAnimationFrame(initialCheckRaf);
      }
      clearProgrammaticIdleTimeout();
      cancelPendingJumpRaf();
      if (rafScrollSyncRef.current !== null) {
        window.cancelAnimationFrame(rafScrollSyncRef.current);
        rafScrollSyncRef.current = null;
      }
      if (programmaticTransitionRef.current?.fileId === file.id) {
        programmaticTransitionRef.current = null;
      }
      if (pinnedShortPageRef.current?.fileId === file.id) {
        pinnedShortPageRef.current = null;
      }
    };
  }, [file.id, totalPages]);

  // Re-anchor pinned short last page cleanly if zoomScale or toolbar visibility (container height) changes.
  useEffect(() => {
    if (pinnedShortPageRef.current?.fileId === file.id) {
      evaluateViewportSyncRef.current();
    }
  }, [file.id, zoomScale, areControlsVisible]);

  // Track natural wheel/touch scrolling via IntersectionObserver and scrollend on the same <main> container
  useEffect(() => {
    const container = mainScrollRef.current;
    if (!container || pages.length === 0) return;

    const onScrollEndEvent = () => {
      handleScrollEndRef.current();
    };
    const onResizeEvent = () => {
      if (pinnedShortPageRef.current?.fileId === file.id) {
        evaluateViewportSyncRef.current();
      }
    };

    container.addEventListener('scrollend', onScrollEndEvent);
    if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
      window.addEventListener('resize', onResizeEvent);
    }

    const resizeObserver =
      typeof ResizeObserver !== 'undefined'
        ? new ResizeObserver(() => {
            onResizeEvent();
          })
        : null;
    resizeObserver?.observe(container);

    const observer =
      typeof IntersectionObserver !== 'undefined'
        ? new IntersectionObserver(
            () => {
              evaluateViewportSyncRef.current();
            },
            {
              root: container,
              threshold: [0.1, 0.25, 0.5, 0.75],
            }
          )
        : null;

    if (observer) {
      pageRefs.current.forEach((el) => {
        if (el) observer.observe(el);
      });
    }

    return () => {
      container.removeEventListener('scrollend', onScrollEndEvent);
      if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
        window.removeEventListener('resize', onResizeEvent);
      }
      resizeObserver?.disconnect();
      observer?.disconnect();
    };
  }, [file.id, pages.length]);

  // Persist local device reading progress strictly from confirmed reached page (never unreached jump targets).
  useEffect(() => {
    if (
      confirmedPageState.fileId === file.id &&
      totalPages > 0 &&
      confirmedPageState.page >= 1 &&
      confirmedPageState.page <= totalPages
    ) {
      saveReadingProgress(file.id, confirmedPageState.page, totalPages);
    }
  }, [file.id, confirmedPageState.fileId, confirmedPageState.page, totalPages]);

  const scrollToPage = (pageNum: number) => {
    if (totalPages <= 0) return;
    const clamped = Math.max(1, Math.min(totalPages, pageNum));
    const container = mainScrollRef.current;
    pinnedShortPageRef.current = null;
    updateStalledTarget(null);
    cancelPendingJumpRaf();
    const transitionId = ++transitionSeqRef.current;
    programmaticTransitionRef.current = {
      id: transitionId,
      fileId: file.id,
      targetPage: clamped,
      startScrollTop: container?.scrollTop ?? 0,
      startScrollLeft: container?.scrollLeft ?? 0,
      hasVerticalMovement: false,
      pendingHorizontalOnly: false,
      isInitialResume: false,
    };
    latestDisplayPageRef.current = clamped;
    setPendingJumpState({ fileId: file.id, page: clamped });
    pageRefs.current[clamped - 1]?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    jumpCheckRafRef.current = window.requestAnimationFrame(() => {
      jumpCheckRafRef.current = null;
      if (programmaticTransitionRef.current?.id === transitionId) {
        evaluateViewportSyncRef.current();
      }
    });
    armProgrammaticStallFallback(transitionId, file.id);
  };

  const handleStepPage = (delta: number) => {
    scrollToPage(latestDisplayPageRef.current + delta);
  };

  const handleCancelStalledJump = () => {
    updateStalledTarget(null);
    setPendingJumpState(null);
    latestDisplayPageRef.current = confirmedPage;
    programmaticTransitionRef.current = null;
    pinnedShortPageRef.current = null;
    cancelPendingJumpRaf();
    evaluateViewportSyncRef.current();
  };

  const handleUserWheelIntent = (e: React.WheelEvent<HTMLElement>) => {
    // Ignore horizontal-only trackpad panning while zoomed so it cannot abort a vertical jump.
    if (Math.abs(e.deltaY) <= 0.5 && Math.abs(e.deltaX) > 0.5) {
      return;
    }
    if (programmaticTransitionRef.current?.fileId === file.id) {
      programmaticTransitionRef.current = null;
      clearProgrammaticIdleTimeout();
      cancelPendingJumpRaf();
      setPendingJumpState(null);
    }
    if (stalledTargetRef.current?.fileId === file.id) {
      updateStalledTarget(null);
    }
  };

  const handleViewportScroll = () => {
    const container = mainScrollRef.current;
    if (!container) return;

    const nextTop = container.scrollTop;
    const nextLeft = container.scrollLeft ?? 0;
    const prevTop = lastViewportPosRef.current.scrollTop;
    const prevLeft = lastViewportPosRef.current.scrollLeft;
    const deltaTop = Math.abs(nextTop - prevTop);
    const pureHorizontal = isPureHorizontalScroll({
      prevScrollTop: prevTop,
      prevScrollLeft: prevLeft,
      nextScrollTop: nextTop,
      nextScrollLeft: nextLeft,
    });

    lastViewportPosRef.current = { scrollTop: nextTop, scrollLeft: nextLeft };

    const transition =
      programmaticTransitionRef.current?.fileId === file.id
        ? programmaticTransitionRef.current
        : null;

    if (pureHorizontal) {
      if (transition) {
        transition.pendingHorizontalOnly = true;
      }
      return;
    }

    if (transition) {
      if (deltaTop > 0.5) {
        transition.pendingHorizontalOnly = false;
      }
      if (Math.abs(nextTop - transition.startScrollTop) > 1) {
        transition.hasVerticalMovement = true;
      }
      // Keep stall fallback pushed out while smooth scrolling is actively progressing vertically.
      armProgrammaticStallFallback(transition.id, file.id);
    } else if (stalledTargetRef.current?.fileId === file.id) {
      updateStalledTarget(null);
    }

    if (rafScrollSyncRef.current !== null) return;
    rafScrollSyncRef.current = window.requestAnimationFrame(() => {
      rafScrollSyncRef.current = null;
      evaluateViewportSyncRef.current();
    });
  };

  const handleExecuteSearch = (e?: React.FormEvent) => {
    e?.preventDefault();
    try {
      const pageStrings = pages.map((p) =>
        [p.heading, ...p.paragraphs, p.keyTakeaway || ''].join(' ')
      );
      const result = searchArabicAcrossPages(searchQuery, pageStrings);
      setSearchResult(result);
      setActiveMatchIndex(0);
      if (result.status === 'FOUND' && result.matches.length > 0) {
        scrollToPage(result.matches[0].pageNumber);
      }
    } catch {
      setSearchResult({
        query: searchQuery.trim(),
        status: 'ERROR',
        matches: [],
      });
      setActiveMatchIndex(0);
    }
  };

  const handleClearSearch = () => {
    setSearchQuery('');
    setSearchResult({ query: '', status: 'IDLE', matches: [] });
    setActiveMatchIndex(0);
  };

  const stepMatch = (delta: number) => {
    if (searchResult.matches.length === 0) return;
    const next =
      (activeMatchIndex + delta + searchResult.matches.length) % searchResult.matches.length;
    setActiveMatchIndex(next);
    scrollToPage(searchResult.matches[next].pageNumber);
  };

  // Error view when no published version is attached yet
  if (!file.activeVersionId) {
    return (
      <div className="flex min-h-screen flex-col bg-[var(--bg)]">
        <header className="flex h-14 items-center gap-2 border-b border-[var(--outline-variant)] bg-[var(--surface)] px-2">
          <button
            type="button"
            onClick={onNavigateBack}
            className="flex h-10 w-10 items-center justify-center rounded-full hover:bg-[var(--surface-variant)]"
          >
            <ArrowRight className="h-5 w-5" />
          </button>
          <h1 className="truncate text-base font-semibold">{file.title}</h1>
        </header>
        <div className="flex flex-1 items-center justify-center p-6">
          <div className="w-full max-w-sm rounded-2xl border border-[var(--outline-variant)] bg-[var(--surface)] p-6 text-center shadow-xs">
            <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-[var(--error-container)] text-[var(--on-error-container)]">
              <FileText className="h-8 w-8" />
            </div>
            <h2 className="text-lg font-bold text-[var(--on-surface)]">تعذّر فتح ملف PDF</h2>
            <p className="mt-2 text-sm text-[var(--on-surface-variant)]">
              لم يُرفع أو يُنشر إصدار PDF فعلي لهذا الملف بعد.
            </p>
            <button
              type="button"
              onClick={onNavigateBack}
              className="mt-5 w-full rounded-xl bg-[var(--primary)] px-4 py-2.5 text-sm font-semibold text-[var(--on-primary)]"
            >
              رجوع
            </button>
          </div>
        </div>
      </div>
    );
  }

  const normalizedQuery = normalizeArabicForSearch(searchQuery);
  const activeMatch = searchResult.matches[activeMatchIndex];

  return (
    <div
      className={`flex h-dvh flex-col overflow-hidden transition-colors ${
        isNightMode ? 'bg-[#111317] text-[#E7EAEF]' : 'bg-[var(--surface-variant)]/60 text-[var(--on-surface)]'
      }`}
    >
      {/* Top Bar */}
      {areControlsVisible && (
        <header
          className={`relative z-30 shrink-0 border-b ${
            isNightMode
              ? 'border-[#2E3440] bg-[#1C2026] text-[#E7EAEF]'
              : 'border-[var(--outline-variant)] bg-[var(--surface)] text-[var(--on-surface)]'
          }`}
        >
          <div className="flex h-14 items-center gap-1 px-2">
            <button
              type="button"
              onClick={onNavigateBack}
              aria-label="رجوع"
              className="flex h-10 w-10 items-center justify-center rounded-full hover:bg-black/5 dark:hover:bg-white/10"
            >
              <ArrowRight className="h-5 w-5" />
            </button>
            <h1 className="min-w-0 flex-1 truncate text-base font-semibold">{file.title}</h1>
            <button
              type="button"
              onClick={() => setIsSearchOpen((v) => !v)}
              aria-label="بحث داخل الملف"
              className="flex h-10 w-10 items-center justify-center rounded-full hover:bg-black/5 dark:hover:bg-white/10"
            >
              <Search className="h-5 w-5" />
            </button>
            <button
              type="button"
              onClick={() => setIsNightMode((v) => !v)}
              aria-label={isNightMode ? 'الوضع النهاري للقراءة' : 'الوضع الليلي للقراءة'}
              className="flex h-10 w-10 items-center justify-center rounded-full hover:bg-black/5 dark:hover:bg-white/10"
            >
              {isNightMode ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
            </button>
          </div>

          {isSearchOpen && (
            <div className="border-t border-black/10 px-3 py-2.5 dark:border-white/10">
              <form onSubmit={handleExecuteSearch} className="flex items-center gap-2">
                <div className="relative flex-1">
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="ابحث عن كلمة أو عبارة…"
                    className={`w-full rounded-xl border px-3 py-2 pl-8 text-sm focus:outline-none ${
                      isNightMode
                        ? 'border-[#3B4252] bg-[#111317] text-white'
                        : 'border-[var(--outline)] bg-[var(--bg)] text-[var(--on-surface)]'
                    }`}
                  />
                  {searchQuery && (
                    <button
                      type="button"
                      onClick={handleClearSearch}
                      className="absolute inset-y-0 left-2 flex items-center text-current opacity-60"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  )}
                </div>
                <button
                  type="submit"
                  disabled={!searchQuery.trim()}
                  className="rounded-xl bg-[var(--primary)] px-4 py-2 text-sm font-semibold text-[var(--on-primary)] disabled:opacity-40"
                >
                  بحث
                </button>
              </form>

              {searchResult.status === 'FOUND' && (
                <div className="mt-2 flex items-center justify-between text-xs">
                  <span className="font-medium">
                    {activeMatchIndex + 1} من {searchResult.matches.length} (صفحة{' '}
                    {activeMatch?.pageNumber})
                  </span>
                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      onClick={() => stepMatch(-1)}
                      className="rounded-lg p-1 hover:bg-black/10"
                    >
                      <ChevronUp className="h-4 w-4" />
                    </button>
                    <button
                      type="button"
                      onClick={() => stepMatch(1)}
                      className="rounded-lg p-1 hover:bg-black/10"
                    >
                      <ChevronDown className="h-4 w-4" />
                    </button>
                  </div>
                </div>
              )}

              {(() => {
                const statusMessage = resolveSearchStatusMessage(searchResult.status);
                if (!statusMessage) return null;
                const isError = searchResult.status === 'ERROR';
                return (
                  <p
                    className={`mt-1.5 text-xs ${
                      isError
                        ? 'font-medium text-[var(--error)]'
                        : 'text-[var(--on-surface-variant)]'
                    }`}
                  >
                    {statusMessage}
                  </p>
                );
              })()}
            </div>
          )}
        </header>
      )}

      {/* Resumed Progress Banner */}
      {resumedPage && areControlsVisible && (
        <div className="shrink-0 bg-[var(--secondary-container)] px-4 py-1.5 text-xs font-medium text-[var(--on-secondary-container)]">
          تمت العودة إلى آخر صفحة قرأتها ({resumedPage}) • يُحفظ تقدم القراءة محلياً على هذا الجهاز فقط
        </div>
      )}

      {/* Stalled Jump Recovery Banner */}
      {stalledTargetPage !== null && areControlsVisible && (
        <div className="flex shrink-0 items-center justify-between gap-2 border-b border-[var(--outline-variant)] bg-[var(--surface)] px-4 py-2 text-xs text-[var(--on-surface)]">
          <span>لم يكتمل التمرير التلقائي إلى صفحة {stalledTargetPage}.</span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => scrollToPage(stalledTargetPage)}
              className="rounded-lg bg-[var(--primary)] px-2.5 py-1 font-semibold text-[var(--on-primary)]"
            >
              متابعة الانتقال
            </button>
            <button
              type="button"
              onClick={handleCancelStalledJump}
              className="rounded-lg border border-[var(--outline)] px-2.5 py-1 font-semibold text-[var(--on-surface-variant)]"
            >
              إلغاء
            </button>
          </div>
        </div>
      )}

      {/* Pages Viewport */}
      <main
        ref={mainScrollRef}
        onScroll={handleViewportScroll}
        onWheel={handleUserWheelIntent}
        onClick={() => setAreControlsVisible((v) => !v)}
        className="min-h-0 flex-1 overflow-y-auto overflow-x-auto px-3 py-4 sm:px-6"
      >
        <div
          className="mx-auto flex flex-col gap-5 transition-all"
          style={{
            maxWidth: `${Math.round(680 * zoomScale)}px`,
          }}
        >
          {pages.map((page, index) => {
            const isMatchedPage =
              searchResult.status === 'FOUND' && activeMatch?.pageNumber === page.pageNumber;

            return (
              <div
                key={page.pageNumber}
                data-page-number={page.pageNumber}
                ref={(el) => {
                  pageRefs.current[index] = el;
                }}
                onClick={(e) => {
                  e.stopPropagation();
                  setAreControlsVisible((v) => !v);
                }}
                className={`rounded-xl border p-6 shadow-md transition-colors sm:p-8 ${
                  isNightMode
                    ? 'border-[#2E3440] bg-[#1A1D22] text-[#E4E4E4]'
                    : 'border-[var(--outline-variant)] bg-white text-[#1A2321]'
                } ${isMatchedPage ? 'ring-2 ring-[var(--primary)]' : ''}`}
                style={{ fontSize: `${Math.round(15 * zoomScale)}px` }}
              >
                <div className="mb-4 flex items-center justify-between border-b border-current/10 pb-2 text-xs opacity-60">
                  <span>{file.title}</span>
                  <span>صفحة {page.pageNumber}</span>
                </div>

                <h2
                  className={`mb-4 font-bold leading-snug ${
                    isNightMode ? 'text-[#5CD4BE]' : 'text-[#0E5A4F]'
                  }`}
                  style={{ fontSize: `${Math.round(19 * zoomScale)}px` }}
                >
                  {page.heading}
                </h2>

                <div className="space-y-4 leading-loose">
                  {page.paragraphs.map((para, pIdx) => {
                    const hasHit =
                      normalizedQuery &&
                      normalizeArabicForSearch(para).includes(normalizedQuery);
                    return (
                      <p
                        key={pIdx}
                        className={
                          hasHit
                            ? isNightMode
                              ? 'rounded-lg bg-[#0D473E]/50 p-2'
                              : 'rounded-lg bg-[#FCEFD2]/80 p-2'
                            : ''
                        }
                      >
                        {para}
                      </p>
                    );
                  })}
                </div>

                {page.keyTakeaway && (
                  <div
                    className={`mt-6 rounded-xl border-r-4 p-3.5 text-sm leading-relaxed ${
                      isNightMode
                        ? 'border-[#5CD4BE] bg-[#0D473E]/30 text-[#D2EFE9]'
                        : 'border-[#0E5A4F] bg-[#D2EFE9]/50 text-[#052E28]'
                    }`}
                  >
                    <strong className="block text-xs opacity-80">خلاصة مهمة:</strong>
                    {page.keyTakeaway}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </main>

      {/* Bottom Toolbar */}
      {areControlsVisible && (
        <footer
          className={`relative z-30 shrink-0 border-t px-3 py-2 ${
            isNightMode
              ? 'border-[#2E3440] bg-[#1C2026] text-[#E7EAEF]'
              : 'border-[var(--outline-variant)] bg-[var(--surface)] text-[var(--on-surface)]'
          }`}
        >
          <div className="mx-auto flex max-w-xl items-center justify-between gap-2">
            {/* Page Navigation */}
            <div className="flex items-center gap-1">
              <button
                type="button"
                disabled={currentPage <= 1}
                onClick={() => handleStepPage(-1)}
                aria-label="الصفحة السابقة"
                className="flex h-9 w-9 items-center justify-center rounded-lg hover:bg-black/5 disabled:opacity-30"
              >
                <ChevronRight className="h-5 w-5" />
              </button>

              <button
                type="button"
                onClick={() => {
                  setJumpInput(String(currentPage));
                  setJumpError(false);
                  setIsJumpDialogOpen(true);
                }}
                className="rounded-lg px-2.5 py-1.5 text-xs font-semibold hover:bg-black/5 sm:text-sm"
              >
                صفحة {currentPage} من {totalPages}
              </button>

              <button
                type="button"
                disabled={currentPage >= totalPages}
                onClick={() => handleStepPage(1)}
                aria-label="الصفحة التالية"
                className="flex h-9 w-9 items-center justify-center rounded-lg hover:bg-black/5 disabled:opacity-30"
              >
                <ChevronLeft className="h-5 w-5" />
              </button>
            </div>

            {/* Zoom Controls */}
            <div className="flex items-center gap-1">
              <button
                type="button"
                disabled={zoomScale <= MIN_ZOOM}
                onClick={() => setZoomScale((z) => Math.max(MIN_ZOOM, +(z - 0.15).toFixed(2)))}
                aria-label="تصغير"
                className="flex h-9 w-9 items-center justify-center rounded-lg hover:bg-black/5 disabled:opacity-30"
              >
                <ZoomOut className="h-4 w-4" />
              </button>
              <button
                type="button"
                onClick={() => setZoomScale(1.0)}
                className="rounded-lg px-2 py-1 text-xs font-semibold hover:bg-black/5"
              >
                {Math.round(zoomScale * 100)}%
              </button>
              <button
                type="button"
                disabled={zoomScale >= MAX_ZOOM}
                onClick={() => setZoomScale((z) => Math.min(MAX_ZOOM, +(z + 0.15).toFixed(2)))}
                aria-label="تكبير"
                className="flex h-9 w-9 items-center justify-center rounded-lg hover:bg-black/5 disabled:opacity-30"
              >
                <ZoomIn className="h-4 w-4" />
              </button>
            </div>
          </div>
        </footer>
      )}

      {/* Page Jump Dialog */}
      {isJumpDialogOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-xs rounded-2xl bg-[var(--surface)] p-5 text-[var(--on-surface)] shadow-xl">
            <h3 className="text-base font-bold">الانتقال إلى صفحة محددة</h3>
            <p className="mt-1 text-xs text-[var(--on-surface-variant)]">
              أدخل رقم الصفحة (1 إلى {totalPages})
            </p>
            <input
              type="number"
              min={1}
              max={totalPages}
              value={jumpInput}
              onChange={(e) => {
                setJumpInput(e.target.value);
                setJumpError(false);
              }}
              className="mt-3 w-full rounded-xl border border-[var(--outline)] bg-[var(--bg)] px-3 py-2 text-sm"
            />
            {jumpError && (
              <p className="mt-1.5 text-xs text-[var(--error)]">
                أدخل رقماً صحيحاً بين 1 و {totalPages}.
              </p>
            )}
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setIsJumpDialogOpen(false)}
                className="rounded-xl px-3.5 py-2 text-xs font-semibold text-[var(--on-surface-variant)]"
              >
                إلغاء
              </button>
              <button
                type="button"
                onClick={() => {
                  const parsed = Number(jumpInput);
                  if (Number.isInteger(parsed) && parsed >= 1 && parsed <= totalPages) {
                    setIsJumpDialogOpen(false);
                    scrollToPage(parsed);
                  } else {
                    setJumpError(true);
                  }
                }}
                className="rounded-xl bg-[var(--primary)] px-4 py-2 text-xs font-semibold text-[var(--on-primary)]"
              >
                انتقال
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
