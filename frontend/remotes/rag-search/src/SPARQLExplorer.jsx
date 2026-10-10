import { useState } from 'react';
import { Box, Typography, TextField, Button, Paper, CircularProgress, Alert } from '@mui/material';
import { useTranslation } from 'react-i18next';
import axios from 'axios';

const DEFAULT_QUERY = `SELECT ?subject ?predicate ?object
WHERE {
  ?subject ?predicate ?object
}
LIMIT 10`;

export default function SPARQLExplorer() {
  const { t } = useTranslation();
  const [query, setQuery] = useState(DEFAULT_QUERY);
  const [results, setResults] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const runQuery = () => {
    setLoading(true);
    setError(null);
    axios.post('/api/sparql', query, { headers: { 'Content-Type': 'application/sparql-query', Accept: 'application/sparql-results+json' } })
      .then(res => setResults(res.data))
      .catch(() => setError(t('sparql.unavailable')))
      .finally(() => setLoading(false));
  };

  return (
    <Box p={3}>
      <Typography variant="h5" gutterBottom>{t('sparql.title')}</Typography>
      <TextField
        fullWidth multiline rows={6}
        variant="outlined"
        value={query}
        onChange={e => setQuery(e.target.value)}
        sx={{ fontFamily: 'monospace', mb: 2 }}
      />
      <Button variant="contained" onClick={runQuery} disabled={loading} sx={{ mb: 2 }}>
        {t('sparql.run')}
      </Button>
      {loading && <CircularProgress size={20} sx={{ ml: 2 }} />}
      {error && <Alert severity="warning" sx={{ mb: 2 }}>{error}</Alert>}
      {results && (
        <Paper sx={{ p: 2, overflow: 'auto', maxHeight: 400 }}>
          <pre style={{ margin: 0, fontSize: 12 }}>{JSON.stringify(results, null, 2)}</pre>
        </Paper>
      )}
    </Box>
  );
}
