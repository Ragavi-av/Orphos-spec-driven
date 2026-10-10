import axios from 'axios';
import { formatDate, formatDateTime } from 'eip-shell-context/datetime';

// All calls go through the shell's nginx (or Vite dev proxy) to
// decision-log-service:8086. Auth headers come from the shell's global
// axios interceptor (eip-shell-context/axiosAuth).
const BASE = '/api/decisions';

export const listDecisions = (params) => axios.get(BASE, { params }).then(r => r.data);
export const getDecision = (id) => axios.get(`${BASE}/${id}`).then(r => r.data);
export const createDecision = (payload) => axios.post(BASE, payload).then(r => r.data);
export const updateDecision = (id, payload) => axios.put(`${BASE}/${id}`, payload).then(r => r.data);
export const changeStatus = (id, body) => axios.patch(`${BASE}/${id}/status`, body).then(r => r.data);
export const updateMilestones = (id, milestones) =>
  axios.patch(`${BASE}/${id}/milestones`, { milestones }).then(r => r.data);
export const recordOutcome = (id, outcome) =>
  axios.post(`${BASE}/${id}/outcome`, { outcome }).then(r => r.data);
export const getTimeline = (id) => axios.get(`${BASE}/${id}/timeline`).then(r => r.data);
export const getCorrelations = (id) => axios.get(`${BASE}/${id}/correlations`).then(r => r.data);
export const getRelated = (id) => axios.get(`${BASE}/${id}/related`).then(r => r.data);
export const getAnnotation = (id, refresh = false) =>
  axios.get(`${BASE}/${id}/annotation`, { params: refresh ? { refresh: true } : {} }).then(r => r.data);
export const predictImpact = (id) => axios.post(`${BASE}/${id}/impact`).then(r => r.data);
export const searchDecisions = (body) => axios.post(`${BASE}/search`, body).then(r => r.data);
export const getAnalytics = () => axios.get(`${BASE}/analytics`).then(r => r.data);
export const getAreas = () => axios.get(`${BASE}/areas`).then(r => r.data);
export const parseDocumentText = (content) =>
  axios.post(`${BASE}/documents/parse`, { content }).then(r => r.data);
export const parseDocumentFile = (file) => {
  const form = new FormData();
  form.append('file', file);
  return axios.post(`${BASE}/documents/parse`, form).then(r => r.data);
};
export const importDocumentText = (content) =>
  axios.post(`${BASE}/documents/import`, { content }).then(r => r.data);
export const importDocumentFile = (file) => {
  const form = new FormData();
  form.append('file', file);
  return axios.post(`${BASE}/documents/import`, form).then(r => r.data);
};
export const syncNow = () => axios.post(`${BASE}/admin/sync`).then(r => r.data);

export const exportDecisionUrl = (id, format) => `${BASE}/${id}/export?format=${format}`;
export const exportAnalyticsUrl = (format) => `${BASE}/analytics/export?format=${format}`;

export const PRIORITY_COLOR = {
  CRITICAL: 'error', HIGH: 'warning', MEDIUM: 'info', LOW: 'default',
};
export const STATUS_COLOR = {
  DETECTED: 'secondary', PENDING: 'warning', APPROVED: 'success', REJECTED: 'error',
};
export const IMPL_COLOR = {
  NOT_STARTED: 'default', IN_PROGRESS: 'info', ON_HOLD: 'warning', COMPLETED: 'success',
};

// Formatted in the APP's language and the tenant's zone, not the browser's:
// a bare toLocaleString() reads the browser locale, which left English dates
// down the decision log on a Japanese board.
export const fmtDate = (iso) => (iso ? formatDateTime(iso, { fallback: iso }) : '');
export const fmtDay = (iso) => (iso ? formatDate(iso, { fallback: iso }) : '');
