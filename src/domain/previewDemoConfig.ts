export interface PreviewDemoEnvInput {
  command: 'serve' | 'build';
  mode: string;
  nodeEnv?: string;
  enablePreviewDemoFlag?: string;
}

/**
 * Preview demo credentials are strictly an explicit dev-preview opt-in and must NEVER
 * be included in production builds even if an outer shell defines `NODE_ENV=development`.
 */
export function resolvePreviewDemoEnabled(input: PreviewDemoEnvInput): boolean {
  const normalizedMode = input.mode.trim();
  const isProductionMode =
    normalizedMode === 'production' ||
    (input.command === 'build' && normalizedMode !== 'preview-demo');

  if (isProductionMode) {
    return false;
  }

  return input.enablePreviewDemoFlag === 'true';
}
