import React from 'react';
import ReactDOM from 'react-dom/client';
import RemoteApp from './RemoteApp';

// Standalone dev-preview entry only — see decision-log/src/main.jsx for why
// this never runs in production (Module Federation loads RemoteApp instead).
ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <RemoteApp slot="top-widgets" context={{}} />
  </React.StrictMode>
);
