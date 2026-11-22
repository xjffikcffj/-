import type {
  StaffAuthManager,
  StaffAuthUserRecord,
} from '../firebase/adminApp.js';
import type {
  AppUserRole,
  StaffMemberDto,
  StaffRole,
  UpdateStaffRoleResponseDto,
} from '../types/domain.js';
import { ApiError } from '../utils/errors.js';
import { validateDocId, validateNonEmptyString } from '../validation/validators.js';
import type { AuditLogService } from './auditLogService.js';

const MAX_LIST_PAGES = 10;

function extractStaffRole(claims?: Record<string, unknown>): StaffRole | null {
  const raw = typeof claims?.role === 'string' ? claims.role.trim().toLowerCase() : '';
  if (raw === 'owner') return 'owner';
  if (raw === 'admin') return 'admin';
  return null;
}

function isAuthUserNotFound(err: unknown): boolean {
  if (typeof err === 'object' && err !== null && 'code' in err) {
    const code = String((err as { code?: unknown }).code ?? '');
    if (code === 'auth/user-not-found') {
      return true;
    }
  }
  return false;
}

function extractErrorCode(err: unknown): string {
  if (typeof err === 'object' && err !== null && 'code' in err) {
    const code = (err as { code?: unknown }).code;
    if (typeof code === 'string' && code.trim().length > 0) {
      return code.trim();
    }
  }
  if (err instanceof Error && err.name.trim().length > 0) {
    return err.name.trim();
  }
  return 'UNKNOWN_ERROR';
}

export class StaffManagementService {
  private readonly staffAuthManager?: StaffAuthManager;
  private readonly auditLogService?: AuditLogService;
  private readonly warn: (message: string) => void;

  constructor(options: {
    staffAuthManager?: StaffAuthManager;
    auditLogService?: AuditLogService;
    warn?: (message: string) => void;
  }) {
    this.staffAuthManager = options.staffAuthManager;
    this.auditLogService = options.auditLogService;
    this.warn = options.warn ?? ((message: string) => console.warn(message));
  }

  private requireManager(): StaffAuthManager {
    if (!this.staffAuthManager) {
      throw new ApiError(
        503,
        'AUTH_ADMIN_NOT_CONFIGURED',
        'Firebase Auth admin service is not configured on this server instance.',
      );
    }
    return this.staffAuthManager;
  }

  async listStaffMembers(): Promise<StaffMemberDto[]> {
    const manager = this.requireManager();
    const staff: StaffMemberDto[] = [];

    let pageToken: string | undefined;
    for (let page = 0; page < MAX_LIST_PAGES; page += 1) {
      const result = await manager.listUsers(1000, pageToken);
      for (const user of result.users) {
        const role = extractStaffRole(user.customClaims);
        if (!role) continue;
        staff.push({
          uid: user.uid,
          email: user.email ?? null,
          displayName: user.displayName ?? null,
          role,
          disabled: Boolean(user.disabled),
          emailVerified: Boolean(user.emailVerified),
        });
      }
      if (!result.pageToken) {
        break;
      }
      pageToken = result.pageToken;
    }

    return staff.sort((a, b) => {
      if (a.role !== b.role) {
        return a.role === 'owner' ? -1 : 1;
      }
      const labelA = (a.email ?? a.uid).toLowerCase();
      const labelB = (b.email ?? b.uid).toLowerCase();
      return labelA.localeCompare(labelB);
    });
  }

  async updateStaffRole(params: {
    targetEmail?: unknown;
    targetUid?: unknown;
    role: unknown;
    actorUid: string;
    actorRole: AppUserRole | string;
  }): Promise<UpdateStaffRoleResponseDto> {
    const manager = this.requireManager();

    const rawRole = typeof params.role === 'string' ? params.role.trim().toLowerCase() : '';
    if (rawRole === 'owner') {
      throw new ApiError(
        403,
        'OWNER_ROLE_IMMUTABLE',
        'Owner custom claims cannot be assigned through the API.',
      );
    }
    if (rawRole !== 'admin' && rawRole !== 'none') {
      throw new ApiError(
        400,
        'VALIDATION_ERROR',
        "role must be either 'admin' or 'none'.",
        { field: 'role' },
      );
    }

    const hasEmail =
      params.targetEmail !== undefined &&
      params.targetEmail !== null &&
      String(params.targetEmail).trim().length > 0;
    const hasUid =
      params.targetUid !== undefined &&
      params.targetUid !== null &&
      String(params.targetUid).trim().length > 0;

    if (!hasEmail && !hasUid) {
      throw new ApiError(
        400,
        'VALIDATION_ERROR',
        'Either targetEmail or targetUid is required.',
      );
    }

    let targetUser: StaffAuthUserRecord;
    try {
      if (hasEmail) {
        const cleanEmail = validateNonEmptyString(
          params.targetEmail,
          'targetEmail',
          254,
        ).toLowerCase();
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(cleanEmail)) {
          throw new ApiError(
            400,
            'VALIDATION_ERROR',
            'targetEmail must be a valid email address.',
            { field: 'targetEmail' },
          );
        }
        targetUser = await manager.getUserByEmail(cleanEmail);
      } else {
        const cleanUid = validateDocId(params.targetUid, 'targetUid');
        targetUser = await manager.getUser(cleanUid);
      }
    } catch (err) {
      if (err instanceof ApiError) throw err;
      if (isAuthUserNotFound(err)) {
        throw new ApiError(
          404,
          'USER_NOT_FOUND',
          'Target Firebase user account was not found.',
        );
      }
      throw err;
    }

    if (targetUser.uid === params.actorUid) {
      throw new ApiError(
        409,
        'CANNOT_MODIFY_SELF',
        'You cannot modify your own staff role.',
      );
    }

    const existingRole = extractStaffRole(targetUser.customClaims);
    if (existingRole === 'owner') {
      throw new ApiError(
        403,
        'OWNER_ROLE_IMMUTABLE',
        'Existing owner accounts cannot be modified or demoted.',
      );
    }

    if (!this.auditLogService) {
      throw new ApiError(
        503,
        'AUDIT_SERVICE_UNAVAILABLE',
        'Audit log service is not configured on this server instance.',
      );
    }

    const nextClaims: Record<string, unknown> = {
      ...(targetUser.customClaims ?? {}),
    };
    if (rawRole === 'admin') {
      nextClaims.role = 'admin';
    } else {
      delete nextClaims.role;
    }

    const claimsPayload = Object.keys(nextClaims).length > 0 ? nextClaims : null;
    await manager.setCustomUserClaims(targetUser.uid, claimsPayload);

    const targetRoleDto: 'admin' | 'student' = rawRole === 'admin' ? 'admin' : 'student';
    let tokensRevoked = false;
    let revokeFailureReason: string | null = null;
    try {
      await manager.revokeRefreshTokens(targetUser.uid);
      tokensRevoked = true;
    } catch (err) {
      revokeFailureReason = extractErrorCode(err);
    }

    const targetLabel = targetUser.email ?? targetUser.uid;
    const baseSummary =
      rawRole === 'admin'
        ? `Granted admin role to ${targetLabel}`
        : `Revoked admin role from ${targetLabel}`;
    const auditSummary = tokensRevoked
      ? baseSummary
      : `${baseSummary} (partial state: custom claims updated, refresh token revocation failed)`;

    let auditRecorded = false;
    let auditFailureReason: string | null = null;
    try {
      await this.auditLogService.recordAuditEvent({
        action: rawRole === 'admin' ? 'ADMIN_ROLE_GRANTED' : 'ADMIN_ROLE_REVOKED',
        actorUid: params.actorUid,
        actorRole: params.actorRole,
        targetCollection: 'users',
        targetId: targetUser.uid,
        summary: auditSummary,
        source: 'backend',
      });
      auditRecorded = true;
    } catch (err) {
      auditFailureReason = extractErrorCode(err);
    }

    if (!tokensRevoked) {
      this.warn(
        `[staff-management] Partial role update for uid=${targetUser.uid} role=${targetRoleDto}: custom claims updated, revokeRefreshTokens failed (${revokeFailureReason ?? 'UNKNOWN_ERROR'}), auditRecorded=${auditRecorded}${auditFailureReason ? ` (${auditFailureReason})` : ''}`,
      );
      throw new ApiError(
        502,
        'STAFF_ROLE_CLAIMS_UPDATED_TOKEN_REVOKE_FAILED',
        'Staff custom claims were updated, but revoking active refresh tokens failed. Claims were not rolled back.',
        {
          claimsUpdated: true,
          tokensRevoked: false,
          auditRecorded,
          uid: targetUser.uid,
          role: targetRoleDto,
        },
      );
    }

    if (!auditRecorded) {
      this.warn(
        `[staff-management] Staff role updated and refresh tokens revoked for uid=${targetUser.uid} role=${targetRoleDto}, but mandatory audit log write failed (${auditFailureReason ?? 'UNKNOWN_ERROR'})`,
      );
      throw new ApiError(
        500,
        'STAFF_ROLE_UPDATED_AUDIT_LOG_FAILED',
        'Staff custom claims were updated and refresh tokens were revoked, but recording the audit log failed.',
        {
          claimsUpdated: true,
          tokensRevoked: true,
          auditRecorded: false,
          uid: targetUser.uid,
          role: targetRoleDto,
        },
      );
    }

    return {
      status: 'UPDATED',
      uid: targetUser.uid,
      email: targetUser.email ?? null,
      role: targetRoleDto,
      tokensRevoked: true,
    };
  }
}
