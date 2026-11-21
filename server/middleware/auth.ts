import type { NextFunction, Request, Response } from 'express';
import type { DecodedIdTokenLike, IdTokenVerifier } from '../firebase/adminApp.js';
import type { AppUserRole, AuthContext } from '../types/domain.js';
import { ApiError } from '../utils/errors.js';

export interface AuthenticatedRequest extends Request {
  authContext?: AuthContext;
}

const GUEST_CONTEXT: AuthContext = {
  isAuthenticated: false,
  isAnonymous: false,
  uid: null,
  email: null,
  role: 'guest',
};

function extractBearerToken(req: Request): string | null {
  const header = req.header('authorization') ?? req.header('Authorization');
  if (!header) {
    return null;
  }
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  if (!match) {
    throw new ApiError(
      401,
      'UNAUTHENTICATED',
      'Authorization header must follow Bearer token format.',
    );
  }
  const token = match[1].trim();
  if (!token) {
    throw new ApiError(401, 'UNAUTHENTICATED', 'Bearer token must not be empty.');
  }
  return token;
}

function mapDecodedTokenToAuthContext(
  decoded: DecodedIdTokenLike,
): AuthContext {
  const signInProvider = decoded.firebase?.sign_in_provider;
  const isAnonymous = signInProvider === 'anonymous';

  if (isAnonymous) {
    // Anonymous Firebase sessions are strictly treated as guest tier regardless of claims.
    return {
      isAuthenticated: true,
      isAnonymous: true,
      uid: decoded.uid || null,
      email: null,
      role: 'guest',
    };
  }

  const rawRole =
    typeof decoded.role === 'string' ? decoded.role.trim().toLowerCase() : '';
  let role: AppUserRole = 'student';
  if (rawRole === 'owner') {
    role = 'owner';
  } else if (rawRole === 'admin') {
    role = 'admin';
  }

  return {
    isAuthenticated: true,
    isAnonymous: false,
    uid: decoded.uid,
    email: typeof decoded.email === 'string' ? decoded.email : null,
    role,
  };
}

function mapAuthError(err: unknown): ApiError {
  if (err instanceof ApiError) return err;
  const code =
    typeof err === 'object' && err !== null && 'code' in err
      ? String((err as { code?: unknown }).code ?? '')
      : '';

  if (code === 'auth/id-token-revoked' || code === 'auth/user-disabled') {
    return new ApiError(
      401,
      'TOKEN_REVOKED',
      'Firebase ID token has been revoked or user account is disabled.',
    );
  }
  if (code === 'auth/id-token-expired') {
    return new ApiError(
      401,
      'TOKEN_EXPIRED',
      'Firebase ID token has expired.',
    );
  }
  return new ApiError(
    401,
    'UNAUTHENTICATED',
    'Invalid or unverified Firebase ID token.',
  );
}

export function createAuthMiddleware(idTokenVerifier: IdTokenVerifier) {
  const resolveAuth = async (req: Request): Promise<AuthContext> => {
    const token = extractBearerToken(req);
    if (!token) {
      return GUEST_CONTEXT;
    }
    try {
      const decoded = await idTokenVerifier.verifyIdToken(token, true);
      if (!decoded || typeof decoded.uid !== 'string' || !decoded.uid.trim()) {
        throw new ApiError(
          401,
          'UNAUTHENTICATED',
          'Decoded token does not contain a valid user UID.',
        );
      }
      return mapDecodedTokenToAuthContext(decoded);
    } catch (err) {
      throw mapAuthError(err);
    }
  };

  const optionalAuth = (
    req: AuthenticatedRequest,
    _res: Response,
    next: NextFunction,
  ) => {
    resolveAuth(req)
      .then((ctx) => {
        req.authContext = ctx;
        next();
      })
      .catch(next);
  };

  const requireNonGuest = (
    req: AuthenticatedRequest,
    _res: Response,
    next: NextFunction,
  ) => {
    resolveAuth(req)
      .then((ctx) => {
        if (!ctx.isAuthenticated || !ctx.uid) {
          throw new ApiError(
            401,
            'UNAUTHENTICATED',
            'Sign-in with a registered account is required to download study files.',
          );
        }
        if (ctx.isAnonymous || ctx.role === 'guest') {
          throw new ApiError(
            403,
            'GUEST_DOWNLOAD_FORBIDDEN',
            'Guest or anonymous sessions are not permitted to download offline PDFs.',
          );
        }
        req.authContext = ctx;
        next();
      })
      .catch(next);
  };

  const requireAdminOrOwner = (
    req: AuthenticatedRequest,
    _res: Response,
    next: NextFunction,
  ) => {
    resolveAuth(req)
      .then((ctx) => {
        if (!ctx.isAuthenticated || !ctx.uid) {
          throw new ApiError(
            401,
            'UNAUTHENTICATED',
            'Admin or owner authentication is required.',
          );
        }
        if (ctx.role !== 'admin' && ctx.role !== 'owner') {
          throw new ApiError(
            403,
            'INSUFFICIENT_ROLE',
            'This endpoint requires admin or owner custom claims.',
          );
        }
        req.authContext = ctx;
        next();
      })
      .catch(next);
  };

  return {
    optionalAuth,
    requireNonGuest,
    requireAdminOrOwner,
  };
}
