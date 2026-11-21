import { ApiError } from '../utils/errors.js';

const DOC_ID_REGEX = /^[a-zA-Z0-9_-]{1,128}$/;
const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SHA256_HEX_REGEX = /^[a-fA-F0-9]{64}$/;

export const MAX_TITLE_LENGTH = 120;
export const MAX_DESCRIPTION_LENGTH = 1000;
export const MAX_VERSION_LABEL_LENGTH = 64;
export const MAX_VERSION_NOTES_LENGTH = 500;

export const CLIENT_FORBIDDEN_FIELDS = [
  'r2ObjectKey',
  'fileSizeBytes',
  'checksumSha256',
  'binaryVerificationStatus',
  'verifiedUploadSessionId',
  'verifiedAttemptId',
  'verifiedProof',
  'activeVersionId',
  'activeVersionNumber',
  'activeVersionLabel',
  'activeVersionPublishedAt',
  'registeredVersionNumbers',
  'isPublished',
  'attemptId',
  'activeAttemptId',
  'lockToken',
  'finalObjectKey',
  'stagingKey',
  'consumedAt',
  'binaryVerifiedAt',
] as const;

export function assertPlainObject(
  value: unknown,
  contextName = 'request body',
): Record<string, unknown> {
  if (
    value === null ||
    typeof value !== 'object' ||
    Array.isArray(value)
  ) {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${contextName} must be a JSON object.`,
    );
  }
  return value as Record<string, unknown>;
}

export function rejectForbiddenFields(
  body: Record<string, unknown>,
  extraForbidden: readonly string[] = [],
): void {
  const forbidden = new Set<string>([
    ...CLIENT_FORBIDDEN_FIELDS,
    ...extraForbidden,
  ]);
  for (const key of Object.keys(body)) {
    if (forbidden.has(key)) {
      throw new ApiError(
        403,
        'PRIVILEGED_FIELD_FORBIDDEN',
        `Client is not permitted to supply server-controlled field '${key}'.`,
        { field: key },
      );
    }
  }
}

export function rejectUnexpectedKeys(
  body: Record<string, unknown>,
  allowedKeys: readonly string[],
): void {
  const allowed = new Set(allowedKeys);
  for (const key of Object.keys(body)) {
    if (!allowed.has(key)) {
      throw new ApiError(
        400,
        'VALIDATION_ERROR',
        `Unexpected field '${key}' in request payload.`,
        { field: key },
      );
    }
  }
}

export function validateDocId(raw: unknown, fieldName: string): string {
  if (typeof raw !== 'string') {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} must be a string.`,
      { field: fieldName },
    );
  }
  const trimmed = raw.trim();
  if (!DOC_ID_REGEX.test(trimmed)) {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} has invalid characters or length.`,
      { field: fieldName },
    );
  }
  return trimmed;
}

export function validateUuid(raw: unknown, fieldName: string): string {
  if (typeof raw !== 'string') {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} must be a valid UUID string.`,
      { field: fieldName },
    );
  }
  const trimmed = raw.trim().toLowerCase();
  if (!UUID_REGEX.test(trimmed)) {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} must be a full un-truncated UUID.`,
      { field: fieldName },
    );
  }
  return trimmed;
}

export function validateSha256Hex(
  raw: unknown,
  fieldName = 'expectedSha256',
): string {
  if (typeof raw !== 'string') {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} must be a 64-character hex SHA-256 string.`,
      { field: fieldName },
    );
  }
  const trimmed = raw.trim().toLowerCase();
  if (!SHA256_HEX_REGEX.test(trimmed)) {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} must be exactly 64 hexadecimal characters.`,
      { field: fieldName },
    );
  }
  return trimmed;
}

export function validatePositiveInt(
  raw: unknown,
  fieldName: string,
  maxValue = Number.MAX_SAFE_INTEGER,
): number {
  if (
    typeof raw !== 'number' ||
    !Number.isInteger(raw) ||
    !Number.isSafeInteger(raw) ||
    raw < 1 ||
    raw > maxValue
  ) {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} must be a positive integer between 1 and ${maxValue}.`,
      { field: fieldName },
    );
  }
  return raw;
}

export function validateNonEmptyString(
  raw: unknown,
  fieldName: string,
  maxLen: number,
): string {
  if (typeof raw !== 'string') {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} must be a non-empty string.`,
      { field: fieldName },
    );
  }
  const trimmed = raw.trim();
  if (trimmed.length === 0 || trimmed.length > maxLen) {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} must be between 1 and ${maxLen} characters.`,
      { field: fieldName },
    );
  }
  return trimmed;
}

export function validateOptionalString(
  raw: unknown,
  fieldName: string,
  maxLen: number,
): string | null {
  if (raw === undefined || raw === null) {
    return null;
  }
  if (typeof raw !== 'string') {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} must be a string or null.`,
      { field: fieldName },
    );
  }
  const trimmed = raw.trim();
  if (trimmed.length === 0) {
    return null;
  }
  if (trimmed.length > maxLen) {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      `${fieldName} must not exceed ${maxLen} characters.`,
      { field: fieldName },
    );
  }
  return trimmed;
}

export function isReliableR2ObjectKey(
  fileId: string,
  versionId: string,
  r2ObjectKey: unknown,
): r2ObjectKey is string {
  if (typeof r2ObjectKey !== 'string') return false;
  const cleanKey = r2ObjectKey.trim();
  const cleanFileId = fileId.trim();
  const cleanVersionId = versionId.trim();
  if (!cleanKey || !cleanFileId || !cleanVersionId) return false;

  if (
    cleanKey.includes('://') ||
    cleanKey.includes('..') ||
    cleanKey.includes('\\') ||
    cleanKey.includes('?') ||
    cleanKey.includes('#') ||
    /\s/.test(cleanKey) ||
    /fake/i.test(cleanKey) ||
    /placeholder/i.test(cleanKey)
  ) {
    return false;
  }

  const expectedPrefix = `study-files/${cleanFileId}/versions/${cleanVersionId}/`;
  if (!cleanKey.startsWith(expectedPrefix)) {
    return false;
  }

  const fileName = cleanKey.slice(expectedPrefix.length);
  return (
    fileName.length > 4 &&
    !fileName.includes('/') &&
    fileName.endsWith('.pdf')
  );
}
