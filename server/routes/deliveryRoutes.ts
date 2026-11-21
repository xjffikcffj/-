import { Router } from 'express';
import type { RequestHandler } from 'express';
import type { DeliveryService } from '../services/deliveryService.js';

export function createDeliveryRouter(params: {
  deliveryService: DeliveryService;
  optionalAuth: RequestHandler;
  requireNonGuest: RequestHandler;
}): Router {
  const router = Router();

  router.get(
    '/:fileId/versions/:versionId/stream',
    params.optionalAuth,
    async (req, res, next) => {
      try {
        await params.deliveryService.deliverPublishedPdf({
          fileId: req.params.fileId,
          versionId: req.params.versionId,
          mode: 'stream',
          res,
        });
      } catch (err) {
        next(err);
      }
    },
  );

  router.get(
    '/:fileId/versions/:versionId/download',
    params.requireNonGuest,
    async (req, res, next) => {
      try {
        await params.deliveryService.deliverPublishedPdf({
          fileId: req.params.fileId,
          versionId: req.params.versionId,
          mode: 'download',
          res,
        });
      } catch (err) {
        next(err);
      }
    },
  );

  return router;
}
