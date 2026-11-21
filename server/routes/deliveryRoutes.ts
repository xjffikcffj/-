import { Router } from 'express';
import type { RequestHandler } from 'express';
import type { InMemoryRateLimiter } from '../middleware/rateLimit.js';
import type { DeliveryService } from '../services/deliveryService.js';

export function createDeliveryRouter(params: {
  deliveryService: DeliveryService;
  optionalAuth: RequestHandler;
  requireNonGuest: RequestHandler;
  rateLimiter?: InMemoryRateLimiter;
}): Router {
  const router = Router();
  const limiter = params.rateLimiter;

  router.get(
    '/:fileId/versions/:versionId/stream',
    params.optionalAuth,
    ...(limiter ? [limiter.middleware('delivery-stream', 'uid-or-ip')] : []),
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
    ...(limiter ? [limiter.middleware('preauth-download', 'ip-only')] : []),
    params.requireNonGuest,
    ...(limiter ? [limiter.middleware('delivery-download', 'uid-or-ip')] : []),
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
