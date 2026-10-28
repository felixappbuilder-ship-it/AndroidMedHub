// scripts/notification/planner.js

/**
 * Schedules native notifications for MedVix planner items.
 *
 * Reads directly from localStorage (the same keys performance.js uses)
 * so this module can be called from app.js bootstrap without importing
 * page-level code.
 *
 * ─── TIMING SPEC ────────────────────────────────────────────────────
 *
 *   EXAMS
 *     Fires 24 hours before the exam, at 19:00 local time.
 *     Math: triggerAt = (examDate@19:00 local) − 24h
 *     Skipped if:
 *       • exam date is unparseable / missing
 *       • trigger is already in the past
 *       • exam is today or earlier
 *
 *   TIMETABLE SESSIONS
 *     Fires 15 minutes before each session start.
 *     Scans today plus the next 6 days (7-day rolling window).
 *     Math: triggerAt = (sessionStart@HH:MM local) − 15min
 *     Skipped if:
 *       • time string is missing or unparseable
 *       • trigger is already in the past
 *       • session is more than 7 days out (rescheduled on next sync)
 *
 * ─── IDEMPOTENCY ────────────────────────────────────────────────────
 *
 *   IDs are stable hashes of reminder content:
 *     exam      → "1100000 + hash(name|date)  % 100000"
 *     timetable → "1200000 + hash(date|time|subject) % 100000"
 *
 *   Same content → same ID → schedule() replaces the previous alarm
 *   in place. Content changed → new ID, old alarm cancelled as orphan.
 *
 *   The set of currently-scheduled IDs is persisted to
 *   localStorage["medvix_scheduled_notif_ids"] so subsequent syncs
 *   can cancel orphaned reminders (exam deleted, session removed,
 *   date moved).
 *
 * ─── SYNC FREQUENCY ─────────────────────────────────────────────────
 *
 *   syncNativeReminders() is cheap (< 5 ms, no network) and safe to
 *   call frequently. Recommended call sites:
 *     • app.js bootstrap()                 (cold-start safety net)
 *     • performance.js init()              (page open)
 *     • performance.js addTimetableSlot()  (after data mutation)
 *     • performance.js addExamCountdown()  (after data mutation)
 *     • performance.js adoptAIPlan()       (after AI plan mutation)
 *     • performance.js deleteTimetableSlot (after data mutation)
 *
 * ─── STORAGE KEYS ───────────────────────────────────────────────────
 *
 *   medvix_planner_exams              — array of {name, date}
 *   medvix_planner_timetable          — object {Monday:[{time, subject}], …}
 *   medvix_scheduled_notif_ids        — array of currently-scheduled IDs
 *
 *   These match performance.js LS_KEYS. If those keys change, update
 *   here too.
 */

import * as utils from '../utils.js';
import * as nativeNotifications from './native.js';

const LS_EXAMS         = 'medvix_planner_exams';
const LS_TIMETABLE     = 'medvix_planner_timetable';
const LS_SCHEDULED_IDS = 'medvix_scheduled_notif_ids';

// ── Timing constants (change here to adjust the whole system) ──
const EXAM_NOTIFY_HOUR_LOCAL  = 19;    // 19:00 local = 7 PM
const EXAM_LEAD_MS            = 24 * 60 * 60 * 1000;  // 24 hours
const SESSION_LEAD_MS         = 15 * 60 * 1000;       // 15 minutes
const SCAN_DAYS               = 7;                    // rolling window

// ── Routes to open on tap ──
const ROUTE_EXAM      = '/performance';
const ROUTE_TIMETABLE = '/performance';

// ── ID namespacing ──
//   prefix * 100_000 + (hash % 100_000)  →  100_000 usable IDs per type
const ID_PREFIX_EXAM      = 11;
const ID_PREFIX_TIMETABLE = 12;

const DAYS_OF_WEEK = [
  'Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday',
];

// ── ID derivation ──
function stableId(prefix, key) {
  let h = 0;
  for (let i = 0; i < key.length; i++) {
    h = (h * 31 + key.charCodeAt(i)) >>> 0;
  }
  return prefix * 100000 + (h % 100000);
}

// ── Storage helpers ──
function loadJson(key, fallback) {
  try {
    const raw = utils.getLocalStorage(key, null);
    return raw
      ? (typeof raw === 'string' ? JSON.parse(raw) : raw)
      : fallback;
  } catch {
    return fallback;
  }
}

/**
 * Parse an "HH:MM" or "H:MM AM/PM" string into a Date on the given
 * reference day. Returns 0 for unparseable input.
 *
 * The reference day is expected to have its date fields set (year,
 * month, day) — we only override hours/minutes.
 */
function parseTimeToMs(timeStr, dateRef) {
  if (!timeStr) return 0;
  const m = String(timeStr).match(/^(\d{1,2}):(\d{2})\s*(AM|PM)?$/i);
  if (!m) return 0;

  let h = parseInt(m[1], 10);
  const min = parseInt(m[2], 10);
  const ampm = m[3]?.toUpperCase();
  if (ampm === 'PM' && h < 12) h += 12;
  if (ampm === 'AM' && h === 12) h = 0;

  const d = new Date(dateRef);
  d.setHours(h, min, 0, 0);
  return d.getTime();
}

/**
 * Compute the trigger time for an exam reminder.
 * Returns 0 when the trigger is invalid or in the past.
 */
function computeExamTrigger(examDateStr, nowMs) {
  if (!examDateStr) return 0;

  // Build a Date at 19:00 local on the day before the exam.
  const examDay = new Date(examDateStr + 'T' + String(EXAM_NOTIFY_HOUR_LOCAL).padStart(2, '0') + ':00:00');
  if (isNaN(examDay.getTime())) return 0;

  const triggerAt = examDay.getTime() - EXAM_LEAD_MS;
  if (!Number.isFinite(triggerAt) || triggerAt <= nowMs) return 0;
  return triggerAt;
}

// ── Sync ──

/**
 * Reconcile scheduled native notifications with current planner data.
 * Safe to call on every boot, page open, or data mutation.
 *
 * @returns {{ scheduled: number, cancelled: number, skipped?: string }}
 */
export function syncNativeReminders() {
  if (!nativeNotifications.isSupported()) {
    return { scheduled: 0, cancelled: 0, skipped: 'bridge-unavailable' };
  }
  if (!nativeNotifications.hasPermission()) {
    // Still schedule — the reminder will fire only if permission is
    // granted by trigger time. Native side checks at fire time.
  }

  const exams     = loadJson(LS_EXAMS, []);
  const timetable = loadJson(LS_TIMETABLE, {});
  const now       = Date.now();

  const nextIds = new Set();
  let scheduled = 0;

  // ── 1. EXAM REMINDERS ──
  for (const e of exams) {
    if (!e || !e.name || !e.date) continue;

    const triggerAt = computeExamTrigger(e.date, now);
    if (!triggerAt) continue; // past, invalid, or already-fired

    const id = stableId(ID_PREFIX_EXAM, `${e.name}|${e.date}`);
    const ok = nativeNotifications.schedule(
      id,
      `Exam tomorrow: ${e.name}`,
      `Your ${e.name} exam is tomorrow. Review your weak areas tonight.`,
      ROUTE_EXAM,
      triggerAt
    );
    if (ok) {
      nextIds.add(id);
      scheduled++;
    }
  }

  // ── 2. TIMETABLE SESSION REMINDERS (rolling 7-day window) ──
  for (let d = 0; d < SCAN_DAYS; d++) {
    const targetDate = new Date(now + d * 86400000);
    const dayName    = DAYS_OF_WEEK[targetDate.getDay()];
    const dateStr    = targetDate.toISOString().split('T')[0];
    const sessions   = timetable[dayName] || [];

    for (const s of sessions) {
      if (!s || !s.time || !s.subject) continue;

      const sessionMs = parseTimeToMs(s.time, targetDate);
      if (!sessionMs) continue;

      const triggerAt = sessionMs - SESSION_LEAD_MS;
      if (triggerAt <= now) continue; // past or too close

      const id = stableId(
        ID_PREFIX_TIMETABLE,
        `${dateStr}|${s.time}|${s.subject}`
      );

      const ok = nativeNotifications.schedule(
        id,
        `Upcoming: ${s.subject}`,
        `Your ${s.subject} session starts at ${s.time} (in 15 minutes).`,
        ROUTE_TIMETABLE,
        triggerAt
      );
      if (ok) {
        nextIds.add(id);
        scheduled++;
      }
    }
  }

  // ── 3. CANCEL ORPHANS ──
  // Anything we scheduled last time but no longer produce should be
  // cancelled — the exam/session was deleted or edited.
  let cancelled = 0;
  try {
    const prevRaw = utils.getLocalStorage(LS_SCHEDULED_IDS, '[]');
    const prevIds = new Set(
      Array.isArray(prevRaw) ? prevRaw : (JSON.parse(prevRaw) || [])
    );
    prevIds.forEach((id) => {
      if (!nextIds.has(id)) {
        if (nativeNotifications.cancel(id)) cancelled++;
      }
    });
  } catch {
    // Corrupt cache — start fresh
  }

  utils.setLocalStorage(
    LS_SCHEDULED_IDS,
    JSON.stringify([...nextIds])
  );

  if (scheduled > 0 || cancelled > 0) {
    console.log(
      `[PlannerNotify] Synced — scheduled: ${scheduled}, cancelled: ${cancelled}, active: ${nextIds.size}`
    );
  }

  return { scheduled, cancelled };
}

/**
 * Cancel every scheduled reminder and clear the ID tracker.
 * Call this on logout so a signed-out device stops receiving pushes
 * for the previous account.
 */
export function clearNativeReminders() {
  nativeNotifications.cancelAll();
  utils.setLocalStorage(LS_SCHEDULED_IDS, '[]');
  console.log('[PlannerNotify] Cleared all scheduled reminders');
}

// ---------------------------------------------------------------
// Dev-only timing preview (call from DevTools to verify math without waiting)
//
//   window.plannerNotifications.previewTimings()
//
// Prints every reminder that WOULD be scheduled, with human-readable
// fire times. No alarms are actually created — pure read-and-report.
// ---------------------------------------------------------------
export function previewTimings() {
  const exams     = loadJson(LS_EXAMS, []);
  const timetable = loadJson(LS_TIMETABLE, {});
  const now       = Date.now();
  const out       = [];

  for (const e of exams) {
    if (!e || !e.name || !e.date) continue;
    const triggerAt = computeExamTrigger(e.date, now);
    out.push({
      type: 'exam',
      key: `${e.name} (${e.date})`,
      firesAt: triggerAt
        ? new Date(triggerAt).toLocaleString()
        : 'SKIPPED (past or invalid)',
      willFire: !!triggerAt,
    });
  }

  for (let d = 0; d < SCAN_DAYS; d++) {
    const targetDate = new Date(now + d * 86400000);
    const dayName    = DAYS_OF_WEEK[targetDate.getDay()];
    const dateStr    = targetDate.toISOString().split('T')[0];
    for (const s of (timetable[dayName] || [])) {
      if (!s || !s.time || !s.subject) continue;
      const sessionMs = parseTimeToMs(s.time, targetDate);
      const triggerAt = sessionMs ? sessionMs - SESSION_LEAD_MS : 0;
      out.push({
        type: 'timetable',
        key: `${dateStr} ${s.time} ${s.subject}`,
        firesAt: triggerAt > now
          ? new Date(triggerAt).toLocaleString()
          : 'SKIPPED (past)',
        willFire: triggerAt > now,
      });
    }
  }

  console.table(out);
  console.log(`Would schedule ${out.filter((x) => x.willFire).length} reminders.`);
  return out;
}

if (typeof window !== 'undefined') {
  window.plannerNotifications = {
    syncNativeReminders,
    clearNativeReminders,
    previewTimings,
  };
}