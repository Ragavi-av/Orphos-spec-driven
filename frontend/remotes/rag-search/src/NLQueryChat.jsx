import { useState } from 'react';
import { Box, Typography, TextField, Button, Paper, List, ListItem, ListItemText, CircularProgress } from '@mui/material';
import { useTranslation } from 'react-i18next';
import axios from 'axios';

export default function NLQueryChat() {
  const { t } = useTranslation();
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(false);

  const ask = () => {
    if (!question.trim()) return;
    const q = question;
    setMessages(prev => [...prev, { role: 'user', text: q }]);
    setQuestion('');
    setLoading(true);
    axios.post('/api/nl-query/ask', { question: q })
      .then(res => setMessages(prev => [...prev, { role: 'assistant', text: res.data.answer || JSON.stringify(res.data), trust: res.data.trust || null }]))
      .catch(() => setMessages(prev => [...prev, { role: 'assistant', text: t('nlQuery.unavailable') }]))
      .finally(() => setLoading(false));
  };

  return (
    <Box p={3} display="flex" flexDirection="column" height="80vh">
      <Typography variant="h5" gutterBottom>{t('nlQuery.title')}</Typography>
      <Paper sx={{ flex: 1, overflow: 'auto', p: 2, mb: 2 }}>
        <List>
          {messages.map((m, i) => (
            <ListItem key={i} sx={{ justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start' }}>
              <Paper sx={{ p: 1, bgcolor: m.role === 'user' ? 'rgba(91, 157, 255, 0.14)' : '#0F2450', maxWidth: '75%' }}>
                <ListItemText primary={m.text} secondary={m.role} />
                {/* Trust badge removed by request — see AICopilot.jsx. `m.trust`
                    is still captured above if it needs to come back. */}
              </Paper>
            </ListItem>
          ))}
        </List>
        {loading && <CircularProgress size={20} />}
      </Paper>
      <Box display="flex" gap={1}>
        <TextField
          fullWidth
          variant="outlined"
          placeholder={t('nlQuery.placeholder')}
          value={question}
          onChange={e => setQuestion(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && ask()}
        />
        <Button variant="contained" onClick={ask} disabled={loading}>{t('nlQuery.ask')}</Button>
      </Box>
    </Box>
  );
}
