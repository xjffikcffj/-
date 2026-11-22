import type { NextFunction, Request, RequestHandler, Response } from 'express';
import type { AuthenticatedRequest } from './auth.js';
import { ApiError } from '../utils/errors.js';

export type RateLimitPolicyName =
  | 'catalog'
  | 'delivery-stream'
  | 'delivery-download'
  | 'preauth-download'
  | 'preauth-admin'
  | 'admin-read'
  | 'admin-version-write'
  | 'admin-upload-slot'
  | 'admin-verify'
  | 'admin-publish'
  | 'admin-staff-read'
  | 'admin-staff-write'
  | 'admin-audit-read'
  | 'admin-notify-write';

export interface RateLimitRule {
  windowMs: number;
  max: number;
}

export type RateLimitOverrides = Partial<
  Record<RateLimitPolicyName, Partial<RateLimitRule>>
>;

export const DEFAULT_RATE_LIMIT_RULES: Record<RateLimitPolicyName, RateLimitRule> = {
  catalog: { windowMs: 60_000, max: 60 },
  'delivery-stream': { windowMs: 60_000, max: 30 },
  'delivery-download': { windowMs: 60_000, max: 25 },
  'preauth-download': { windowMs: 60_000, max: 90 },
  'preauth-admin': { windowMs: 60_000, max: 120 },
  'admin-read': { windowMs: 60_000, max: 60 },
  'admin-version-write': { windowMs: 60_000, max: 20 },
  'admin-upload-slot': { windowMs: 60_000, max: 15 },
  'admin-verify': { windowMs: 60_000, max: 15 },
  'admin-publish': { windowMs: 60_000, max: 15 },
  'admin-staff-read': { windowMs: 60_000, max: 30 },
  'admin-staff-write': { windowMs: 60_000, max: 10 },
  'admin-audit-read': { windowMs: 60_000, max: 30 },
  'admin-notify-write': { windowMs: 60_000, max: 10 },
};

interface RateLimitBucket {
  count: number;
  resetAtMs: number;
}

function normalizeClientIp(req: Request): string {
  // Rely solely on Express req.ip (governed by app.set('trust proxy', ...)),
  // never raw X-Forwarded-For or client-mutable headers.
  const rawIp = req.ip || req.socket?.remoteAddress || 'unknown';
  const trimmed = rawIp.trim();
  if (trimmed.startsWith('::ffff:')) {
    return trimmed.slice('::ffff:'.length);
  }
  return trimmed || 'unknown';
}

/**
 * Single-process in-memory rate limiter intended as local / per-instance
 * defense-in-depth. This is NOT a distributed rate limiter across multiple Cloud Run
 * instances; production deployments MUST enforce Edge / Load Balancer limits
 * (e.g. Cloud Armor / Cloudflare WAF) in front of Cloud Run.
 */
export class InMemoryRateLimiter {
  private readonly buckets = new Map<string, RateLimitBucket>();
  private readonly rules: Record<RateLimitPolicyName, RateLimitRule>;
  private readonly nowMs: () => number;
  private readonly enabled: boolean;
  private readonly maxEntries: number;
  private opsSinceSweep = 0;

  constructor(options?: {
    enabled?: boolean;
    overrides?: RateLimitOverrides;
    nowMs?: () => number;
    maxEntries?: number;
  }) {
    this.enabled = options?.enabled ?? true;
    this.nowMs = options?.nowMs ?? (() => Date.now());
    this.maxEntries = options?.maxEntries ?? 10_000;

    const merged = { ...DEFAULT_RATE_LIMIT_RULES };
    if (options?.overrides) {
      for (const policy of Object.keys(merged) as RateLimitPolicyName[]) {
        const override = options.overrides[policy];
        if (!override) continue;
        merged[policy] = {
          windowMs:
            typeof override.windowMs === 'number' && override.windowMs > 0
              ? override.windowMs
              : merged[policy].windowMs,
          max:
            typeof override.max === 'number' && override.max >= 1
              ? Math.floor(override.max)
              : merged[policy].max,
        };
      }
    }
    this.rules = merged;
  }

  middleware(
    policy: RateLimitPolicyName,
    keyMode: 'uid-or-ip' | 'ip-only' = 'uid-or-ip',
  ): RequestHandler {
    return (req: AuthenticatedRequest, res: Response, next: NextFunction) => {
      if (!this.enabled) {
        next();
        return;
      }

      const rule = this.rules[policy];
      const now = this.nowMs();
      this.sweepIfNeeded(now);

      const subjectKey = this.deriveSubjectKey(req, keyMode);
      const bucketKey = `${policy}:${subjectKey}`;
      let bucket = this.buckets.get(bucketKey);

      if (!bucket || now >= bucket.resetAtMs) {
        bucket = {
          count: 0,
          resetAtMs: now + rule.windowMs,
        };
        this.ensureCapacity(now);
        this.buckets.set(bucketKey, bucket);
      }

      const resetSeconds = Math.max(
        1,
        Math.ceil((bucket.resetAtMs - now) / 1000),
      );
      res.setHeader('RateLimit-Limit', String(rule.max));
      res.setHeader('RateLimit-Reset', String(resetSeconds));

      if (bucket.count >= rule.max) {
        res.setHeader('RateLimit-Remaining', '0');
        res.setHeader('Retry-After', String(resetSeconds));
        next(
          new ApiError(
            429,
            'RATE_LIMIT_EXCEEDED',
            'Too many requests for this endpoint. Please wait before retrying.',
            {
              policy,
              retryAfterSeconds: resetSeconds,
            },
          ),
        );
        return;
      }

      bucket.count += 1;
      res.setHeader(
        'RateLimit-Remaining',
        String(Math.max(0, rule.max - bucket.count)),
      );
      next();
    };
  }

  private deriveSubjectKey(
    req: AuthenticatedRequest,
    keyMode: 'uid-or-ip' | 'ip-only',
  ): string {
    if (keyMode === 'uid-or-ip') {
      const uid = req.authContext?.uid?.trim();
      if (uid) {
        // Key by verified Firebase UID only; never embed raw Bearer tokens, emails, or signed URLs.
        return `uid:${uid}`;
      }
    }
    return `ip:${normalizeClientIp(req)}`;
  }

  private sweepIfNeeded(now: number): void {
    this.opsSinceSweep += 1;
    if (this.opsSinceSweep < 256 && this.buckets.size < this.maxEntries) {
      return;
    }
    this.opsSinceSweep = 0;
    for (const [key, entry] of this.buckets.entries()) {
      if (now >= entry.resetAtMs) {
        this.buckets.delete(key);
      }
    }
  }

  private ensureCapacity(now: number): void {
    if (this.buckets.size < this.maxEntries) {
      return;
    }
    for (const [key, entry] of this.buckets.entries()) {
      if (now >= entry.resetAtMs) {
        this.buckets.delete(key);
      }
    }
    while (this.buckets.size >= this.maxEntries) {
      const oldestKey = this.buckets.keys().next().value;
      if (oldestKey === undefined) break;
      this.buckets.delete(oldestKey);
    }
  }
}
