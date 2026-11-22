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

export class StaffManagementService {
  private readonly staffAuthManager?: StaffAuthManager;
  private readonly auditLogService?: AuditLogService;

  constructor(options: {
    staffAuthManager?: StaffAuthManager;
    auditLogService?: AuditLogService;
  }) {
    this.staffAuthManager = options.staffAuthManager;
    this.auditLogService = options.auditLogService;
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
    await manager.revokeRefreshTokens(targetUser.uid);

    const targetLabel = targetUser.email ?? targetUser.uid;
    await this.auditLogService?.recordBestEffort({
      action: rawRole === 'admin' ? 'ADMIN_ROLE_GRANTED' : 'ADMIN_ROLE_REVOKED',
      actorUid: params.actorUid,
      actorRole: params.actorRole,
      targetCollection: 'users',
      targetId: targetUser.uid,
      summary:
        rawRole === 'admin'
          ? `Granted admin role to ${targetLabel}`
          : `Revoked admin role from ${targetLabel}`,
      source: 'backend',
    });

    return {
      status: 'UPDATED',
      uid: targetUser.uid,
      email: targetUser.email ?? null,
      role: rawRole === 'admin' ? 'admin' : 'student',
      tokensRevoked: true,
    };
  }
}
