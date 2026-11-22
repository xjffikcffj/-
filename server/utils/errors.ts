export type ApiErrorCode =
  | 'BAD_REQUEST'
  | 'VALIDATION_ERROR'
  | 'PRIVILEGED_FIELD_FORBIDDEN'
  | 'UNAUTHENTICATED'
  | 'TOKEN_EXPIRED'
  | 'TOKEN_REVOKED'
  | 'FORBIDDEN'
  | 'GUEST_DOWNLOAD_FORBIDDEN'
  | 'INSUFFICIENT_ROLE'
  | 'NOT_FOUND'
  | 'CATALOG_UNAVAILABLE'
  | 'FILE_ARCHIVED'
  | 'VERSION_ARCHIVED'
  | 'ARCHIVE_STATE_UNSPECIFIED'
  | 'VERSION_ALREADY_PUBLISHED'
  | 'VERSION_ALREADY_VERIFIED'
  | 'VERSION_NUMBER_CONFLICT'
  | 'NO_ACTIVE_VERSION'
  | 'VERIFY_IN_PROGRESS'
  | 'CONFLICT_STALE_LOCK'
  | 'SESSION_EXPIRED'
  | 'SESSION_SUPERSEDED'
  | 'SESSION_FAILED'
  | 'SESSION_MISMATCH'
  | 'INVALID_PDF_HEADER'
  | 'SIZE_MISMATCH'
  | 'SHA256_MISMATCH'
  | 'ETAG_MISMATCH'
  | 'COPY_FAILED'
  | 'PROOF_MISMATCH'
  | 'PROOF_MISSING'
  | 'R2_OBJECT_MISSING'
  | 'R2_NOT_CONFIGURED'
  | 'USER_NOT_FOUND'
  | 'CANNOT_MODIFY_SELF'
  | 'OWNER_ROLE_IMMUTABLE'
  | 'STAFF_ROLE_CLAIMS_UPDATED_TOKEN_REVOKE_FAILED'
  | 'STAFF_ROLE_UPDATED_AUDIT_LOG_FAILED'
  | 'AUTH_ADMIN_NOT_CONFIGURED'
  | 'AUDIT_SERVICE_UNAVAILABLE'
  | 'NOTIFICATION_SERVICE_UNAVAILABLE'
  | 'RATE_LIMIT_EXCEEDED'
  | 'INTERNAL_ERROR';

export class ApiError extends Error {
  readonly statusCode: number;
  readonly code: ApiErrorCode;
  readonly details?: Record<string, unknown>;

  constructor(
    statusCode: number,
    code: ApiErrorCode,
    message: string,
    details?: Record<string, unknown>,
  ) {
    super(message);
    this.name = 'ApiError';
    this.statusCode = statusCode;
    this.code = code;
    this.details = details;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}
