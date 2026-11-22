import { Buffer } from 'node:buffer';
import { createHash } from 'node:crypto';
import {
  CopyObjectCommand,
  DeleteObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import {
  isR2Configured,
  validateR2Endpoint,
  type ServerConfig,
} from '../config/env.js';
import { ApiError } from '../utils/errors.js';

export interface PresignedPutParams {
  key: string;
  contentType: 'application/pdf';
  contentLength: number;
  expiresInSeconds: number;
}

export interface R2ObjectStreamResult {
  stream: AsyncIterable<Uint8Array>;
  etag: string | null;
  contentLength: number | null;
  contentType: string | null;
}

export interface R2HeadResult {
  exists: boolean;
  contentLength: number | null;
  etag: string | null;
  contentType: string | null;
}

export interface R2CopyIfMatchParams {
  sourceKey: string;
  destinationKey: string;
  expectedETag: string;
  contentType: 'application/pdf';
}

export interface R2StoragePort {
  isConfigured(): boolean;
  createPresignedPutUrl(params: PresignedPutParams): Promise<string>;
  getObjectStreamWithETag(key: string): Promise<R2ObjectStreamResult>;
  copyObjectIfMatch(params: R2CopyIfMatchParams): Promise<{ etag: string | null }>;
  headObject(key: string): Promise<R2HeadResult>;
  deleteObject(key: string): Promise<void>;
}

export function normalizeETag(etag: string | undefined | null): string | null {
  if (!etag) return null;
  const trimmed = etag.trim().replace(/^W\//, '').replace(/^"+|"+$/g, '');
  return trimmed.length > 0 ? trimmed : null;
}

function encodeCopySource(bucket: string, sourceKey: string): string {
  const encodedKey = sourceKey
    .split('/')
    .map((segment) => encodeURIComponent(segment))
    .join('/');
  return `${bucket}/${encodedKey}`;
}

function getHttpStatusCode(err: unknown): number | undefined {
  if (typeof err !== 'object' || err === null) return undefined;
  const meta = (err as { $metadata?: { httpStatusCode?: unknown } }).$metadata;
  return typeof meta?.httpStatusCode === 'number'
    ? meta.httpStatusCode
    : undefined;
}

function getAwsErrorName(err: unknown): string {
  if (typeof err !== 'object' || err === null) return '';
  return String((err as { name?: unknown; Code?: unknown }).name ?? (err as { Code?: unknown }).Code ?? '');
}

export class UnconfiguredR2StorageAdapter implements R2StoragePort {
  isConfigured(): boolean {
    return false;
  }

  private fail(): never {
    throw new ApiError(
      503,
      'R2_NOT_CONFIGURED',
      'Cloudflare R2 storage credentials are not configured on this server.',
    );
  }

  async createPresignedPutUrl(): Promise<string> {
    this.fail();
  }

  async getObjectStreamWithETag(): Promise<R2ObjectStreamResult> {
    this.fail();
  }

  async copyObjectIfMatch(): Promise<{ etag: string | null }> {
    this.fail();
  }

  async headObject(): Promise<R2HeadResult> {
    this.fail();
  }

  async deleteObject(): Promise<void> {
    this.fail();
  }
}

const SAFE_R2_ACCOUNT_ID_REGEX = /^[A-Za-z0-9_-]{1,128}$/;

export function resolveR2Endpoint(config: ServerConfig): string {
  const validatedExplicit = validateR2Endpoint(config.r2Endpoint);
  if (validatedExplicit) {
    return validatedExplicit;
  }
  const accountId = config.r2AccountId?.trim() ?? '';
  if (!SAFE_R2_ACCOUNT_ID_REGEX.test(accountId)) {
    throw new Error('Invalid R2_ACCOUNT_ID: malformed Cloudflare R2 account identifier.');
  }
  return `https://${accountId}.r2.cloudflarestorage.com`;
}

export class S3R2StorageAdapter implements R2StoragePort {
  private readonly client: S3Client;
  private readonly bucket: string;

  constructor(config: ServerConfig) {
    if (!isR2Configured(config) || !config.r2BucketName) {
      throw new Error('Cannot construct S3R2StorageAdapter without R2 configuration.');
    }
    const endpoint = resolveR2Endpoint(config);

    this.bucket = config.r2BucketName;
    this.client = new S3Client({
      region: 'auto',
      endpoint,
      credentials: {
        accessKeyId: config.r2AccessKeyId!,
        secretAccessKey: config.r2SecretAccessKey!,
      },
      forcePathStyle: true,
    });
  }

  isConfigured(): boolean {
    return true;
  }

  async createPresignedPutUrl(params: PresignedPutParams): Promise<string> {
    const command = new PutObjectCommand({
      Bucket: this.bucket,
      Key: params.key,
      ContentType: params.contentType,
      ContentLength: params.contentLength,
    });
    return getSignedUrl(this.client, command, {
      expiresIn: params.expiresInSeconds,
    });
  }

  async getObjectStreamWithETag(key: string): Promise<R2ObjectStreamResult> {
    try {
      const response = await this.client.send(
        new GetObjectCommand({
          Bucket: this.bucket,
          Key: key,
        }),
      );
      if (!response.Body) {
        throw new ApiError(
          404,
          'R2_OBJECT_MISSING',
          `R2 object '${key}' returned an empty body.`,
        );
      }
      return {
        stream: response.Body as AsyncIterable<Uint8Array>,
        etag: normalizeETag(response.ETag),
        contentLength:
          typeof response.ContentLength === 'number'
            ? response.ContentLength
            : null,
        contentType: response.ContentType ?? null,
      };
    } catch (err) {
      if (err instanceof ApiError) throw err;
      const status = getHttpStatusCode(err);
      const name = getAwsErrorName(err);
      if (status === 404 || name === 'NoSuchKey' || name === 'NotFound') {
        throw new ApiError(
          404,
          'R2_OBJECT_MISSING',
          `R2 object '${key}' was not found.`,
        );
      }
      throw err;
    }
  }

  async copyObjectIfMatch(
    params: R2CopyIfMatchParams,
  ): Promise<{ etag: string | null }> {
    try {
      const response = await this.client.send(
        new CopyObjectCommand({
          Bucket: this.bucket,
          Key: params.destinationKey,
          CopySource: encodeCopySource(this.bucket, params.sourceKey),
          CopySourceIfMatch: params.expectedETag,
          ContentType: params.contentType,
          MetadataDirective: 'REPLACE',
        }),
      );
      return {
        etag: normalizeETag(response.CopyObjectResult?.ETag),
      };
    } catch (err) {
      const status = getHttpStatusCode(err);
      const name = getAwsErrorName(err);
      if (status === 412 || name === 'PreconditionFailed') {
        throw new ApiError(
          412,
          'ETAG_MISMATCH',
          'Staging object changed concurrently during conditional copy.',
        );
      }
      if (status === 404 || name === 'NoSuchKey') {
        throw new ApiError(
          404,
          'R2_OBJECT_MISSING',
          'Staging object disappeared before copy.',
        );
      }
      throw new ApiError(
        502,
        'COPY_FAILED',
        'Failed to copy verified staging object into immutable key.',
      );
    }
  }

  async headObject(key: string): Promise<R2HeadResult> {
    try {
      const response = await this.client.send(
        new HeadObjectCommand({
          Bucket: this.bucket,
          Key: key,
        }),
      );
      return {
        exists: true,
        contentLength:
          typeof response.ContentLength === 'number'
            ? response.ContentLength
            : null,
        etag: normalizeETag(response.ETag),
        contentType: response.ContentType ?? null,
      };
    } catch (err) {
      const status = getHttpStatusCode(err);
      const name = getAwsErrorName(err);
      if (status === 404 || name === 'NotFound' || name === 'NoSuchKey') {
        return {
          exists: false,
          contentLength: null,
          etag: null,
          contentType: null,
        };
      }
      throw err;
    }
  }

  async deleteObject(key: string): Promise<void> {
    await this.client.send(
      new DeleteObjectCommand({
        Bucket: this.bucket,
        Key: key,
      }),
    );
  }
}

export function createR2StorageAdapter(config: ServerConfig): R2StoragePort {
  return isR2Configured(config)
    ? new S3R2StorageAdapter(config)
    : new UnconfiguredR2StorageAdapter();
}

const PDF_MAGIC_PREFIX = Buffer.from('%PDF-', 'ascii');

export async function verifyPdfStreamIntegrity(
  stream: AsyncIterable<Uint8Array>,
  expectations: {
    expectedSizeBytes: number;
    expectedSha256: string;
    maxPdfBytes: number;
  },
): Promise<{ actualSizeBytes: number; actualSha256: string }> {
  const hash = createHash('sha256');
  const headerChunks: Buffer[] = [];
  let headerBytesCollected = 0;
  let totalBytes = 0;

  for await (const rawChunk of stream) {
    const chunk = Buffer.isBuffer(rawChunk) ? rawChunk : Buffer.from(rawChunk);
    if (chunk.length === 0) continue;

    totalBytes += chunk.length;
    if (
      totalBytes > expectations.maxPdfBytes ||
      totalBytes > expectations.expectedSizeBytes
    ) {
      throw new ApiError(
        422,
        'SIZE_MISMATCH',
        `Uploaded PDF byte length exceeds expected ${expectations.expectedSizeBytes} bytes.`,
        {
          expectedSizeBytes: expectations.expectedSizeBytes,
          observedAtLeastBytes: totalBytes,
        },
      );
    }

    if (headerBytesCollected < 5) {
      const needed = 5 - headerBytesCollected;
      headerChunks.push(chunk.subarray(0, needed));
      headerBytesCollected += Math.min(needed, chunk.length);
      if (headerBytesCollected === 5) {
        const header = Buffer.concat(headerChunks);
        if (!header.equals(PDF_MAGIC_PREFIX)) {
          throw new ApiError(
            422,
            'INVALID_PDF_HEADER',
            'Uploaded binary does not start with valid %PDF- signature.',
          );
        }
      }
    }

    hash.update(chunk);
  }

  if (headerBytesCollected < 5) {
    throw new ApiError(
      422,
      'INVALID_PDF_HEADER',
      'Uploaded binary is too small to contain a valid %PDF- header.',
    );
  }

  const header = Buffer.concat(headerChunks);
  if (!header.equals(PDF_MAGIC_PREFIX)) {
    throw new ApiError(
      422,
      'INVALID_PDF_HEADER',
      'Uploaded binary does not start with valid %PDF- signature.',
    );
  }

  if (totalBytes !== expectations.expectedSizeBytes) {
    throw new ApiError(
      422,
      'SIZE_MISMATCH',
      `Uploaded PDF size (${totalBytes}) does not match expected size (${expectations.expectedSizeBytes}).`,
      {
        expectedSizeBytes: expectations.expectedSizeBytes,
        actualSizeBytes: totalBytes,
      },
    );
  }

  const actualSha256 = hash.digest('hex').toLowerCase();
  if (actualSha256 !== expectations.expectedSha256.toLowerCase()) {
    throw new ApiError(
      422,
      'SHA256_MISMATCH',
      'Uploaded PDF SHA-256 checksum does not match expected digest.',
      {
        expectedSha256: expectations.expectedSha256.toLowerCase(),
        actualSha256,
      },
    );
  }

  return {
    actualSizeBytes: totalBytes,
    actualSha256,
  };
}
