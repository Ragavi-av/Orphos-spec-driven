// Barrel export — most consumers import specific subpaths directly
// (e.g. `eip-shell-context/AuthProvider`) to keep bundler tree-shaking
// simple, but this barrel exists for convenience and as a safety net for
// any bare `import ... from 'eip-shell-context'` usage.
export * from './AuthProvider';
export * from './BrandingProvider';
export * from './ToastProvider';
export * from './DashboardLayoutProvider';
export * from './useRole';
export { default as axiosAuth } from './axiosAuth';
export * from './keycloak';
export * from './tenantResolver';
export * from './actingTenant';
export * from './activeRole';
export * from './apiError';
export * from './catalog';
export * from './datetime';
export * from './locale';
export * from './timeFormat';
export * from './country';
export * from './voice';
export * from './webBff';
export { default as i18n } from './i18n';
export { default as TenantScopeBanner } from './TenantScopeBanner';
export { default as OrphosOrb } from './OrphosOrb';
