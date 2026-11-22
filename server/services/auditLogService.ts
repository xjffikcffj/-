import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import type {
  AppUserRole,
  AuditLogAction,
  AuditLogDto,
  AuditLogSource,
} from '../types/domain.js';
import { ApiError } from '../utils/errors.js';

const MAX_SUMMARY_LENGTH = 300;
const DEFAULT_AUDIT_LIMIT = 50;
const MAX_AUDIT_LIMIT = 100;

function extractEpochMillis(value: unknown): number | null {
  if (!value) return null;
  if (value instanceof Timestamp) {
    return value.toMillis();
  }
  if (value instanceof Date) {
    return value.getTime();
  }
  if (
    typeof value === 'object' &&
    value !== null &&
    'toMillis' in value &&
    typeof (value as { toMillis?: unknown }).toMillis === 'function'
  ) {
    return (value as { toMillis: () => number }).toMillis();
  }
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    return value;
  }
  return null;
}

function sanitizeSummary(raw: string): string {
  const trimmed = raw.trim().replace(/\s+/g, ' ');
  if (!trimmed) {
    return 'Administrative action recorded';
  }
  return trimmed.slice(0, MAX_SUMMARY_LENGTH);
}

export class AuditLogService {
  private readonly nowMs: () => number;
  private readonly warn: (message: string) => void;

  constructor(
    private readonly firestore: Firestore,
    options?: {
      nowMs?: () => number;
      warn?: (message: string) => void;
    },
  ) {
    this.nowMs = options?.nowMs ?? (() => Date.now());
    this.warn = options?.warn ?? ((message: string) => console.warn(message));
  }

  async recordAuditEvent(params: {
    action: AuditLogAction | string;
    actorUid: string;
    actorRole: AppUserRole | string;
    targetCollection: string;
    targetId: string;
    summary: string;
    source?: AuditLogSource;
  }): Promise<string> {
    const docRef = this.firestore.collection('audit_logs').doc();
    const createdAt = Timestamp.fromMillis(this.nowMs());
    await docRef.set({
      action: params.action.trim().slice(0, 64),
      actorUid: params.actorUid.trim(),
      actorRole: String(params.actorRole).trim().toLowerCase(),
      targetCollection: params.targetCollection.trim().slice(0, 64),
      targetId: params.targetId.trim().slice(0, 128),
      summary: sanitizeSummary(params.summary),
      source: params.source ?? 'backend',
      createdAt,
    });
    return docRef.id;
  }

  async recordBestEffort(params: {
    action: AuditLogAction | string;
    actorUid: string;
    actorRole: AppUserRole | string;
    targetCollection: string;
    targetId: string;
    summary: string;
    source?: AuditLogSource;
  }): Promise<void> {
    try {
      await this.recordAuditEvent(params);
    } catch (err) {
      const reason = err instanceof Error ? err.name : 'UnknownError';
      this.warn(`[audit-log] Non-fatal write failure for ${params.action}: ${reason}`);
    }
  }

  async listAuditLogs(options?: {
    limit?: unknown;
    action?: unknown;
  }): Promise<AuditLogDto[]> {
    let limit = DEFAULT_AUDIT_LIMIT;
    if (options?.limit !== undefined && options.limit !== null && options.limit !== '') {
      const parsed =
        typeof options.limit === 'number'
          ? options.limit
          : Number.parseInt(String(options.limit), 10);
      if (!Number.isInteger(parsed) || parsed < 1 || parsed > MAX_AUDIT_LIMIT) {
        throw new ApiError(
          400,
          'VALIDATION_ERROR',
          `limit must be an integer between 1 and ${MAX_AUDIT_LIMIT}.`,
          { field: 'limit' },
        );
      }
      limit = parsed;
    }

    let actionFilter: string | null = null;
    if (typeof options?.action === 'string' && options.action.trim().length > 0) {
      actionFilter = options.action.trim().slice(0, 64);
    }

    const fetchLimit = actionFilter ? Math.min(limit * 3, 200) : limit;
    const snap = await this.firestore
      .collection('audit_logs')
      .orderBy('createdAt', 'desc')
      .limit(fetchLimit)
      .get();

    const mapped: AuditLogDto[] = [];
    for (const doc of snap.docs) {
      const data = doc.data();
      const action = typeof data.action === 'string' ? data.action.trim() : '';
      const actorUid =
        typeof data.actorUid === 'string' ? data.actorUid.trim() : '';
      const actorRole =
        typeof data.actorRole === 'string' ? data.actorRole.trim() : 'admin';
      const targetCollection =
        typeof data.targetCollection === 'string'
          ? data.targetCollection.trim()
          : '';
      const targetId =
        typeof data.targetId === 'string' ? data.targetId.trim() : '';
      const summary =
        typeof data.summary === 'string' ? data.summary.trim() : '';
      const source =
        typeof data.source === 'string' ? data.source.trim() : 'backend';

      if (!action || !actorUid || !targetCollection || !targetId || !summary) {
        continue;
      }
      if (actionFilter && action !== actionFilter) {
        continue;
      }

      mapped.push({
        id: doc.id,
        action,
        actorUid,
        actorRole,
        targetCollection,
        targetId,
        summary,
        source,
        createdAtEpochMillis: extractEpochMillis(data.createdAt),
      });

      if (mapped.length >= limit) {
        break;
      }
    }

    return mapped.sort(
      (a, b) => (b.createdAtEpochMillis ?? 0) - (a.createdAtEpochMillis ?? 0),
    );
  }
}
