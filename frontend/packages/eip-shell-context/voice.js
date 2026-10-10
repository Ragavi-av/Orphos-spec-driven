import { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';

/**
 * Voice (speech-to-text) support for every Ask Orphos affordance.
 *
 * <p>Voice is a SEPARATELY managed platform functionality, not a property of
 * the co-pilot. It is live for a user only when BOTH hold:</p>
 *
 * <ol>
 *   <li>the voice module is running — {@code eip-voice} + its Whisper sidecar
 *       are started, which a platform admin does from Admin → Voice; and</li>
 *   <li>a platform admin has enabled voice FOR THAT TENANT.</li>
 * </ol>
 *
 * <p>{@code GET /api/voice/status} collapses both into one answer for the
 * calling tenant, so the SPA never has to reason about containers. When the
 * voice module is not running the request fails — which is itself the answer
 * ("not available"), so a failure is normal here and must never surface as an
 * error to the user.</p>
 */

const STATUS_URL     = '/api/voice/status';
const TRANSCRIBE_URL = '/api/voice/transcribe';

// One poll shared by every mounted composer. Several Ask Orphos affordances can
// be on screen at once (the dock composer, document search, a greeting card's
// button) and each mounting its own interval would multiply the same request.
let cache = { available: false, enabled: false, loaded: false };
let inflight = null;
const listeners = new Set();

function publish(next) {
  cache = next;
  listeners.forEach(fn => { try { fn(next); } catch { /* a bad subscriber must not stop the rest */ } });
}

function fetchStatus() {
  if (inflight) return inflight;
  inflight = axios.get(STATUS_URL, { timeout: 8000 })
    .then(res => {
      publish({
        available: res.data?.available === true,
        enabled:   res.data?.enabled === true,
        loaded:    true,
      });
    })
    .catch(() => {
      // Voice module down / route absent / tenant not permitted — all mean the
      // same thing to the UI, and none of them is worth a toast.
      publish({ available: false, enabled: false, loaded: true });
    })
    .finally(() => { inflight = null; });
  return inflight;
}

/** Force a re-read — called by the admin tab right after it flips the switch. */
export function refreshVoiceStatus() {
  return fetchStatus();
}

/**
 * Voice availability for the current tenant.
 *
 * @returns {{available:boolean, enabled:boolean, voiceEnabled:boolean, loaded:boolean}}
 *   `voiceEnabled` is the one the UI should branch on: the module is up AND the
 *   tenant is opted in. The two parts are kept separate so the admin tab can
 *   explain WHICH half is missing.
 */
export function useVoiceStatus() {
  const [state, setState] = useState(cache);

  useEffect(() => {
    listeners.add(setState);
    if (!cache.loaded) fetchStatus();
    else setState(cache);
    return () => { listeners.delete(setState); };
  }, []);

  return { ...state, voiceEnabled: state.available && state.enabled };
}

/** The recorder MIME type this browser will actually produce, or '' for its default. */
function pickMimeType() {
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4'];
  if (typeof MediaRecorder === 'undefined') return '';
  return candidates.find(t => {
    try { return MediaRecorder.isTypeSupported(t); } catch { return false; }
  }) || '';
}

/**
 * Microphone dictation: toggle on, toggle off, transcript comes back.
 *
 * <p>Deliberately a TOGGLE rather than press-and-hold. Dictating a question to
 * an assistant is not a walkie-talkie interaction — the user needs both hands
 * and their attention free while speaking, and holding a mouse button down for
 * thirty seconds is neither comfortable nor reachable by keyboard.</p>
 *
 * <p>The transcript is handed to {@code onTranscript} rather than submitted:
 * speech-to-text is never perfect, so the text lands in the composer for the
 * user to correct, and submitting stays an explicit, separate act.</p>
 */
export function useVoiceDictation({ onTranscript, onError } = {}) {
  const [recording,   setRecording]   = useState(false);
  const [transcribing, setTranscribing] = useState(false);

  const recorderRef = useRef(null);
  const chunksRef   = useRef([]);
  const streamRef   = useRef(null);
  // Guards the async gap between "user asked to stop" and the recorder's final
  // dataavailable — without it a fast double-toggle can start a second
  // recording while the first is still flushing.
  const stoppingRef = useRef(false);

  const releaseMic = useCallback(() => {
    // Every track must be stopped or the browser keeps showing the recording
    // indicator (and on some platforms holds the mic open) after we are done.
    streamRef.current?.getTracks().forEach(t => { try { t.stop(); } catch {} });
    streamRef.current = null;
    recorderRef.current = null;
  }, []);

  // Unmounting mid-recording (the dock closes, the user navigates away) must
  // not leave the microphone live.
  useEffect(() => () => {
    try { recorderRef.current?.stop(); } catch {}
    releaseMic();
  }, [releaseMic]);

  const send = useCallback(async (blob) => {
    if (!blob || blob.size === 0) { setTranscribing(false); return; }
    const form = new FormData();
    // Extension matters: Whisper's ffmpeg decode path picks the demuxer from
    // the filename, and a bare "blob" is sniffed as raw PCM and comes back as
    // noise. Derive it from the type the recorder actually produced.
    const ext = blob.type.includes('ogg') ? 'ogg'
              : blob.type.includes('mp4') ? 'm4a'
              : 'webm';
    form.append('audio', blob, `dictation.${ext}`);
    try {
      const res = await axios.post(TRANSCRIBE_URL, form, { timeout: 120000 });
      const text = (res.data?.text || '').trim();
      if (text) onTranscript?.(text);
      else onError?.('No speech was recognised — try again, a little closer to the mic.');
    } catch (err) {
      onError?.(err.response?.data?.error || 'Could not transcribe that. Please try again.');
    } finally {
      setTranscribing(false);
    }
  }, [onTranscript, onError]);

  const start = useCallback(async () => {
    if (recording || stoppingRef.current) return;
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      onError?.('This browser cannot record audio.');
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      const mimeType = pickMimeType();
      const rec = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      chunksRef.current = [];
      rec.ondataavailable = e => { if (e.data?.size > 0) chunksRef.current.push(e.data); };
      rec.onstop = () => {
        const blob = new Blob(chunksRef.current, { type: rec.mimeType || 'audio/webm' });
        chunksRef.current = [];
        releaseMic();
        stoppingRef.current = false;
        setTranscribing(true);
        send(blob);
      };
      recorderRef.current = rec;
      rec.start();
      setRecording(true);
    } catch (err) {
      releaseMic();
      onError?.(err?.name === 'NotAllowedError'
        ? 'Microphone access was blocked. Allow it in your browser to dictate.'
        : 'Could not start recording.');
    }
  }, [recording, onError, releaseMic, send]);

  const stop = useCallback(() => {
    if (!recording || stoppingRef.current) return;
    stoppingRef.current = true;
    setRecording(false);
    try { recorderRef.current?.stop(); } catch { stoppingRef.current = false; releaseMic(); }
  }, [recording, releaseMic]);

  const toggle = useCallback(() => { recording ? stop() : start(); }, [recording, start, stop]);

  return { recording, transcribing, start, stop, toggle };
}

/**
 * Click / double-click disambiguation for one control.
 *
 * <p>The ORB carries two actions: a single click submits, a double click toggles
 * dictation. A double click also delivers TWO `click` events before `dblclick`,
 * so binding the submit straight to `onClick` fires it — twice — on the way to
 * starting a recording. The single-click action is therefore deferred by
 * {@code delay} and cancelled when the second click lands.</p>
 *
 * <p>The cost is a ~220 ms lag on submit-by-click. Enter still submits with no
 * delay at all, which is how the composer is actually driven, and losing the
 * double-click gesture to avoid the lag would cost the voice affordance
 * entirely. When {@code onDoubleClick} is absent there is nothing to wait for,
 * so the single click fires immediately.</p>
 */
export function useClickOrDoubleClick(onClick, onDoubleClick, delay = 320) {
  const timerRef = useRef(null);

  useEffect(() => () => { if (timerRef.current) clearTimeout(timerRef.current); }, []);

  const handleClick = useCallback((e) => {
    if (!onDoubleClick) { onClick?.(e); return; }
    // MouseEvent.detail is the running click count for this burst, as the BROWSER
    // counts it — 2 on the second click of a double, using the platform double-click
    // interval rather than our own guess at it. Relying on the pending timer alone
    // was wrong: a deliberate but unhurried double-click, with more than `delay` ms
    // between the two presses, let the timer fire first and SUBMITTED the draft on
    // the way to starting a recording. Cancelling on detail > 1 closes that window.
    if (e.detail > 1) {
      if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null; }
      return;
    }
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      onClick?.(e);
    }, delay);
  }, [onClick, onDoubleClick, delay]);

  const handleDoubleClick = useCallback((e) => {
    if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null; }
    onDoubleClick?.(e);
  }, [onDoubleClick]);

  return { onClick: handleClick, onDoubleClick: handleDoubleClick };
}
