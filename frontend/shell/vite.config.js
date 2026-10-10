import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import federation from '@originjs/vite-plugin-federation'

/// <reference types="vitest" />

export default defineConfig({
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
  },
  plugins: [
    react(),
    // Host config. The `remotes` map is the one unavoidable manual edit when
    // a new plugin is added (see the module-federation runbook) — each entry
    // MUST match platform-registry-service's seeded PluginManifest exactly:
    // key = remote_scope, value = remote_entry_path. import()s in
    // src/PluginLoader.jsx must stay static literals ('plugin_x/RemoteApp')
    // for Vite/Rollup to resolve these at build time; only the URL each name
    // maps to is dynamic (dev ports here vs. nginx-proxied paths in prod —
    // both resolve through the /remotes/<id>/ prefix, proxied below in dev
    // and by shell/nginx.conf in prod).
    federation({
      name: 'shell',
      remotes: {
        plugin_ai_copilot:    '/remotes/ai-copilot/assets/remoteEntry.js',
        plugin_email_scanner: '/remotes/email-scanner/assets/remoteEntry.js',
        plugin_doc_ingest:    '/remotes/doc-ingest/assets/remoteEntry.js',
        plugin_rag_search:    '/remotes/rag-search/assets/remoteEntry.js',
        plugin_widgets:       '/remotes/widgets/assets/remoteEntry.js',
        plugin_decision_log:  '/remotes/decision-log/assets/remoteEntry.js',
      },
      // i18next/react-i18next are deliberately NOT shared (unlike the other
      // entries below). @originjs/vite-plugin-federation routes every shared
      // import — including the host's own — through an async
      // `importShared()`-style helper regardless of an `eager` flag (tried
      // that; the generated bundle still awaits it uniformly). That's fine
      // for code that only touches the module inside an async/lazy context,
      // but eip-shell-context/i18n.js calls `i18n.on(...)` as a top-level
      // side effect, which raced that resolution and threw "X.on is not a
      // function" — aborting script execution before React ever mounted (a
      // blank page, no console output beyond that one error). Leaving these
      // two out of `shared` makes every bundle that imports 'i18next' get an
      // ordinary, plain, synchronously-available copy — no federation
      // indirection. Trade-off: remotes' useTranslation() calls use their
      // own uninitialised i18next instance (translated strings may render as
      // raw keys there) until cross-remote i18n is threaded through the
      // `context` prop instead of a federated singleton.
      shared: ['react', 'react-dom', '@mui/material', '@mui/icons-material', '@emotion/react', '@emotion/styled', 'axios', 'eip-shell-context'],
    }),
  ],
  server: {
    port: 3001,
    // Listen on every interface so the per-tenant URLs work even when the SPA
    // is reached via a non-localhost alias (Docker host networking, LAN test).
    host: true,
    proxy: {
      '/api/alerts':    { target: 'http://localhost:8083', changeOrigin: true },
      '/api/priority':  { target: 'http://localhost:8083', changeOrigin: true },
      '/api/briefing':  { target: 'http://localhost:8084', changeOrigin: true },
      '/api/zenitus':   { target: 'http://localhost:8084', changeOrigin: true },
      '/api/nl-query':  { target: 'http://localhost:8082', changeOrigin: true },
      '/api/mobile':    { target: 'http://localhost:8097', changeOrigin: true },
      // web-bff-service (8098): per-screen aggregation for this dashboard.
      '/api/web':       { target: 'http://localhost:8098', changeOrigin: true },
      '/api/decisions': { target: 'http://localhost:8086', changeOrigin: true },
      '/api/agents':    { target: 'http://localhost:8085', changeOrigin: true },
      // agent-orchestrator-service (8085): approval-inbox proxy + event ingress.
      '/api/orchestrator': { target: 'http://localhost:8085', changeOrigin: true },
      '/api/sparql':    { target: 'http://localhost:8080', changeOrigin: true },
      // platform-registry-service (8093): plugin manifests + per-tenant feature flags.
      '/api/platform': { target: 'http://localhost:8093', changeOrigin: true },
      // data-ingestion-service (8096): data sources, mappings, ingestion jobs.
      '/api/data-ingestion': { target: 'http://localhost:8096', changeOrigin: true },
      // admin-service was split into three services (see the admin-service
      // decomposition in the modularization plan). Vite matches the FIRST
      // matching key by insertion order (unlike nginx's longest-prefix
      // match), so the more specific admin-console/widgets paths MUST come
      // before their broader /api/admin and /api/dashboard counterparts.
      '/api/admin/branding':      { target: 'http://localhost:8094', changeOrigin: true },
      '/api/admin/email':         { target: 'http://localhost:8094', changeOrigin: true },
      '/api/admin/query-history': { target: 'http://localhost:8094', changeOrigin: true },
      '/api/admin/events':        { target: 'http://localhost:8094', changeOrigin: true },
      '/api/admin/dashboard/layout': { target: 'http://localhost:8095', changeOrigin: true },
      // platform-core-service (8090): tenant discovery, auth/authz config,
      // onboarding, ops — everything else under /api/admin.
      '/api/admin':     { target: 'http://localhost:8090', changeOrigin: true },
      // Role aliases (tenant-scoped, any authenticated user) — platform-core.
      '/api/roles':     { target: 'http://localhost:8090', changeOrigin: true },
      '/api/public':    { target: 'http://localhost:8090', changeOrigin: true },
      '/api/internal':  { target: 'http://localhost:8090', changeOrigin: true },
      '/api/auth':      { target: 'http://localhost:8090', changeOrigin: true },
      // admin-console-service (8094): branding + email/notification prefs.
      '/api/branding':  { target: 'http://localhost:8094', changeOrigin: true },
      '/api/notifications/prefs': { target: 'http://localhost:8094', changeOrigin: true },
      // widgets-service (8095): per-user dashboard layout. platform-core-service
      // (8090) serves the rest of /api/dashboard (widgets/metrics from onboarding).
      // Without a /api/dashboard entry, vite serves index.html for the call and
      // the SPA reads visibleIds=undefined → "No widgets visible" with no clue why.
      '/api/dashboard/layout': { target: 'http://localhost:8095', changeOrigin: true },
      '/api/dashboard': { target: 'http://localhost:8090', changeOrigin: true },
      // Dev-only stand-in for shell/nginx.conf's prod `/remotes/<id>/` proxy —
      // routes each remote's remoteEntry.js + chunks to its own `vite dev` port
      // so the federation `remotes` map above (a single relative URL space)
      // resolves the same way in dev and prod.
      '/remotes/ai-copilot':    { target: 'http://localhost:5602', changeOrigin: true },
      '/remotes/email-scanner': { target: 'http://localhost:5603', changeOrigin: true },
      '/remotes/doc-ingest':    { target: 'http://localhost:5604', changeOrigin: true },
      '/remotes/rag-search':    { target: 'http://localhost:5605', changeOrigin: true },
      '/remotes/widgets':       { target: 'http://localhost:5606', changeOrigin: true },
      '/remotes/decision-log':  { target: 'http://localhost:5601', changeOrigin: true },
    }
  },
  build: {
    // @originjs/vite-plugin-federation relies on dynamic import()/top-level
    // await for its runtime — requires a modern build target (same as every
    // remote's vite.config.js).
    target: 'esnext',
    outDir: 'dist'
  }
})
