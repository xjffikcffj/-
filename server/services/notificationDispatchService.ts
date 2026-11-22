import { randomUUID } from 'node:crypto';
import type { PushNotificationDispatcher } from '../firebase/adminApp.js';
import type {
  AppUserRole,
  BroadcastNotificationResponseDto,
  StudyNotificationType,
} from '../types/domain.js';
import { validateNonEmptyString } from '../validation/validators.js';
import type { AuditLogService } from './auditLogService.js';

export const HAQIBATI_UPDATES_TOPIC = 'haqibati_updates';

const MAX_NOTIFICATION_TITLE_LENGTH = 120;
const MAX_NOTIFICATION_BODY_LENGTH = 500;

export class NotificationDispatchService {
  private readonly dispatcher?: PushNotificationDispatcher;
  private readonly auditLogService?: AuditLogService;
  private readonly uuidGenerator: () => string;
  private readonly nowMs: () => number;
  private readonly warn: (message: string) => void;

  constructor(options: {
    pushNotificationDispatcher?: PushNotificationDispatcher;
    auditLogService?: AuditLogService;
    uuidGenerator?: () => string;
    nowMs?: () => number;
    warn?: (message: string) => void;
  }) {
    this.dispatcher = options.pushNotificationDispatcher;
    this.auditLogService = options.auditLogService;
    this.uuidGenerator = options.uuidGenerator ?? (() => randomUUID());
    this.nowMs = options.nowMs ?? (() => Date.now());
    this.warn = options.warn ?? ((message: string) => console.warn(message));
  }

  async notifyPublishedVersion(params: {
    fileId: string;
    fileTitle: string;
    versionId: string;
    versionNumber: number;
    versionLabel: string;
    isNewFile: boolean;
  }): Promise<BroadcastNotificationResponseDto> {
    const type: StudyNotificationType = params.isNewFile
      ? 'NEW_FILE'
      : 'NEW_VERSION';
    const title = params.isNewFile
      ? 'ملف دراسي جديد'
      : 'إصدار جديد لملف دراسي';
    const body = params.isNewFile
      ? `تم نشر ${params.fileTitle} (${params.versionLabel})`
      : `تم تحديث ${params.fileTitle} إلى الإصدار ${params.versionLabel}`;

    return this.dispatchBestEffort({
      type,
      title,
      body,
      extraData: {
        fileId: params.fileId,
        fileTitle: params.fileTitle,
        versionId: params.versionId,
        versionNumber: String(params.versionNumber),
        versionLabel: params.versionLabel,
      },
    });
  }

  async broadcastImportantUpdate(params: {
    title: unknown;
    body: unknown;
    actorUid: string;
    actorRole: AppUserRole | string;
  }): Promise<BroadcastNotificationResponseDto> {
    const title = validateNonEmptyString(
      params.title,
      'title',
      MAX_NOTIFICATION_TITLE_LENGTH,
    );
    const body = validateNonEmptyString(
      params.body,
      'body',
      MAX_NOTIFICATION_BODY_LENGTH,
    );

    const outcome = await this.dispatchBestEffort({
      type: 'IMPORTANT_UPDATE',
      title,
      body,
      extraData: {},
    });

    await this.auditLogService?.recordBestEffort({
      action: 'IMPORTANT_UPDATE_BROADCAST',
      actorUid: params.actorUid,
      actorRole: params.actorRole,
      targetCollection: 'notifications',
      targetId: outcome.notificationId,
      summary: `${title} (${outcome.sent ? 'FCM dispatched' : outcome.reason ?? 'not dispatched'})`,
      source: 'backend',
    });

    return outcome;
  }

  private async dispatchBestEffort(params: {
    type: StudyNotificationType;
    title: string;
    body: string;
    extraData: Record<string, string>;
  }): Promise<BroadcastNotificationResponseDto> {
    const notificationId = this.uuidGenerator().toLowerCase();
    const sentAt = String(this.nowMs());

    if (!this.dispatcher) {
      return {
        notificationId,
        topic: HAQIBATI_UPDATES_TOPIC,
        type: params.type,
        title: params.title,
        body: params.body,
        sent: false,
        reason: 'FCM_NOT_CONFIGURED',
      };
    }

    try {
      await this.dispatcher.sendToTopic({
        topic: HAQIBATI_UPDATES_TOPIC,
        data: {
          notificationId,
          type: params.type,
          title: params.title,
          body: params.body,
          sentAtEpochMillis: sentAt,
          ...params.extraData,
        },
      });
      return {
        notificationId,
        topic: HAQIBATI_UPDATES_TOPIC,
        type: params.type,
        title: params.title,
        body: params.body,
        sent: true,
      };
    } catch (err) {
      const code =
        typeof err === 'object' && err !== null && 'code' in err
          ? String((err as { code?: unknown }).code ?? 'FCM_DISPATCH_FAILED')
          : err instanceof Error
            ? err.name
            : 'FCM_DISPATCH_FAILED';
      this.warn(`[fcm-dispatch] Best-effort topic push failed for ${params.type}: ${code}`);
      return {
        notificationId,
        topic: HAQIBATI_UPDATES_TOPIC,
        type: params.type,
        title: params.title,
        body: params.body,
        sent: false,
        reason: code,
      };
    }
  }
}
