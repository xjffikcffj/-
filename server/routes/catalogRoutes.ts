import { Router } from 'express';
import type { RequestHandler } from 'express';
import type { InMemoryRateLimiter } from '../middleware/rateLimit.js';
import type { CatalogService } from '../services/catalogService.js';

export function createCatalogRouter(
  catalogService: CatalogService,
  optionalAuth: RequestHandler,
  rateLimiter?: InMemoryRateLimiter,
): Router {
  const router = Router();
  router.use(optionalAuth);
  if (rateLimiter) {
    router.use(rateLimiter.middleware('catalog', 'uid-or-ip'));
  }

  router.get('/branches', async (_req, res, next) => {
    try {
      const items = await catalogService.listPublishedBranches();
      res.status(200).json({ items });
    } catch (err) {
      next(err);
    }
  });

  router.get('/branches/:branchId/subjects', async (req, res, next) => {
    try {
      const items = await catalogService.listPublishedSubjects(
        req.params.branchId,
      );
      res.status(200).json({ branchId: req.params.branchId, items });
    } catch (err) {
      next(err);
    }
  });

  router.get('/subjects/:subjectId/sections', async (req, res, next) => {
    try {
      const items = await catalogService.listPublishedSections(
        req.params.subjectId,
      );
      res.status(200).json({ subjectId: req.params.subjectId, items });
    } catch (err) {
      next(err);
    }
  });

  router.get('/sections/:sectionId/study-files', async (req, res, next) => {
    try {
      const items = await catalogService.listPublishedStudyFiles(
        req.params.sectionId,
      );
      res.status(200).json({ sectionId: req.params.sectionId, items });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
