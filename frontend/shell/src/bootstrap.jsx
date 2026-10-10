import React from 'react';
import ReactDOM from 'react-dom/client';
import { AuthProvider } from 'eip-shell-context/AuthProvider';
import { BrandingProvider } from 'eip-shell-context/BrandingProvider';
import { DashboardLayoutProvider } from 'eip-shell-context/DashboardLayoutProvider';
import { RolesProvider } from 'eip-shell-context/RolesProvider';
import { ToastProvider } from 'eip-shell-context/ToastProvider';
import 'eip-shell-context/axiosAuth';   // register axios interceptors globally
import 'eip-shell-context/i18n';         // initialise i18next (bilingual JA+EN) before render
import { PluginRegistryProvider } from './PluginRegistry';
import App from './App';
import ErrorBoundary from './components/ErrorBoundary/ErrorBoundary';

ReactDOM.createRoot(document.getElementById('root')).render(
  <ErrorBoundary>
    <ToastProvider>
      <AuthProvider>
        <BrandingProvider>
          <RolesProvider>
            <DashboardLayoutProvider>
              <PluginRegistryProvider>
                <App />
              </PluginRegistryProvider>
            </DashboardLayoutProvider>
          </RolesProvider>
        </BrandingProvider>
      </AuthProvider>
    </ToastProvider>
  </ErrorBoundary>
);
