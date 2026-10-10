import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import federation from '@originjs/vite-plugin-federation'

// name/exposes MUST match platform-registry-service's seeded PluginManifest
// row for pluginId "doc-ingest" (remoteScope: "plugin_doc_ingest") � see
// services/platform-registry-service/src/main/resources/data.sql.
export default defineConfig({
  plugins: [
    react(),
    federation({
      name: 'plugin_doc_ingest',
      filename: 'remoteEntry.js',
      exposes: {
        './RemoteApp': './src/RemoteApp.jsx',
      },
      // See decision-log/vite.config.js for why axios/eip-shell-context must be
      // shared singletons, and why i18next/react-i18next deliberately are NOT.
      shared: ['react', 'react-dom', '@mui/material', '@mui/icons-material', '@emotion/react', '@emotion/styled', 'axios', 'eip-shell-context'],
    }),
  ],
  build: {
    target: 'esnext',
    minify: false,
    cssCodeSplit: false,
    outDir: 'dist',
  },
  server: {
    port: 5604,
  },
})