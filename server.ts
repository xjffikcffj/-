import 'dotenv/config';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
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

  const distDir = path.resolve(import.meta.dirname, 'dist');
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

const isMainEntry =
  typeof process !== 'undefined' &&
  Array.isArray(process.argv) &&
  process.argv[1]?.endsWith('server.ts');

if (isMainEntry) {
  startBackendServer();
}
