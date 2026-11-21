import { Router } from 'express';
import type { RequestHandler } from 'express';
import type { AuthenticatedRequest } from '../middleware/auth.js';
import type { InMemoryRateLimiter } from '../middleware/rateLimit.js';
import type { CatalogService } from '../services/catalogService.js';
import type { UploadVerificationService } from '../services/uploadVerificationService.js';
import {
  assertPlainObject,
  rejectForbiddenFields,
  rejectUnexpectedKeys,
} from '../validation/validators.js';

export function createAdminRouter(params: {
  catalogService: CatalogService;
  uploadVerificationService: UploadVerificationService;
  requireAdminOrOwner: RequestHandler;
  rateLimiter?: InMemoryRateLimiter;
}): Router {
  const router = Router();
  const limiter = params.rateLimiter;
  const adminReadLimit = limiter
    ? [limiter.middleware('admin-read', 'uid-or-ip')]
    : [];
  const adminWriteLimit = limiter
    ? [limiter.middleware('admin-version-write', 'uid-or-ip')]
    : [];
  const uploadSlotLimit = limiter
    ? [limiter.middleware('admin-upload-slot', 'uid-or-ip')]
    : [];
  const verifyLimit = limiter
    ? [limiter.middleware('admin-verify', 'uid-or-ip')]
    : [];
  const publishLimit = limiter
    ? [limiter.middleware('admin-publish', 'uid-or-ip')]
    : [];

  if (limiter) {
    router.use(limiter.middleware('preauth-admin', 'ip-only'));
  }
  router.use(params.requireAdminOrOwner);

  router.get('/branches', ...adminReadLimit, async (_req, res, next) => {
    try {
      const items = await params.catalogService.listAllBranchesForAdmin();
      res.status(200).json({ items });
    } catch (err) {
      next(err);
    }
  });

  router.get('/branches/:branchId/subjects', ...adminReadLimit, async (req, res, next) => {
    try {
      const items = await params.catalogService.listAllSubjectsForAdmin(
        req.params.branchId,
      );
      res.status(200).json({ branchId: req.params.branchId, items });
    } catch (err) {
      next(err);
    }
  });

  router.get('/subjects/:subjectId/sections', ...adminReadLimit, async (req, res, next) => {
    try {
      const items = await params.catalogService.listAllSectionsForAdmin(
        req.params.subjectId,
      );
      res.status(200).json({ subjectId: req.params.subjectId, items });
    } catch (err) {
      next(err);
    }
  });

  router.get('/sections/:sectionId/study-files', ...adminReadLimit, async (req, res, next) => {
    try {
      const items = await params.catalogService.listStudyFilesForAdmin(
        req.params.sectionId,
      );
      res.status(200).json({ sectionId: req.params.sectionId, items });
    } catch (err) {
      next(err);
    }
  });

  router.get('/study-files/:fileId/versions', ...adminReadLimit, async (req, res, next) => {
    try {
      const items = await params.catalogService.listStudyFileVersionsForAdmin(
        req.params.fileId,
      );
      res.status(200).json({ fileId: req.params.fileId, items });
    } catch (err) {
      next(err);
    }
  });

  router.post(
    '/study-files/:fileId/versions',
    ...adminWriteLimit,
    async (req: AuthenticatedRequest, res, next) => {
      try {
        const body = assertPlainObject(req.body);
        rejectForbiddenFields(body);
        rejectUnexpectedKeys(body, [
          'versionNumber',
          'versionLabel',
          'versionNotes',
          'customVersionId',
        ]);

        const created = await params.catalogService.createDraftStudyFileVersion({
          fileId: req.params.fileId,
          versionNumber: body.versionNumber,
          versionLabel: body.versionLabel,
          versionNotes: body.versionNotes,
          customVersionId: body.customVersionId,
          actorUid: req.authContext!.uid!,
        });
        res.status(201).json({ version: created });
      } catch (err) {
        next(err);
      }
    },
  );

  router.patch(
    '/study-files/:fileId/versions/:versionId',
    ...adminWriteLimit,
    async (req: AuthenticatedRequest, res, next) => {
      try {
        const body = assertPlainObject(req.body);
        rejectForbiddenFields(body, ['versionNumber', 'fileId']);
        rejectUnexpectedKeys(body, ['versionLabel', 'versionNotes']);

        await params.catalogService.updateStudyFileVersionMetadata({
          fileId: req.params.fileId,
          versionId: req.params.versionId,
          versionLabel: body.versionLabel,
          versionNotes: body.versionNotes,
          actorUid: req.authContext!.uid!,
        });
        res.status(200).json({ updated: true });
      } catch (err) {
        next(err);
      }
    },
  );

  router.post(
    '/study-files/:fileId/versions/:versionId/upload-slot',
    ...uploadSlotLimit,
    async (req: AuthenticatedRequest, res, next) => {
      try {
        const body = assertPlainObject(req.body);
        rejectForbiddenFields(body);
        rejectUnexpectedKeys(body, ['expectedSizeBytes', 'expectedSha256']);

        const slot = await params.uploadVerificationService.createUploadSlot({
          fileId: req.params.fileId,
          versionId: req.params.versionId,
          expectedSizeBytes: body.expectedSizeBytes,
          expectedSha256: body.expectedSha256,
          actorUid: req.authContext!.uid!,
        });
        res.status(201).json(slot);
      } catch (err) {
        next(err);
      }
    },
  );

  router.post(
    '/study-files/:fileId/versions/:versionId/verify',
    ...verifyLimit,
    async (req: AuthenticatedRequest, res, next) => {
      try {
        const body = assertPlainObject(req.body);
        rejectForbiddenFields(body, ['expectedSizeBytes', 'expectedSha256']);
        rejectUnexpectedKeys(body, ['uploadSessionId']);

        const verified = await params.uploadVerificationService.verifyUploadedPdf({
          fileId: req.params.fileId,
          versionId: req.params.versionId,
          uploadSessionId: body.uploadSessionId,
          actorUid: req.authContext!.uid!,
        });
        res.status(200).json(verified);
      } catch (err) {
        next(err);
      }
    },
  );

  router.post(
    '/study-files/:fileId/versions/:versionId/publish',
    ...publishLimit,
    async (req: AuthenticatedRequest, res, next) => {
      try {
        const rawBody = req.body ?? {};
        const body = assertPlainObject(rawBody);
        rejectForbiddenFields(body);
        rejectUnexpectedKeys(body, []);

        const published = await params.uploadVerificationService.publishVersion({
          fileId: req.params.fileId,
          versionId: req.params.versionId,
          actorUid: req.authContext!.uid!,
        });
        res.status(200).json(published);
      } catch (err) {
        next(err);
      }
    },
  );

  return router;
}
