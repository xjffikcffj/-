import process from 'node:process';

export type TrustProxySetting = false | number | 'loopback';

export interface ServerConfig {
  port: number;
  trustProxy: TrustProxySetting;
  firebaseProjectId: string;
  firebaseServiceAccountJson?: string;
  firestoreEmulatorHost?: string;
  firebaseAuthEmulatorHost?: string;
  r2AccountId?: string;
  r2Endpoint?: string;
  r2BucketName?: string;
  r2AccessKeyId?: string;
  r2SecretAccessKey?: string;
  allowedOrigins: string[];
  uploadSessionTtlSeconds: number;
  verifyLockTtlSeconds: number;
  maxPdfBytes: number;
}

function parsePositiveIntEnv(
  raw: string | undefined,
  fallback: number,
  min = 1,
  max = Number.MAX_SAFE_INTEGER,
): number {
  if (!raw || raw.trim().length === 0) return fallback;
  const parsed = Number(raw.trim());
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    return fallback;
  }
  return parsed;
}

function parseTrustProxyEnv(raw: string | undefined): TrustProxySetting {
  const trimmed = raw?.trim().toLowerCase() ?? '';
  if (!trimmed || trimmed === 'false' || trimmed === '0') {
    return false;
  }
  if (trimmed === 'loopback') {
    return 'loopback';
  }
  // Reject blanket 'true'/'all' so clients cannot spoof X-Forwarded-For across arbitrary hops.
  const hops = Number(trimmed);
  if (Number.isInteger(hops) && hops >= 1 && hops <= 10) {
    return hops;
  }
  return false;
}

export function validateR2Endpoint(raw: string | undefined): string | undefined {
  const trimmed = raw?.trim() ?? '';
  if (trimmed.length === 0) {
    return undefined;
  }

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    throw new Error('Invalid R2_ENDPOINT: malformed URL.');
  }

  if (parsed.protocol !== 'https:') {
    throw new Error('Invalid R2_ENDPOINT: must use HTTPS.');
  }
  if (!parsed.hostname) {
    throw new Error('Invalid R2_ENDPOINT: hostname is required.');
  }
  if (parsed.username || parsed.password) {
    throw new Error('Invalid R2_ENDPOINT: embedded credentials are not allowed.');
  }
  if (parsed.search || parsed.hash) {
    throw new Error('Invalid R2_ENDPOINT: query parameters and fragments are not allowed.');
  }

  return parsed.pathname === '/'
    ? parsed.origin
    : `${parsed.origin}${parsed.pathname.replace(/\/+$/, '')}`;
}

export function loadServerConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  const allowedOrigins = (env.ALLOWED_ORIGINS ?? '')
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0);

  return {
    port: parsePositiveIntEnv(env.PORT, 3000, 1, 65535),
    trustProxy: parseTrustProxyEnv(env.TRUST_PROXY),
    firebaseProjectId:
      env.FIREBASE_PROJECT_ID?.trim() || 'student-learning-platform-2026',
    firebaseServiceAccountJson:
      env.FIREBASE_SERVICE_ACCOUNT_JSON?.trim() || undefined,
    firestoreEmulatorHost: env.FIRESTORE_EMULATOR_HOST?.trim() || undefined,
    firebaseAuthEmulatorHost:
      env.FIREBASE_AUTH_EMULATOR_HOST?.trim() || undefined,
    r2AccountId: env.R2_ACCOUNT_ID?.trim() || undefined,
    r2Endpoint: validateR2Endpoint(env.R2_ENDPOINT),
    r2BucketName: env.R2_BUCKET_NAME?.trim() || undefined,
    r2AccessKeyId: env.R2_ACCESS_KEY_ID?.trim() || undefined,
    r2SecretAccessKey: env.R2_SECRET_ACCESS_KEY?.trim() || undefined,
    allowedOrigins,
    uploadSessionTtlSeconds: parsePositiveIntEnv(
      env.UPLOAD_SESSION_TTL_SECONDS,
      10 * 60,
      60,
      3600,
    ),
    verifyLockTtlSeconds: parsePositiveIntEnv(
      env.VERIFY_LOCK_TTL_SECONDS,
      5 * 60,
      30,
      1800,
    ),
    maxPdfBytes: parsePositiveIntEnv(
      env.MAX_PDF_BYTES,
      100 * 1024 * 1024,
      1024,
      500 * 1024 * 1024,
    ),
  };
}

export function isR2Configured(config: ServerConfig): boolean {
  let hasValidEndpoint = false;
  if (config.r2Endpoint && config.r2Endpoint.trim().length > 0) {
    try {
      hasValidEndpoint = Boolean(validateR2Endpoint(config.r2Endpoint));
    } catch {
      return false;
    }
  } else {
    hasValidEndpoint = Boolean(config.r2AccountId?.trim());
  }
  return Boolean(
    hasValidEndpoint &&
      config.r2BucketName &&
      config.r2AccessKeyId &&
      config.r2SecretAccessKey,
  );
}
