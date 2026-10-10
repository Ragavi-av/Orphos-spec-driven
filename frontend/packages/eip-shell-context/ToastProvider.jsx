import { createContext, useContext, useCallback, useMemo, useState } from 'react';
import { Snackbar, Alert } from '@mui/material';

/**
 * App-wide toast/snackbar provider. Exposes a {@code useToast()} hook so any
 * component can fire a transient notification without wiring its own Snackbar.
 *
 * <p>Usage:
 * <pre>
 *   const toast = useToast();
 *   toast.success('Saved successfully.');
 *   toast.error('Could not save.');
 * </pre>
 *
 * <p>A single Snackbar is rendered at the app root; firing a new toast replaces
 * the currently-visible one (last write wins) which keeps the UI calm when
 * several saves complete in quick succession.</p>
 */

const ToastContext = createContext({
  success: () => {},
  error: () => {},
  info: () => {},
  show: () => {},
});

const DEFAULT_DURATION = 3500;

export function ToastProvider({ children }) {
  const [toast, setToast] = useState(null); // { message, severity, duration } | null

  const show = useCallback((message, severity = 'success', duration = DEFAULT_DURATION) => {
    if (!message) return;
    setToast({ message, severity, duration });
  }, []);

  const api = useMemo(() => ({
    show,
    success: (m, d) => show(m, 'success', d),
    error:   (m, d) => show(m, 'error', d),
    info:    (m, d) => show(m, 'info', d),
  }), [show]);

  const handleClose = (_e, reason) => {
    // Ignore click-away so a toast isn't dismissed the instant the user
    // interacts elsewhere on the page.
    if (reason === 'clickaway') return;
    setToast(null);
  };

  return (
    <ToastContext.Provider value={api}>
      {children}
      <Snackbar
        open={!!toast}
        autoHideDuration={toast?.duration ?? DEFAULT_DURATION}
        onClose={handleClose}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {toast ? (
          <Alert
            severity={toast.severity}
            variant='filled'
            onClose={() => setToast(null)}
            sx={{ fontSize: 13 }}
          >
            {toast.message}
          </Alert>
        ) : undefined}
      </Snackbar>
    </ToastContext.Provider>
  );
}

export function useToast() {
  return useContext(ToastContext);
}
