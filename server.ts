import 'dotenv/config';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import express from 'express';
import { createBackendApp } from './server/app.js';
import { loadServerConfig } from './server/config/env.js';
import { createFirebaseAdminServices } from './server/firebase/adminApp.js';
import { createR2StorageAdapter } from './server/storage/r2Storage.js';

export function startBackendServer() {
  const config = loadServerConfig();
  const { firestore, idTokenVerifier } = createFirebaseAdminServices(config);
  const r2Storage = createR2StorageAdapter(config);

  const app = createBackendApp({
    config,
    firestore,
    idTokenVerifier,
    r2Storage,
  });

  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const distDir = path.resolve(currentDir, 'dist');
  const distIndexPath = path.join(distDir, 'index.html');
  if (fs.existsSync(distIndexPath)) {
    app.use(express.static(distDir));
    app.get('*', (req, res, next) => {
      if (req.path.startsWith('/api')) {
        next();
        return;
      }
      res.sendFile(distIndexPath);
    });
  }

  const server = app.listen(config.port, '0.0.0.0', () => {
    console.log(
      `Haqibati backend API listening on port ${config.port} (R2 configured: ${r2Storage.isConfigured()})`,
    );
  });

  return server;
}

const KNOWN_ENTRYPOINT_EXTENSIONS = [
  '.ts',
  '.mts',
  '.cts',
  '.js',
  '.mjs',
  '.cjs',
] as const;

function canonicalizeFsPath(targetPath: string, cwd: string): string {
  const resolved = targetPath.startsWith('file://')
    ? fileURLToPath(targetPath)
    : path.resolve(cwd, targetPath);
  try {
    return fs.realpathSync(resolved);
  } catch {
    return path.normalize(resolved);
  }
}

export interface MainEntryOptions {
  entryArg?: string;
  moduleUrl?: string;
  cwd?: string;
}

export function isMainEntry(options: MainEntryOptions = {}): boolean {
  const cwd =
    options.cwd ?? (typeof process !== 'undefined' ? process.cwd() : '/');
  const rawModuleUrl =
    options.moduleUrl ??
    (typeof import.meta !== 'undefined' ? import.meta.url : undefined);
  const rawEntryArg =
    options.entryArg !== undefined
      ? options.entryArg
      : typeof process !== 'undefined' && Array.isArray(process.argv)
        ? process.argv[1]
        : undefined;

  if (
    typeof rawModuleUrl !== 'string' ||
    rawModuleUrl.trim().length === 0 ||
    typeof rawEntryArg !== 'string' ||
    rawEntryArg.trim().length === 0
  ) {
    return false;
  }

  let canonicalModulePath: string;
  let canonicalEntryPath: string;
  try {
    canonicalModulePath = canonicalizeFsPath(rawModuleUrl.trim(), cwd);
    canonicalEntryPath = canonicalizeFsPath(rawEntryArg.trim(), cwd);
  } catch {
    return false;
  }

  if (canonicalEntryPath === canonicalModulePath) {
    return true;
  }

  // Handle extensionless CLI entrypoints such as `tsx server` or `tsx ./server`.
  if (path.extname(canonicalEntryPath) === '') {
    for (const ext of KNOWN_ENTRYPOINT_EXTENSIONS) {
      const candidatePath = canonicalizeFsPath(
        `${canonicalEntryPath}${ext}`,
        cwd,
      );
      if (candidatePath === canonicalModulePath) {
        return true;
      }
    }
  }

  return false;
}

if (isMainEntry()) {
  startBackendServer();
}
