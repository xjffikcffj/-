import express, {
  type ErrorRequestHandler,
  type Express,
  type NextFunction,
  type Request,
  type Response,
} from 'express';
import type { Firestore } from 'firebase-admin/firestore';
import { isR2Configured, type ServerConfig } from './config/env.js';
import type { IdTokenVerifier } from './firebase/adminApp.js';
import { createAuthMiddleware } from './middleware/auth.js';
import {
  InMemoryRateLimiter,
  type RateLimitOverrides,
} from './middleware/rateLimit.js';
import { createAdminRouter } from './routes/adminRoutes.js';
import { createCatalogRouter } from './routes/catalogRoutes.js';
import { createDeliveryRouter } from './routes/deliveryRoutes.js';
import { CatalogService } from './services/catalogService.js';
import { DeliveryService } from './services/deliveryService.js';
import { UploadVerificationService } from './services/uploadVerificationService.js';
import type { R2StoragePort } from './storage/r2Storage.js';
import { isApiError } from './utils/errors.js';

export interface BackendAppDependencies {
  config: ServerConfig;
  firestore: Firestore;
  idTokenVerifier: IdTokenVerifier;
  r2Storage: R2StoragePort;
  nowMs?: () => number;
  uuidGenerator?: () => string;
  enableRateLimiting?: boolean;
  rateLimits?: RateLimitOverrides;
}

export function createBackendApp(deps: BackendAppDependencies): Express {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', deps.config.trustProxy);

  app.use((req: Request, res: Response, next: NextFunction) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');

    const origin = req.header('origin');
    if (origin && deps.config.allowedOrigins.includes(origin)) {
      res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Vary', 'Origin');
      res.setHeader(
        'Access-Control-Allow-Methods',
        'GET, POST, PATCH, OPTIONS',
      );
      res.setHeader(
        'Access-Control-Allow-Headers',
        'Authorization, Content-Type',
      );
    }

    if (req.method === 'OPTIONS') {
      res.status(204).end();
      return;
    }
    next();
  });

  app.use(express.json({ limit: '64kb' }));

  const { optionalAuth, requireNonGuest, requireAdminOrOwner } =
    createAuthMiddleware(deps.idTokenVerifier);

  const catalogService = new CatalogService(deps.firestore);
  const uploadVerificationService = new UploadVerificationService({
    firestore: deps.firestore,
    r2Storage: deps.r2Storage,
    config: deps.config,
    nowMs: deps.nowMs,
    uuidGenerator: deps.uuidGenerator,
  });
  const deliveryService = new DeliveryService(catalogService, deps.r2Storage);
  const rateLimiter = new InMemoryRateLimiter({
    enabled: deps.enableRateLimiting ?? true,
    overrides: deps.rateLimits,
    nowMs: deps.nowMs,
  });

  app.get('/api/health', (_req, res) => {
    res.status(200).json({
      status: 'ok',
      r2Configured: deps.r2Storage.isConfigured() && isR2Configured(deps.config),
    });
  });

  app.use(
    '/api/catalog',
    createCatalogRouter(catalogService, optionalAuth, rateLimiter),
  );
  app.use(
    '/api/study-files',
    createDeliveryRouter({
      deliveryService,
      optionalAuth,
      requireNonGuest,
      rateLimiter,
    }),
  );
  app.use(
    '/api/admin',
    createAdminRouter({
      catalogService,
      uploadVerificationService,
      requireAdminOrOwner,
      rateLimiter,
    }),
  );

  app.use('/api', (_req, res) => {
    res.status(404).json({
      error: {
        code: 'NOT_FOUND',
        message: 'API route not found.',
      },
    });
  });

  const errorHandler: ErrorRequestHandler = (err, _req, res, _next) => {
    if (res.headersSent) {
      res.end();
      return;
    }

    if (
      err instanceof SyntaxError &&
      'type' in err &&
      (err as { type?: unknown }).type === 'entity.parse.failed'
    ) {
      res.status(400).json({
        error: {
          code: 'BAD_REQUEST',
          message: 'Malformed JSON request body.',
        },
      });
      return;
    }

    if (isApiError(err)) {
      res.status(err.statusCode).json({
        error: {
          code: err.code,
          message: err.message,
          ...(err.details ? { details: err.details } : {}),
        },
      });
      return;
    }

    res.status(500).json({
      error: {
        code: 'INTERNAL_ERROR',
        message: 'An unexpected internal server error occurred.',
      },
    });
  };

  app.use(errorHandler);
  return app;
}
