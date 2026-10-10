// The reader's clock: 12-hour (2:30 PM) or 24-hour (14:30).
//
// This belongs on the USER side, beside the language and for the same reason.
// 14:30 and 2:30 PM are the same instant said two ways — the choice changes how
// a time READS, never what it means. Compare the time ZONE, which is an admin
// setting on the tenant's primary country: moving a timestamp from Kolkata to
// Tokyo changes which moment it refers to, so no reader may do it. Notation is
// a preference; meaning is not. See eip-shell-context/country.
//
// Three states, not two:
//
//   'auto'  follow the workspace country's convention — India reads 6:00 am,
//           Japan reads 06:00. The default, and what every surface did before
//           this module existed (except the header clock, which hardcoded
//           24-hour and so disagreed with every other time on the page).
//   '12'    force AM/PM everywhere.
//   '24'    force 24-hour everywhere.
//
// 'auto' earns its place because the admin's country already answers this
// question correctly for most readers; the override is for the ones it does not
// suit, and 'auto' is how they get back.

import { useEffect, useState } from 'react';

export const TIME_FORMATS = ['auto', '12', '24'];

const STORAGE_KEY = 'orphos.timeformat';
const FORMAT_EVENT = 'orphos:timeformatchange';

function normalize(value) {
  return TIME_FORMATS.includes(value) ? value : 'auto';
}

function readStored() {
  // localStorage throws in private-mode Safari and some embedded webviews; the
  // board must still render, just without a remembered preference.
  try { return normalize(localStorage.getItem(STORAGE_KEY)); } catch { return 'auto'; }
}

let _format = readStored();

/** The reader's choice: 'auto' | '12' | '24'. */
export function timeFormat() {
  return _format;
}

/**
 * The preference as Intl's `hour12` option, or undefined for 'auto'.
 *
 * undefined rather than a boolean is deliberate: spreading `{ hour12: undefined }`
 * into an Intl options object is identical to omitting the key, so 'auto' costs
 * the call sites no branching and lets the locale answer, which is exactly what
 * 'auto' means.
 */
export function hour12Options() {
  if (_format === '12') return { hour12: true };
  if (_format === '24') return { hour12: false };
  return {};
}

// Formatting helpers are plain module functions with no React around them
// (datetime.js, meetings.js, mailMonitorUtils.js), so the preference lives in a
// module singleton and components subscribe — the same shape as country.js.
const listeners = new Set();

export function subscribeTimeFormat(fn) {
  listeners.add(fn);
  return () => { listeners.delete(fn); };
}

function applyLocally(value) {
  _format = normalize(value);
  for (const fn of [...listeners]) {
    try { fn(); } catch { /* one bad subscriber must not stop the rest */ }
  }
}

/**
 * Choose a clock format — the single entry point behind the header control.
 *
 * Broadcast over `window` because Module Federation gives the shell and every
 * remote their own copy of this module: without it the dashboard would switch
 * to AM/PM while the meetings list and decision log kept 24-hour until a hard
 * refresh. Same problem, same fix, as the language bridge in ./i18n.js and the
 * country broadcast in ./country.js.
 */
export function setTimeFormat(value) {
  const next = normalize(value);
  if (next === _format) return next;
  try { localStorage.setItem(STORAGE_KEY, next); } catch { /* not fatal */ }
  applyLocally(next);
  try {
    window.dispatchEvent(new CustomEvent(FORMAT_EVENT, { detail: { format: next } }));
  } catch { /* no CustomEvent — this bundle still switched */ }
  return next;
}

if (typeof window !== 'undefined') {
  window.addEventListener(FORMAT_EVENT, (event) => {
    applyLocally(event?.detail?.format);
  });

  // Another tab of the same workspace. `storage` fires only in the OTHER tabs,
  // so a second window follows along without either one reloading.
  window.addEventListener('storage', (event) => {
    if (event.key === STORAGE_KEY) applyLocally(event.newValue);
  });
}

/** React view of the preference: re-renders the caller whenever it changes. */
export function useTimeFormat() {
  const [format, setState] = useState(timeFormat);
  useEffect(() => subscribeTimeFormat(() => setState(timeFormat())), []);
  return { format, setTimeFormat };
}
