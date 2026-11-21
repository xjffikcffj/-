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
      r2Object.contentLength !== null &&
      r2Object.contentLength !== resolved.fileSizeBytes
    ) {
      throw new ApiError(
        409,
        'SIZE_MISMATCH',
        'Stored R2 object size no longer matches verified Firestore metadata.',
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

    try {
      for await (const chunk of r2Object.stream) {
        if (params.res.destroyed) break;
        const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
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
            const cleanup = () => {
              params.res.off('drain', onDrain);
              params.res.off('error', onError);
            };
            params.res.once('drain', onDrain);
            params.res.once('error', onError);
          });
        }
      }
      if (!params.res.writableEnded) {
        params.res.end();
      }
    } catch (streamErr) {
      if (!params.res.headersSent) {
        throw streamErr;
      }
      params.res.destroy(streamErr instanceof Error ? streamErr : undefined);
    }
  }
}
