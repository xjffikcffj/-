import { PdfSearchStatus } from './models';

export interface PageViewportRect {
  pageNumber: number;
  top: number;
  bottom: number;
}

/**
 * Determines which 1-based page dominates the scroll viewport during wheel/touch scrolling.
 * Prioritizes highest visible overlap inside `[containerTop, containerBottom]`, breaking ties
 * by proximity to the top reading edge.
 */
export function determineMostVisiblePage(
  containerTop: number,
  containerBottom: number,
  pageRects: PageViewportRect[]
): number | null {
  if (pageRects.length === 0 || containerBottom <= containerTop) return null;

  const anchorTop = containerTop + 32;
  let bestPage: number | null = null;
  let bestVisibleHeight = -1;
  let bestAnchorDistance = Number.POSITIVE_INFINITY;

  for (const rect of pageRects) {
    const visibleTop = Math.max(containerTop, rect.top);
    const visibleBottom = Math.min(containerBottom, rect.bottom);
    const visibleHeight = Math.max(0, visibleBottom - visibleTop);
    if (visibleHeight <= 0) continue;

    const anchorDistance = Math.abs(rect.top - anchorTop);
    if (
      visibleHeight > bestVisibleHeight + 1 ||
      (Math.abs(visibleHeight - bestVisibleHeight) <= 1 && anchorDistance < bestAnchorDistance)
    ) {
      bestPage = rect.pageNumber;
      bestVisibleHeight = visibleHeight;
      bestAnchorDistance = anchorDistance;
    }
  }

  return bestPage;
}

export function hasReachedTargetPage(params: {
  containerTop: number;
  containerBottom: number;
  containerScrollTop: number;
  containerMaxScrollTop: number;
  targetRect: PageViewportRect | null;
  mostVisiblePage: number | null;
  targetPage: number;
}): boolean {
  const {
    containerTop,
    containerBottom,
    containerScrollTop,
    containerMaxScrollTop,
    targetRect,
    mostVisiblePage,
    targetPage,
  } = params;

  if (!targetRect || containerBottom <= containerTop) return false;

  const visibleHeight =
    Math.min(containerBottom, targetRect.bottom) - Math.max(containerTop, targetRect.top);
  if (visibleHeight <= 0) return false;

  // 1. Target page clearly dominates the scroll viewport.
  if (mostVisiblePage === targetPage) return true;

  // 2. Target page's top reading edge reached the top of the viewport.
  if (Math.abs(targetRect.top - containerTop) <= 48) {
    return true;
  }

  // 3. Reached top scroll bound for the first page.
  const atTopEdge = targetPage <= 1 && containerScrollTop <= 2;
  if (atTopEdge && targetRect.top >= containerTop - 2) {
    return true;
  }

  // 4. Reached bottom scroll bound for a trailing short page: only counts as arrived when
  //    the full target page fits cleanly inside the viewport (so scrolling up to an earlier
  //    partially-clipped page from maxScrollTop never triggers a false arrival).
  const atBottomEdge = containerMaxScrollTop - containerScrollTop <= 2;
  const targetFullyInView =
    targetRect.top >= containerTop - 2 && targetRect.bottom <= containerBottom + 2;

  return atBottomEdge && targetFullyInView;
}

export function isPureHorizontalScroll(params: {
  prevScrollTop: number;
  prevScrollLeft: number;
  nextScrollTop: number;
  nextScrollLeft: number;
}): boolean {
  return (
    Math.abs(params.nextScrollLeft - params.prevScrollLeft) > 0.5 &&
    Math.abs(params.nextScrollTop - params.prevScrollTop) <= 0.5
  );
}

export function resolveSearchStatusMessage(status: PdfSearchStatus): string | null {
  switch (status) {
    case 'NO_RESULTS':
      return 'لا توجد نتائج مطابقة للبحث في هذا الملف.';
    case 'NO_EXTRACTABLE_TEXT':
      return 'هذا الملف لا يحتوي على نص قابل للاستخراج (قد تكون الصفحات مصوّرة)، لذا لا يتوفر البحث النصي له حالياً.';
    case 'ERROR':
      return 'تعذّر إتمام البحث داخل هذا الملف.';
    default:
      return null;
  }
}
