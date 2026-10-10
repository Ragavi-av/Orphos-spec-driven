import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import federation from '@originjs/vite-plugin-federation'

// name/exposes MUST match platform-registry-service's seeded PluginManifest
// row for pluginId "decision-log" (remoteScope: "plugin_decision_log") — see
// services/platform-registry-service/src/main/resources/data.sql.
export default defineConfig({
  plugins: [
    react(),
    federation({
      name: 'plugin_decision_log',
      filename: 'remoteEntry.js',
      exposes: {
        './RemoteApp': './src/RemoteApp.jsx',
      },
      // Shared as singletons so a remote's calls into eip-shell-context (axios
      // interceptors set up once in the shell, ToastProvider/DashboardLayoutProvider
      // React context) hit the SAME instance the shell created — without this
      // each remote would bundle its own copy and silently lose auth headers /
      // toast wiring. i18next/react-i18next are deliberately NOT shared — see
      // frontend/shell/vite.config.js for why (top-level side effects in
      // eip-shell-context/i18n.js race the federation runtime's async shared-
      // module resolution and throw "X.on is not a function").
      shared: ['react', 'react-dom', '@mui/material', '@mui/icons-material', '@emotion/react', '@emotion/styled', 'axios', 'eip-shell-context'],
    }),
  ],
  // @originjs/vite-plugin-federation relies on dynamic import()/top-level
  // await for the federation runtime — requires a modern build target.
  build: {
    target: 'esnext',
    minify: false,
    cssCodeSplit: false,
    outDir: 'dist',
  },
  server: {
    port: 5601,
  },
})
