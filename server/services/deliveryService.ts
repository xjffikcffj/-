import { Buffer } from 'node:buffer';
import type { Response } from 'express';
import type { R2StoragePort } from '../storage/r2Storage.js';
import { ApiError } from '../utils/errors.js';
import type { CatalogService } from './catalogService.js';

export type DeliveryMode = 'stream' | 'download';

function buildSafeAsciiPdfFilename(fileId: string, versionNumber: number): string {
  const safeBase = fileId.replace(/[^a-zA-Z0-9_-]/g, '_');
  return `${safeBase}_v${versionNumber}.pdf`;
}

async function closeUpstreamStream(stream: unknown): Promise<void> {
  if (!stream || typeof stream !== 'object') return;
  const maybeDestroy = (stream as { destroy?: () => void }).destroy;
  if (typeof maybeDestroy === 'function') {
    try {
      maybeDestroy.call(stream);
    } catch {
      // ignore stream cleanup failure
    }
  }
  const maybeCancel = (stream as { cancel?: () => Promise<void> | void }).cancel;
  if (typeof maybeCancel === 'function') {
    try {
      await maybeCancel.call(stream);
    } catch {
      // ignore stream cancel failure
    }
  }
  const maybeReturn = (
    stream as { return?: () => Promise<unknown> | unknown }
  ).return;
  if (typeof maybeReturn === 'function') {
    try {
      await maybeReturn.call(stream);
    } catch {
      // ignore iterator return failure
    }
  }
}

export class DeliveryService {
  constructor(
    private readonly catalogService: CatalogService,
    private readonly r2Storage: R2StoragePort,
  ) {}

  async deliverPublishedPdf(params: {
    fileId: string;
    versionId: string;
    mode: DeliveryMode;
    res: Response;
  }): Promise<void> {
    if (!this.r2Storage.isConfigured()) {
      throw new ApiError(
        503,
        'R2_NOT_CONFIGURED',
        'Cloudflare R2 storage is not configured yet.',
      );
    }

    // Live 5-level verification (branch -> subject -> section -> study_file -> version)
    const resolved = await this.catalogService.resolvePublishedHierarchyForVersion(
      params.fileId,
      params.versionId,
    );

    const r2Object = await this.r2Storage.getObjectStreamWithETag(
      resolved.r2ObjectKey,
    );

    if (
      typeof r2Object.contentLength !== 'number' ||
      !Number.isInteger(r2Object.contentLength) ||
      r2Object.contentLength !== resolved.fileSizeBytes
    ) {
      await closeUpstreamStream(r2Object.stream);
      throw new ApiError(
        409,
        'SIZE_MISMATCH',
        'Stored R2 object size is missing or no longer matches verified Firestore metadata.',
      );
    }

    const filename = buildSafeAsciiPdfFilename(
      resolved.fileId,
      resolved.versionNumber,
    );
    const disposition =
      params.mode === 'download'
        ? `attachment; filename="${filename}"`
        : `inline; filename="${filename}"`;

    params.res.status(200);
    params.res.setHeader('Content-Type', 'application/pdf');
    params.res.setHeader('Content-Length', String(resolved.fileSizeBytes));
    params.res.setHeader('X-Content-Sha256', resolved.checksumSha256);
    params.res.setHeader('Content-Disposition', disposition);
    params.res.setHeader('Cache-Control', 'private, no-store, max-age=0');
    params.res.setHeader('X-Content-Type-Options', 'nosniff');

    let clientAborted = false;
    let bytesSent = 0;
    let onBackpressureAbort: (() => void) | null = null;

    const onClientClose = () => {
      if (!params.res.writableEnded) {
        clientAborted = true;
        onBackpressureAbort?.();
        void closeUpstreamStream(r2Object.stream);
      }
    };

    params.res.once('close', onClientClose);

    try {
      for await (const chunk of r2Object.stream) {
        if (
          clientAborted ||
          params.res.destroyed ||
          params.res.writableEnded
        ) {
          clientAborted = true;
          break;
        }
        const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        if (buf.length === 0) continue;

        bytesSent += buf.length;
        if (bytesSent > resolved.fileSizeBytes) {
          throw new Error(
            `R2 stream byte count (${bytesSent}) exceeded verified fileSizeBytes (${resolved.fileSizeBytes}).`,
          );
        }

        if (!params.res.write(buf)) {
          await new Promise<void>((resolve, reject) => {
            const onDrain = () => {
              cleanup();
              resolve();
            };
            const onError = (err: Error) => {
              cleanup();
              reject(err);
            };
            const onCloseWhilePaused = () => {
              cleanup();
              clientAborted = true;
              resolve();
            };
            const cleanup = () => {
              onBackpressureAbort = null;
              params.res.off('drain', onDrain);
              params.res.off('error', onError);
              params.res.off('close', onCloseWhilePaused);
            };
            onBackpressureAbort = onCloseWhilePaused;
            params.res.once('drain', onDrain);
            params.res.once('error', onError);
            params.res.once('close', onCloseWhilePaused);

            if (
              clientAborted ||
              params.res.destroyed ||
              params.res.writableEnded
            ) {
              onCloseWhilePaused();
            }
          });
        }
      }

      if (clientAborted || params.res.destroyed) {
        return;
      }

      if (bytesSent !== resolved.fileSizeBytes) {
        params.res.destroy(
          new Error(
            `R2 stream truncated at ${bytesSent} bytes (expected ${resolved.fileSizeBytes}).`,
          ),
        );
        return;
      }

      if (!params.res.writableEnded) {
        params.res.end();
      }
    } catch (streamErr) {
      if (!params.res.headersSent) {
        throw streamErr;
      }
      if (!params.res.destroyed) {
        params.res.destroy(streamErr instanceof Error ? streamErr : undefined);
      }
    } finally {
      onBackpressureAbort = null;
      params.res.off('close', onClientClose);
      await closeUpstreamStream(r2Object.stream);
    }
  }
}
