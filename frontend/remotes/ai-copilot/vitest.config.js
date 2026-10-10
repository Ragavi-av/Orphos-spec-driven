import { defineConfig } from 'vitest/config'

// Kept separate from vite.config.js on purpose: that config carries the Module
// Federation plugin, which rewrites imports for the remote container and has no
// business running under the test runner. Vitest prefers this file when present.
export default defineConfig({
  test: {
    environment: 'jsdom',
    // The default `forks` pool cannot start a worker in this environment (the
    // shell's suite fails the same way, before loading any test file). Threads
    // start fine and these tests need no process isolation.
    pool: 'threads',
    include: ['src/**/*.test.{js,jsx}'],
    globals: true,
    // The first test to import AICopilot.jsx pays for transforming the MUI
    // barrel imports it pulls in, which alone exceeds the 5s/10s defaults.
    testTimeout: 30000,
    hookTimeout: 30000,
  },
})
