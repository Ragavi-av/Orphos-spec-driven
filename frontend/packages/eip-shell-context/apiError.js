/**
 * Turns an axios error into a clear, human-readable message.
 *
 * <p>Every EIP service returns a structured {@code ApiError} envelope
 * ({@code { code, message, timestamp }}) for handled errors, and some
 * controllers return {@code { error }}. This helper prefers that server
 * message, and otherwise maps the HTTP status to something actionable so the
 * UI never collapses to a bare "Upload failed" / "Save failed" with no clue
 * as to why.</p>
 *
 * @param {unknown} e        the caught axios error
 * @param {string}  fallback short action label, e.g. "Upload failed"
 * @returns {string} a message safe to show in an Alert
 */
export function apiErrorMessage(e, fallback = 'Request failed') {
  // No response at all → network / timeout / CORS / server down.
  if (!e?.response) {
    if (e?.code === 'ECONNABORTED') return `${fallback}: the request timed out — please retry.`;
    return `${fallback}: could not reach the server (network error).`;
  }

  const { status, data } = e.response;

  // Prefer the structured server message: ApiError.message, then a plain
  // { error } body. Guard against non-string bodies (e.g. HTML error pages).
  const serverMsg = data && typeof data === 'object'
    ? (data.message || data.error)
    : null;
  if (serverMsg && typeof serverMsg === 'string' && serverMsg.trim()) {
    // Append the status only for server-side faults, where the code aids triage.
    return status >= 500 ? `${serverMsg} (HTTP ${status})` : serverMsg;
  }

  // No usable body — map the status to an actionable message.
  switch (status) {
    case 400: return `${fallback}: the request was rejected (400).`;
    case 401: return `${fallback}: your session has expired — please sign in again (401).`;
    case 403: return `${fallback}: you don't have permission for this action (403).`;
    case 404: return `${fallback}: the resource was not found (404).`;
    case 409: return `${fallback}: conflict with the current state (409).`;
    case 413: return `${fallback}: the file is too large (413).`;
    case 415: return `${fallback}: that file type isn't supported (415).`;
    case 503: return `${fallback}: the service is temporarily unavailable — please retry shortly (503).`;
    default:  return `${fallback}: unexpected server error (HTTP ${status}).`;
  }
}
