import React from 'react';
import ReactDOM from 'react-dom/client';
import RemoteApp from './RemoteApp';

// Standalone dev-preview entry only — `npm run dev` in this package renders
// the plugin in isolation with a minimal stub context, so a team can iterate
// on it without running the full shell. Production loads RemoteApp via
// Module Federation instead (see shell/src/PluginLoader.jsx), never this file.
ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <RemoteApp tabKey="decision-log" slot={null} context={{}} />
  </React.StrictMode>
);
