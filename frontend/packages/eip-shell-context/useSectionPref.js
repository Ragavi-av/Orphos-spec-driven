import { useCallback, useEffect, useRef, useState } from 'react';
import { ORPHOS_MOTION } from './orphosTheme';

// Collapse state for the dashboard's big sections — the morning brief, the
// widget watchlist, the tab panel.
//
// The v3 JP artboard splits this into TWO independent facts, and keeping them
// separate is the whole point of the control:
//
//   open    what the section is doing right now, because the user clicked it
//   always  a pinned DEFAULT — 'expand' or 'collapse' — that outranks `open`
//
// Collapsing them into one boolean is what the plain chevron did, and it loses
// the case the artboard cares about: a user who never wants to see the morning
// brief has to close it again on every load. `always` is the answer, and it is
// why the toggle carries a lock dot when one is set.
//
// Clicking the section itself CLEARS the lock rather than fighting it. A pinned
// "always collapsed" section that silently sprang back shut after being opened
// would read as broken; the artboard's toggle() does the same.

const LS_PREFIX = 'orphos.section.';

// Every hook instance watching the same key, so they stay in step.
//
// Two components legitimately hold the same section: the dashboard's tab strip
// owns the 'brief' key well enough to collapse it (opening the panel minimises
// the morning brief, as the artboard does), while the greeting band renders it.
// Without this registry each instance kept private state, so App collapsing the
// brief left the band still showing it — the write reached localStorage and
// nothing on screen moved until a reload.
const subscribers = new Map();

function publish(key, value, from) {
  const set = subscribers.get(key);
  if (!set) return;
  for (const fn of set) if (fn !== from) fn(value);
}

/** null | 'expand' | 'collapse' — anything else on disk is treated as unset. */
function readPref(key) {
  try {
    const raw = JSON.parse(localStorage.getItem(LS_PREFIX + key) || 'null');
    if (!raw || typeof raw !== 'object') return { always: null, open: null };
    const always = raw.always === 'expand' || raw.always === 'collapse' ? raw.always : null;
    return { always, open: typeof raw.open === 'boolean' ? raw.open : null };
  } catch {
    // Private-mode localStorage throws on read. An unset preference is the
    // correct fallback — the section just behaves as it does on first visit.
    return { always: null, open: null };
  }
}

function writePref(key, value) {
  try {
    localStorage.setItem(LS_PREFIX + key, JSON.stringify(value));
  } catch { /* quota or private mode — the preference is a convenience, not state */ }
}

/**
 * Collapse state + exit-animation gating for one named section.
 *
 * @param {string}  key                 stable id; also the localStorage slot
 * @param {boolean} [defaultOpen=true]  state before the user has expressed one
 * @returns {{
 *   open: boolean,        render the body? (stays true while closing)
 *   closing: boolean,     the exit animation is playing
 *   always: ?string,      'expand' | 'collapse' | null
 *   toggle: function,     what the left half of the split button does
 *   setAlways: function,  what the caret menu's options do
 * }}
 *
 * `open` deliberately stays TRUE for the length of the exit animation. The body
 * has to remain mounted to be animated out; unmounting on click is what made
 * the old sections disappear with no motion at all.
 */
export default function useSectionPref(key, defaultOpen = true) {
  const [pref, setPref] = useState(() => {
    const stored = readPref(key);
    return { always: stored.always, open: stored.open == null ? defaultOpen : stored.open };
  });
  const [closing, setClosing] = useState(false);
  const timer = useRef(null);

  // Join the registry for this key so a write from ANY instance lands here too.
  // `setPref` is stable, which is what lets it double as the identity we filter
  // the publisher's own echo against.
  useEffect(() => {
    let set = subscribers.get(key);
    if (!set) { set = new Set(); subscribers.set(key, set); }
    set.add(setPref);
    return () => {
      set.delete(setPref);
      if (set.size === 0) subscribers.delete(key);
    };
  }, [key]);

  // A section unmounted mid-close (tab switch, route change) would otherwise
  // fire setState on a dead component and leave `closing` stuck for the next
  // mount that reuses the key.
  useEffect(() => () => clearTimeout(timer.current), []);

  const persist = useCallback((next) => {
    setPref(next);
    writePref(key, next);
    publish(key, next, setPref);
  }, [key]);

  // The pinned default outranks the last click; `closing` outranks both, so the
  // body survives long enough to animate away.
  const resolvedOpen = pref.always === 'expand' ? true
    : pref.always === 'collapse' ? false
    : pref.open;

  const toggle = useCallback(() => {
    if (resolvedOpen) {
      setClosing(true);
      clearTimeout(timer.current);
      timer.current = setTimeout(() => {
        setClosing(false);
        // Clearing `always` here is what makes a click win over a pinned
        // default instead of being reverted by it on the next render.
        persist({ always: null, open: false });
      }, ORPHOS_MOTION.exitMs);
      return;
    }
    persist({ always: null, open: true });
  }, [resolvedOpen, persist]);

  const setAlways = useCallback((value) => {
    // 'collapse' has to travel through the same exit animation as a click,
    // otherwise pinning a section shut makes it vanish without motion.
    if (value === 'collapse' && resolvedOpen) {
      setClosing(true);
      clearTimeout(timer.current);
      timer.current = setTimeout(() => {
        setClosing(false);
        persist({ always: 'collapse', open: false });
      }, ORPHOS_MOTION.exitMs);
      return;
    }
    persist({
      always: value,
      open: value === 'expand' ? true : value === 'collapse' ? false : pref.open,
    });
  }, [resolvedOpen, persist, pref.open]);

  return {
    open: resolvedOpen || closing,
    closing,
    always: pref.always,
    toggle,
    setAlways,
  };
}
