import { useEffect, useRef, useState, type MutableRefObject } from 'react'
import {
  CandlestickSeries,
  ColorType,
  createChart,
  createSeriesMarkers,
  TickMarkType,
  type CandlestickData,
  type IChartApi,
  type ISeriesApi,
  type ISeriesMarkersPluginApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts'
import { api, ApiError, type SpreadHistoryPoint } from '../api/client'
import { useSpreadFeed, type SpreadLegInput, type SpreadTick } from '../hooks/useSpreadFeed'
import { useTheme } from '../hooks/useTheme'

const BAR_SEC = 300

type Props = {
  legs: SpreadLegInput[]
  /** ISO timestamp when the spread was established (latest leg fill). */
  entryAt?: string | null
  /** Sum of position realised for selected legs — shown in live PnL header only. */
  realised?: number
  title?: string
  onAuthError?: () => void
}

function formatSigned(n: number): string {
  const sign = n >= 0 ? '+' : '-'
  return (
    sign +
    Math.abs(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  )
}

function formatPrice(n: number): string {
  return n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function todayIst(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}

function istDayFromIso(iso: string | null | undefined): string {
  if (!iso) {
    return todayIst()
  }
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) {
    return todayIst()
  }
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(d)
}

function floorBarSec(epochSec: number): number {
  return Math.floor(epochSec / BAR_SEC) * BAR_SEC
}

function epochSecFromTime(time: Time): number | null {
  if (typeof time === 'number' && Number.isFinite(time)) {
    return time
  }
  if (time && typeof time === 'object' && 'timestamp' in time) {
    const ts = (time as { timestamp: number }).timestamp
    return Number.isFinite(ts) ? ts : null
  }
  return null
}

const istTimeFmt = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

const istCrosshairFmt = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  day: '2-digit',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

const istDayTickFmt = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  day: '2-digit',
  month: 'short',
})

const istMonthTickFmt = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  month: 'short',
  year: '2-digit',
})

const istYearTickFmt = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  year: 'numeric',
})

/** Crosshair / tooltip label in IST. */
function formatIstCrosshair(time: Time): string {
  const sec = epochSecFromTime(time)
  if (sec == null) {
    return ''
  }
  return istCrosshairFmt.format(new Date(sec * 1000))
}

/** Axis tick label in IST (lightweight-charts otherwise uses UTC). */
function formatIstTickMark(time: Time, tickMarkType: TickMarkType): string {
  const sec = epochSecFromTime(time)
  if (sec == null) {
    return ''
  }
  const d = new Date(sec * 1000)
  switch (tickMarkType) {
    case TickMarkType.Year:
      return istYearTickFmt.format(d)
    case TickMarkType.Month:
      return istMonthTickFmt.format(d)
    case TickMarkType.DayOfMonth:
      return istDayTickFmt.format(d)
    case TickMarkType.Time:
    case TickMarkType.TimeWithSeconds:
    default:
      return istTimeFmt.format(d)
  }
}

function formatIstFromLabel(iso: string | null | undefined, openDay: string): string {
  if (iso) {
    const ms = Date.parse(iso)
    if (Number.isFinite(ms)) {
      return `${istCrosshairFmt.format(new Date(ms))} IST`
    }
  }
  return `${openDay} 09:15 IST`
}

/** True when epoch ms falls inside NSE F&O cash session 09:15–15:30 IST. */
function isWithinIstSession(epochMs: number): boolean {
  if (!Number.isFinite(epochMs)) {
    return false
  }
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Kolkata',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(new Date(epochMs))
  const hour = Number(parts.find((p) => p.type === 'hour')?.value)
  const minute = Number(parts.find((p) => p.type === 'minute')?.value)
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) {
    return false
  }
  const mins = hour * 60 + minute
  return mins >= 9 * 60 + 15 && mins <= 15 * 60 + 30
}

function entryFloorSec(entryAtIso: string | null | undefined): number | null {
  if (!entryAtIso) {
    return null
  }
  const ms = Date.parse(entryAtIso)
  if (!Number.isFinite(ms)) {
    return null
  }
  return floorBarSec(Math.floor(ms / 1000))
}

/** History OHLC is already spread mark (combined price). */
function toMarkBar(p: SpreadHistoryPoint): CandlestickData {
  return {
    time: Math.floor(p.ts / 1000) as UTCTimestamp,
    open: p.open,
    high: p.high,
    low: p.low,
    close: p.close,
  }
}

function legsFingerprint(legs: SpreadLegInput[]): string {
  return legs
    .map((l) => `${l.instrumentKey}:${l.side}:${l.quantity}:${l.lotSize}:${l.lots}:${l.entryPrice}`)
    .sort()
    .join('|')
}

/** Per-unit combined premium from leg entry prices (same formula as spreadClassify). */
function entryPremiumFromLegs(legs: SpreadLegInput[]): number {
  let credit = 0
  let debit = 0
  for (const leg of legs) {
    const px = Math.abs(leg.entryPrice)
    if (leg.side === 'SELL') {
      credit += px
    } else {
      debit += px
    }
  }
  return credit >= debit ? credit - debit : debit - credit
}

function normalizeBars(bars: CandlestickData[]): CandlestickData[] {
  const byTime = new Map<number, CandlestickData>()
  for (const bar of bars) {
    const t = Number(bar.time)
    if (
      !Number.isFinite(t) ||
      !Number.isFinite(bar.open) ||
      !Number.isFinite(bar.high) ||
      !Number.isFinite(bar.low) ||
      !Number.isFinite(bar.close)
    ) {
      continue
    }
    byTime.set(t, bar)
  }
  return [...byTime.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([, bar]) => bar)
}

function resolveEntryBarTime(
  bars: CandlestickData[],
  entryAtIso: string | null | undefined,
): UTCTimestamp | null {
  if (!entryAtIso || bars.length === 0) {
    return null
  }
  const ms = Date.parse(entryAtIso)
  if (!Number.isFinite(ms)) {
    return null
  }
  const target = floorBarSec(Math.floor(ms / 1000))
  let best: CandlestickData | null = null
  for (const bar of bars) {
    const t = Number(bar.time)
    if (t >= target && (best == null || t < Number(best.time))) {
      best = bar
    }
  }
  if (best) {
    return best.time as UTCTimestamp
  }
  // Fall back to last bar at/before target
  for (let i = bars.length - 1; i >= 0; i--) {
    const bar = bars[i]!
    if (Number(bar.time) <= target) {
      return bar.time as UTCTimestamp
    }
  }
  return bars[0]!.time as UTCTimestamp
}

export function SpreadPnlChart({ legs, entryAt = null, realised = 0, title, onAuthError }: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const priceLineRef = useRef<ReturnType<ISeriesApi<'Candlestick'>['createPriceLine']> | null>(null)
  const entryLineRef = useRef<ReturnType<ISeriesApi<'Candlestick'>['createPriceLine']> | null>(null)
  const markersRef = useRef<ISeriesMarkersPluginApi<Time> | null>(null)
  const lastSeedTsRef = useRef(0)
  const barsRef = useRef<CandlestickData[]>([])
  const liveBarRef = useRef<CandlestickData | null>(null)
  const historyPointsRef = useRef<SpreadHistoryPoint[]>([])
  const entryAtRef = useRef(entryAt)
  entryAtRef.current = entryAt
  const entryPremiumRef = useRef(0)
  const currentMarkRef = useRef(0)
  entryPremiumRef.current = entryPremiumFromLegs(legs)
  const [last, setLast] = useState<SpreadTick | null>(null)
  const [waiting, setWaiting] = useState(true)
  const [historyEmpty, setHistoryEmpty] = useState(false)
  const [historyEmptyMessage, setHistoryEmptyMessage] = useState<string | null>(null)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const { isDark } = useTheme()
  const realisedRef = useRef(realised)
  realisedRef.current = realised
  const isDarkRef = useRef(isDark)
  isDarkRef.current = isDark
  const onAuthErrorRef = useRef(onAuthError)
  onAuthErrorRef.current = onAuthError
  const legsRef = useRef(legs)
  legsRef.current = legs
  const fingerprint = legsFingerprint(legs)
  const openDay = istDayFromIso(entryAt)

  function applyEntryMarker(series: ISeriesApi<'Candlestick'>, bars: CandlestickData[]) {
    const entryTime = resolveEntryBarTime(bars, entryAtRef.current)
    const markers: SeriesMarker<Time>[] = []
    if (entryTime != null) {
      markers.push({
        time: entryTime,
        position: 'belowBar',
        shape: 'circle',
        color: '#f59e0b',
        text: 'Entry',
      })
    }
    if (!markersRef.current) {
      markersRef.current = createSeriesMarkers(series, markers)
    } else {
      markersRef.current.setMarkers(markers)
    }
  }

  useEffect(() => {
    const el = containerRef.current
    if (!el) {
      return
    }
    const chart = createChart(el, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: isDark ? '#131823' : '#ffffff' },
        textColor: isDark ? '#94a3b8' : '#64748b',
      },
      grid: {
        vertLines: { color: isDark ? '#1e293b' : '#f1f5f9' },
        horzLines: { color: isDark ? '#1e293b' : '#f1f5f9' },
      },
      rightPriceScale: { borderVisible: false },
      localization: {
        timeFormatter: formatIstCrosshair,
      },
      timeScale: {
        borderVisible: false,
        timeVisible: true,
        secondsVisible: false,
        tickMarkFormatter: formatIstTickMark,
      },
      crosshair: { mode: 0 },
    })
    const series = chart.addSeries(CandlestickSeries, {
      upColor: '#10b981',
      downColor: '#f43f5e',
      borderUpColor: '#10b981',
      borderDownColor: '#f43f5e',
      wickUpColor: '#10b981',
      wickDownColor: '#f43f5e',
      priceLineVisible: false,
      lastValueVisible: true,
      autoscaleInfoProvider: (
        original: () => { priceRange: { minValue: number; maxValue: number } | null } | null,
      ) => {
        const base = original()
        if (base == null || base.priceRange == null) {
          return base
        }
        let min = base.priceRange.minValue
        let max = base.priceRange.maxValue
        const entry = entryPremiumRef.current
        const mark = currentMarkRef.current
        if (entry > 0) {
          min = Math.min(min, entry)
          max = Math.max(max, entry)
        }
        if (Number.isFinite(mark) && mark !== 0) {
          min = Math.min(min, mark)
          max = Math.max(max, mark)
        }
        const pad = Math.max((max - min) * 0.08, 0.5)
        return {
          ...base,
          priceRange: { minValue: min - pad, maxValue: max + pad },
        }
      },
    })
    chartRef.current = chart
    seriesRef.current = series
    markersRef.current = null
    priceLineRef.current = null
    entryLineRef.current = null
    // Theme remount: restore previously loaded bars + lines + entry blip
    if (barsRef.current.length > 0) {
      series.setData(barsRef.current)
      const lastPoint = historyPointsRef.current[historyPointsRef.current.length - 1]
      const mark = liveBarRef.current?.close ?? barsRef.current[barsRef.current.length - 1]!.close
      currentMarkRef.current = mark
      const entryPremium = entryPremiumRef.current || lastPoint?.combinedPremium || 0
      const lots = lastPoint?.lots ?? 1
      const basePnl = lastPoint?.runningPnl ?? 0
      applyPriceLines(
        series,
        mark,
        entryPremium,
        lots,
        basePnl + realisedRef.current,
        isDark,
        priceLineRef,
        entryLineRef,
      )
      applyEntryMarker(series, barsRef.current)
      chart.timeScale().fitContent()
    }
    return () => {
      chart.remove()
      chartRef.current = null
      seriesRef.current = null
      markersRef.current = null
      priceLineRef.current = null
      entryLineRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- restore uses refs; recreate only on theme
  }, [isDark])

  useEffect(() => {
    seriesRef.current?.setData([])
    barsRef.current = []
    liveBarRef.current = null
    historyPointsRef.current = []
    setLast(null)
    setWaiting(true)
    setHistoryEmpty(false)
    setHistoryEmptyMessage(null)
    setHistoryError(null)
    lastSeedTsRef.current = 0
    markersRef.current?.setMarkers([])
    if (seriesRef.current) {
      if (priceLineRef.current) {
        seriesRef.current.removePriceLine(priceLineRef.current)
        priceLineRef.current = null
      }
      if (entryLineRef.current) {
        seriesRef.current.removePriceLine(entryLineRef.current)
        entryLineRef.current = null
      }
    }
  }, [fingerprint, openDay, entryAt])

  useEffect(() => {
    if (!fingerprint) {
      return
    }
    let cancelled = false
    let pollTimer: number | undefined
    let polls = 0
    const maxPolls = 15

    function applyHistoryPoints(points: SpreadHistoryPoint[]) {
      const series = seriesRef.current
      if (!series || points.length === 0) {
        return
      }
      const entryFloor = entryFloorSec(entryAtRef.current)
      const trimmed =
        entryFloor != null ? points.filter((p) => Math.floor(p.ts / 1000) >= entryFloor) : points
      const usable = trimmed.length > 0 ? trimmed : points
      historyPointsRef.current = usable
      let data = normalizeBars(usable.map(toMarkBar))
      if (data.length === 0) {
        return
      }
      const live = liveBarRef.current
      const liveInSession =
        live != null && isWithinIstSession(Number(live.time) * 1000)
      if (
        liveInSession &&
        live &&
        Number(live.time) > Number(data[data.length - 1]!.time)
      ) {
        data = normalizeBars([...data, live])
      } else if (
        liveInSession &&
        live &&
        Number(live.time) === Number(data[data.length - 1]!.time)
      ) {
        const lastBar = data[data.length - 1]!
        data[data.length - 1] = {
          ...lastBar,
          high: Math.max(lastBar.high, live.high),
          low: Math.min(lastBar.low, live.low),
          close: live.close,
        }
      }
      barsRef.current = data
      series.setData(data)
      const lastPoint = usable[usable.length - 1]!
      lastSeedTsRef.current = Math.max(lastPoint.ts, lastSeedTsRef.current)
      const mark = data[data.length - 1]!.close
      currentMarkRef.current = mark
      const r = realisedRef.current
      const entryPremium = entryPremiumRef.current || lastPoint.combinedPremium
      applyPriceLines(
        series,
        mark,
        entryPremium,
        lastPoint.lots,
        lastPoint.runningPnl + r,
        isDarkRef.current,
        priceLineRef,
        entryLineRef,
      )
      applyEntryMarker(series, data)
      setLast({
        ts: lastPoint.ts,
        spreadMark: lastPoint.spreadMark,
        runningPnl: lastPoint.runningPnl + r,
        runningPnl1Lot: lastPoint.runningPnl1Lot,
        combinedPremium: lastPoint.combinedPremium,
        lots: lastPoint.lots,
        kind: lastPoint.kind === 'credit' ? 'credit' : 'debit',
      })
      setWaiting(false)
      setHistoryEmpty(false)
      setHistoryError(null)
      chartRef.current?.timeScale().fitContent()
    }

    async function loadHistory(schedulePoll: boolean) {
      try {
        const currentLegs = legsRef.current
        const res = await api.spreadHistory({
          legs: currentLegs.map((l) => ({
            instrumentKey: l.instrumentKey,
            side: l.side,
            quantity: l.quantity,
            lotSize: l.lotSize,
            lots: l.lots,
            entryPrice: l.entryPrice,
          })),
          openDay,
          entryAt: entryAtRef.current,
        })
        if (cancelled) {
          return
        }
        if (res.points?.length) {
          applyHistoryPoints(res.points)
          setHistoryEmpty(false)
          setHistoryEmptyMessage(null)
        } else {
          setWaiting(false)
          setHistoryEmpty(true)
          setHistoryEmptyMessage(
            typeof res.message === 'string' && res.message.trim()
              ? res.message.trim()
              : null,
          )
        }
        // Only poll while backend says history is still filling; closed/holiday returns incomplete=false.
        if (schedulePoll && res.incomplete && polls < maxPolls) {
          polls += 1
          pollTimer = window.setTimeout(() => {
            void loadHistory(true)
          }, 2000)
        }
      } catch (err) {
        if (cancelled) {
          return
        }
        setWaiting(false)
        if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
          onAuthErrorRef.current?.()
        } else {
          setHistoryError(err instanceof Error ? err.message : 'Failed to load candles')
        }
      }
    }

    void loadHistory(true)
    return () => {
      cancelled = true
      if (pollTimer != null) {
        window.clearTimeout(pollTimer)
      }
    }
  }, [fingerprint, openDay, entryAt])

  // Re-place entry blip when entryAt arrives after history
  useEffect(() => {
    const series = seriesRef.current
    if (series && barsRef.current.length > 0) {
      applyEntryMarker(series, barsRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entryAt])

  // Live PnL header updates when realised changes; candle Y stays mark-only.
  useEffect(() => {
    setLast((prev) => {
      if (!prev) {
        return prev
      }
      const points = historyPointsRef.current
      const basePnl =
        points.length > 0
          ? points[points.length - 1]!.runningPnl
          : prev.runningPnl - realisedRef.current
      return {
        ...prev,
        runningPnl: basePnl + realised,
      }
    })
    const series = seriesRef.current
    const mark = liveBarRef.current?.close ?? barsRef.current[barsRef.current.length - 1]?.close
    const lastPoint = historyPointsRef.current[historyPointsRef.current.length - 1]
    if (series && mark != null) {
      currentMarkRef.current = mark
      const entryPremium =
        entryPremiumRef.current || lastPoint?.combinedPremium || last?.combinedPremium || 0
      const lots = lastPoint?.lots ?? last?.lots ?? 1
      const basePnl = lastPoint?.runningPnl ?? (last ? last.runningPnl - realisedRef.current : 0)
      applyPriceLines(
        series,
        mark,
        entryPremium,
        lots,
        basePnl + realised,
        isDarkRef.current,
        priceLineRef,
        entryLineRef,
      )
    }
  }, [realised, last?.combinedPremium, last?.lots, fingerprint])

  useSpreadFeed({
    legs,
    enabled: legs.length > 0,
    onAuthError,
    onTick: (tick) => {
      if (tick.ts < lastSeedTsRef.current - BAR_SEC * 1000) {
        return
      }
      const r = realisedRef.current
      const fullPnl = tick.runningPnl + r
      const mark = tick.spreadMark
      // Always refresh header PnL; only extend candles during IST session.
      setLast({
        ...tick,
        runningPnl: fullPnl,
      })
      setWaiting(false)
      const series = seriesRef.current
      if (!series || !Number.isFinite(mark)) {
        return
      }
      const inSession = isWithinIstSession(tick.ts)
      const entryFloor = entryFloorSec(entryAtRef.current)
      const barSec = floorBarSec(Math.floor(tick.ts / 1000))
      const needsFirstBar = barsRef.current.length === 0
      // Outside session: still paint a first mark bar so the canvas is not blank;
      // only skip extending candles after hours.
      if ((!inSession && !needsFirstBar) || (entryFloor != null && barSec < entryFloor)) {
        currentMarkRef.current = mark
        const entryPremium = entryPremiumRef.current || tick.combinedPremium
        applyPriceLines(
          series,
          mark,
          entryPremium,
          tick.lots,
          fullPnl,
          isDark,
          priceLineRef,
          entryLineRef,
        )
        return
      }
      const time = barSec as UTCTimestamp
      let bar = liveBarRef.current
      if (!bar || Number(bar.time) !== barSec) {
        const prev = barsRef.current[barsRef.current.length - 1]
        const open = prev && Number(prev.time) === barSec ? prev.open : mark
        bar = {
          time,
          open,
          high: Math.max(open, mark),
          low: Math.min(open, mark),
          close: mark,
        }
      } else {
        bar = {
          time,
          open: bar.open,
          high: Math.max(bar.high, mark),
          low: Math.min(bar.low, mark),
          close: mark,
        }
      }
      liveBarRef.current = bar
      currentMarkRef.current = mark
      if (needsFirstBar) {
        barsRef.current = [bar]
        series.setData([bar])
        applyEntryMarker(series, barsRef.current)
      } else {
        try {
          series.update(bar)
          const idx = barsRef.current.findIndex((b) => Number(b.time) === barSec)
          if (idx >= 0) {
            barsRef.current[idx] = bar
          } else {
            barsRef.current = normalizeBars([...barsRef.current, bar])
          }
        } catch {
          const next = normalizeBars([
            ...barsRef.current.filter((b) => Number(b.time) !== barSec),
            bar,
          ])
          barsRef.current = next
          series.setData(next)
        }
      }
      const entryPremium = entryPremiumRef.current || tick.combinedPremium
      applyPriceLines(
        series,
        mark,
        entryPremium,
        tick.lots,
        fullPnl,
        isDark,
        priceLineRef,
        entryLineRef,
      )
      lastSeedTsRef.current = tick.ts
      chartRef.current?.timeScale().scrollToRealTime()
    },
  })

  return (
    <div className="flex min-h-[360px] flex-1 flex-col rounded-xl border border-slate-200 bg-white p-3 dark:border-slate-800 dark:bg-[#131823]">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2 shrink-0">
        <div>
          <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
            {title ?? 'Spread price'}
          </h3>
          <p className="text-[11px] text-slate-500">
            5m candles · from {formatIstFromLabel(entryAt, openDay)} · spread combined price
          </p>
        </div>
        {last && (
          <div className="text-right text-xs">
            <div className="font-semibold tabular-nums text-slate-800 dark:text-slate-100">
              Price {formatPrice(last.spreadMark)}
            </div>
            <div
              className={`font-semibold tabular-nums ${
                last.runningPnl >= 0 ? 'text-emerald-600' : 'text-rose-600'
              }`}
            >
              {last.lots} lots :: {formatSigned(last.runningPnl)}
            </div>
            <div className="text-slate-500">{last.kind}</div>
          </div>
        )}
      </div>
      {waiting && !last && (
        <p className="mb-2 shrink-0 text-[11px] text-slate-500">Loading candle history…</p>
      )}
      {!waiting && historyEmpty && !last && (
        <p className="mb-2 shrink-0 text-[11px] text-slate-500">
          {historyEmptyMessage ?? 'No candle history yet — waiting for market data…'}
        </p>
      )}
      {historyError && (
        <p className="mb-2 shrink-0 text-[11px] text-rose-500">{historyError}</p>
      )}
      <div ref={containerRef} className="min-h-[320px] w-full flex-1" />
    </div>
  )
}

function applyPriceLines(
  series: ISeriesApi<'Candlestick'>,
  currentMark: number,
  entryPremium: number,
  lots: number,
  fullPnl: number,
  isDark: boolean,
  priceLineRef: MutableRefObject<ReturnType<ISeriesApi<'Candlestick'>['createPriceLine']> | null>,
  entryLineRef: MutableRefObject<ReturnType<ISeriesApi<'Candlestick'>['createPriceLine']> | null>,
) {
  if (priceLineRef.current) {
    series.removePriceLine(priceLineRef.current)
  }
  priceLineRef.current = series.createPriceLine({
    price: currentMark,
    color: isDark ? '#38bdf8' : '#0284c7',
    lineWidth: 1,
    lineStyle: 2,
    axisLabelVisible: true,
    title: `Now ${formatPrice(currentMark)} · ${lots} lots :: ${formatSigned(fullPnl)}`,
  })

  if (entryLineRef.current) {
    series.removePriceLine(entryLineRef.current)
    entryLineRef.current = null
  }
  if (entryPremium > 0) {
    entryLineRef.current = series.createPriceLine({
      price: entryPremium,
      color: isDark ? '#94a3b8' : '#64748b',
      lineWidth: 1,
      lineStyle: 1,
      axisLabelVisible: true,
      title: `Entry ${formatPrice(entryPremium)}`,
    })
  }
}
