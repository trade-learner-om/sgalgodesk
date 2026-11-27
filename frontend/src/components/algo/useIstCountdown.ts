import { useEffect, useState } from 'react'

const IST = 'Asia/Kolkata'
const IST_OFFSET_MS = (5 * 60 + 30) * 60 * 1000

/** NSE equity / F&O holidays 2026 (weekdays only; weekends excluded separately). */
const NSE_HOLIDAYS = new Set([
  '2026-01-26',
  '2026-03-03',
  '2026-03-26',
  '2026-03-31',
  '2026-04-03',
  '2026-04-14',
  '2026-05-01',
  '2026-05-28',
  '2026-06-26',
  '2026-09-14',
  '2026-10-02',
  '2026-10-20',
  '2026-11-10',
  '2026-11-24',
  '2026-12-25',
])

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

function ymdKey(y: number, m: number, d: number): string {
  return `${y}-${pad2(m)}-${pad2(d)}`
}

/** Today's calendar date parts in IST. */
function istYmd(now = new Date()): { y: number; m: number; d: number } {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: IST,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value)
  return { y: get('year'), m: get('month'), d: get('day') }
}

function addCalendarDays(
  y: number,
  m: number,
  d: number,
  days: number,
): { y: number; m: number; d: number } {
  const dt = new Date(Date.UTC(y, m - 1, d + days))
  return {
    y: dt.getUTCFullYear(),
    m: dt.getUTCMonth() + 1,
    d: dt.getUTCDate(),
  }
}

/** Mon–Fri and not on the NSE holiday calendar (IST calendar date). */
export function isNseTradingDay(y: number, m: number, d: number): boolean {
  const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay() // 0 Sun … 6 Sat
  if (dow === 0 || dow === 6) return false
  if (NSE_HOLIDAYS.has(ymdKey(y, m, d))) return false
  return true
}

function parseHhmm(
  hhmm: string,
): { hour: number; minute: number } | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(hhmm.trim())
  if (!match) return null
  const hour = Number(match[1])
  const minute = Number(match[2])
  if (hour > 23 || minute > 59) return null
  return { hour, minute }
}

/** Instant for y-m-d HH:mm as IST wall time. */
function istAt(
  y: number,
  m: number,
  d: number,
  hour: number,
  minute: number,
): Date {
  const utcMs = Date.UTC(y, m - 1, d, hour, minute, 0) - IST_OFFSET_MS
  return new Date(utcMs)
}

/** Parse HH:mm (or H:mm) as today's Instant in IST. */
export function istTodayAt(hhmm: string, now = new Date()): Date | null {
  const parsed = parseHhmm(hhmm)
  if (!parsed) return null
  const { y, m, d } = istYmd(now)
  return istAt(y, m, d, parsed.hour, parsed.minute)
}

/**
 * Next HH:mm on an NSE trading day (IST).
 * Default: on a trading day after that clock time, returns today's (past) instant so the
 * countdown expires (Entering… / Exiting…). On weekends/holidays, rolls forward.
 * When {@code rollIfPast} is true, a past clock time rolls to the next trading day
 * (used for next-deployment countdown after session square-off).
 */
export function nextTradingDayAt(
  hhmm: string,
  now = new Date(),
  rollIfPast = false,
): Date | null {
  const parsed = parseHhmm(hhmm)
  if (!parsed) return null
  const { hour, minute } = parsed
  const today = istYmd(now)

  if (isNseTradingDay(today.y, today.m, today.d)) {
    const todayTarget = istAt(today.y, today.m, today.d, hour, minute)
    if (!rollIfPast || todayTarget.getTime() > now.getTime()) {
      return todayTarget
    }
  }

  for (let i = 1; i <= 14; i++) {
    const day = addCalendarDays(today.y, today.m, today.d, i)
    if (!isNseTradingDay(day.y, day.m, day.d)) continue
    return istAt(day.y, day.m, day.d, hour, minute)
  }
  return null
}

/** Today's calendar date as yyyy-MM-dd in IST. */
export function istTodayKey(now = new Date()): string {
  const { y, m, d } = istYmd(now)
  return ymdKey(y, m, d)
}

export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  if (h > 0) return `${pad(h)}:${pad(m)}:${pad(s)}`
  return `${pad(m)}:${pad(s)}`
}

export function formatIstClock(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleString('en-IN', {
    timeZone: IST,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    day: '2-digit',
    month: 'short',
  })
}

/** WAITING_ENTRY → "Waiting Entry" */
export function formatLifecycleState(state: string | null | undefined): string {
  if (!state) return '—'
  return state
    .toLowerCase()
    .split('_')
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}

export function useIstCountdown(
  targetHhmm: string | null | undefined,
  options?: { rollIfPast?: boolean },
): {
  label: string
  expired: boolean
  msRemaining: number
} {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(id)
  }, [])

  if (!targetHhmm) {
    return { label: '—', expired: true, msRemaining: 0 }
  }
  const target = nextTradingDayAt(
    targetHhmm,
    new Date(now),
    options?.rollIfPast === true,
  )
  if (!target) {
    return { label: '—', expired: true, msRemaining: 0 }
  }
  const ms = target.getTime() - now
  if (ms <= 0) {
    return { label: '00:00', expired: true, msRemaining: 0 }
  }
  return { label: formatCountdown(ms), expired: false, msRemaining: ms }
}
