// Vite entry point — kept deliberately thin. @originjs/vite-plugin-federation
// needs to finish initialising the shared-dependency scope (react, i18next,
// eip-shell-context, etc.) before any application code that uses those
// dependencies runs. A static import here executes synchronously as soon as
// this chunk loads, racing the federation runtime's own async setup —
// eip-shell-context/i18n's top-level `i18n.on(...)` call was hitting an
// i18next instance the runtime hadn't fully wired up yet ("D.on is not a
// function"), which aborted script execution before React ever mounted
// (a blank page with no console output beyond that one error). The dynamic
// import defers everything to a microtask, after that setup completes — this
// bootstrap split is the documented pattern for vite-plugin-federation hosts.
import('./bootstrap');
