import React from 'react';
import { Box, Button, Typography } from '@mui/material';

// React only supports error-boundary semantics on class components — that's
// the contract for getDerivedStateFromError + componentDidCatch. Don't try
// to convert this to hooks.
export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, info) {
    // Forward to the browser console (already collected by promtail via
    // docker stdout if/when we add SPA-side log shipping). Keep this as a
    // single structured line so log parsers can pick it up cleanly.
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary]', error, info?.componentStack);
  }

  handleReload = () => {
    // Hard reload — clears any in-memory React state that may have led here.
    window.location.reload();
  };

  render() {
    if (!this.state.hasError) return this.props.children;

    return (
      <Box
        role="alert"
        sx={{
          minHeight: '100vh',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 2,
          px: 3,
          textAlign: 'center',
          bgcolor: '#0F2450',
        }}
      >
        <Typography variant="h5" sx={{ fontWeight: 700, color: '#FFFFFF' }}>
          Something went wrong.
        </Typography>
        <Typography sx={{ color: '#C3D3F2', maxWidth: 480 }}>
          The dashboard hit an unexpected error and stopped rendering. Reloading
          the page will reset the view; if it keeps happening, contact your
          administrator with the time this occurred.
        </Typography>
        <Button variant="contained" onClick={this.handleReload}>
          Reload page
        </Button>
      </Box>
    );
  }
}
