import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import fs from 'fs';
import path from 'path';
import {defineConfig} from 'vite';

export default defineConfig(({command, mode}) => {
  const isProductionMode =
    mode === 'production' || (command === 'build' && mode !== 'preview-demo');
  const isPreviewDemoEnabled =
    !isProductionMode && process.env.VITE_ENABLE_PREVIEW_DEMO === 'true';

  return {
    define: {
      __HAQIBATI_PREVIEW_DEMO_ENABLED__: JSON.stringify(isPreviewDemoEnabled),
    },
    plugins: [
      react(),
      tailwindcss(),
      // Copies src/assets into dist/ so root-relative URLs like
      // "/src/assets/images/..." (e.g. generate_image output) still resolve
      // in production builds. Do not remove.
      {
        name: 'copy-unbundled-src-assets',
        apply: 'build',
        closeBundle(error?: Error): void {
          if (error) {
            return;
          }
          const distDir = path.resolve(import.meta.dirname, 'dist');
          const srcAssets = path.resolve(import.meta.dirname, 'src/assets');
          const distAssets = path.resolve(distDir, 'src/assets');
          if (fs.existsSync(distDir) && fs.existsSync(srcAssets)) {
            fs.cpSync(srcAssets, distAssets, {recursive: true, force: true});
          }
        },
      },
      {
        name: 'serve-debug-apk-download',
        apply: 'serve',
        configureServer(server) {
          const apkAbsolutePath = path.resolve(
            import.meta.dirname,
            'android/app/build/outputs/apk/debug/app-debug.apk',
          );
          const allowedUrlPaths = new Set([
            '/android/app/build/outputs/apk/debug/app-debug.apk',
            '/download/app-debug.apk',
          ]);

          server.middlewares.use((req, res, next) => {
            const requestPath = req.url?.split('?')[0];
            if (!requestPath || !allowedUrlPaths.has(requestPath)) {
              next();
              return;
            }
            if (req.method !== 'GET' && req.method !== 'HEAD') {
              res.statusCode = 405;
              res.setHeader('Allow', 'GET, HEAD');
              res.end();
              return;
            }
            if (!fs.existsSync(apkAbsolutePath)) {
              res.statusCode = 404;
              res.setHeader('Content-Type', 'text/plain; charset=utf-8');
              res.end('Debug APK not found.');
              return;
            }

            const stat = fs.statSync(apkAbsolutePath);
            res.statusCode = 200;
            res.setHeader(
              'Content-Type',
              'application/vnd.android.package-archive',
            );
            res.setHeader(
              'Content-Disposition',
              'attachment; filename="app-debug.apk"',
            );
            res.setHeader('Content-Length', String(stat.size));
            res.setHeader('X-Content-Type-Options', 'nosniff');
            res.setHeader('Cache-Control', 'no-store');

            if (req.method === 'HEAD') {
              res.end();
              return;
            }

            const stream = fs.createReadStream(apkAbsolutePath);
            stream.on('error', () => {
              if (!res.headersSent) {
                res.statusCode = 500;
                res.end();
              } else {
                res.destroy();
              }
            });
            stream.pipe(res);
          });
        },
      },
    ],
    resolve: {
      alias: {
        '@': path.resolve(import.meta.dirname, '.'),
      },
    },
    server: {
      host: '0.0.0.0',
      port: 3000,
      allowedHosts: true as const,
      // HMR is disabled in AI Studio via DISABLE_HMR env var.
      // Do not modify—file watching is disabled to prevent flickering during agent edits.
      hmr: process.env.DISABLE_HMR !== 'true',
      // Disable file watching when DISABLE_HMR is true to save CPU during agent edits.
      watch: process.env.DISABLE_HMR === 'true' ? null : {},
    },
  };
});
