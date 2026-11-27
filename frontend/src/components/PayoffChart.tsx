import { memo, useEffect, useMemo, useRef, useState } from 'react'
import {
  buildTicks,
  formatAxisPnl,
  formatAxisPrice,
  pickTickStep,
  pickYTicks,
} from '../payoff/axis'
import {
  blendedIv,
  buildPayoff,
  daysToExpiry,
  estimateMargin,
  estimatePop,
  netPremium,
  pnlAtSpot,
  strategyGreeks,
  type ScenarioOverrides,
  type StrategyLeg,
} from '../payoff/payoff'
import { combinedPremium } from '../payoff/spreadClassify'
import {
  SPREAD_TEMPLATES,
  TEMPLATE_PREVIEW_PATHS,
  type SpreadTemplateId,
} from '../payoff/templatePreviews'
import { useTheme } from '../hooks/useTheme'

type Props = {
  legs: StrategyLeg[]
  spot: number | null
  title?: string | null
  templatesReady?: boolean
  onApplyTemplate?: (id: SpreadTemplateId) => void
}

type ViewDomain = { min: number; max: number }

const MIN_VIEW_SPAN = 100

function formatMoney(value: number): string {
  if (!Number.isFinite(value)) {
    return '—'
  }
  return value.toLocaleString('en-IN', {
    maximumFractionDigits: 0,
    signDisplay: 'exceptZero',
  })
}

function formatPrice(value: number): string {
  return value.toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function formatGreek(value: number | null | undefined, digits = 2): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  return value.toLocaleString('en-IN', {
    maximumFractionDigits: digits,
    signDisplay: 'exceptZero',
  })
}

function clampDomain(
  min: number,
  max: number,
  fullMin: number,
  fullMax: number,
  minSpan = MIN_VIEW_SPAN,
): ViewDomain {
  const fullSpan = fullMax - fullMin
  const spanFloor = Math.min(Math.max(minSpan, fullSpan * 0.01), fullSpan)
  let nextMin = Math.max(fullMin, Math.min(min, max))
  let nextMax = Math.min(fullMax, Math.max(min, max))
  if (nextMax - nextMin < spanFloor) {
    const mid = (nextMin + nextMax) / 2
    nextMin = mid - spanFloor / 2
    nextMax = mid + spanFloor / 2
    if (nextMin < fullMin) {
      nextMin = fullMin
      nextMax = fullMin + spanFloor
    }
    if (nextMax > fullMax) {
      nextMax = fullMax
      nextMin = fullMax - spanFloor
    }
  }
  return { min: nextMin, max: nextMax }
}

function MutedTemplatePreview({ id, isDark }: { id: SpreadTemplateId; isDark: boolean }) {
  const paths = TEMPLATE_PREVIEW_PATHS[id]
  const zero = isDark ? '#475569' : '#94a3b8'
  const line = isDark ? '#cbd5e1' : '#334155'
  const profit = isDark ? '#34d399' : '#10b981'
  const loss = isDark ? '#fb7185' : '#f43f5e'
  return (
    <svg viewBox="0 0 120 56" className="h-12 w-full opacity-80" aria-hidden>
      <line x1="8" x2="112" y1="28" y2="28" stroke={zero} strokeDasharray="2 2" strokeWidth="1" />
      <path d={paths.profit} fill={profit} fillOpacity="0.25" />
      <path d={paths.loss} fill={loss} fillOpacity="0.25" />
      <path d={paths.line} fill="none" stroke={line} strokeWidth="1.75" strokeLinejoin="round" />
    </svg>
  )
}

export const PayoffChart = memo(function PayoffChart({
  legs,
  spot,
  title,
  templatesReady = false,
  onApplyTemplate,
}: Props) {
  const calendarDte = useMemo(() => {
    const dte = daysToExpiry(legs)
    return dte == null || Number.isNaN(dte) ? null : dte
  }, [legs])
  const baseIv = useMemo(() => blendedIv(legs), [legs])
  const [scenarioDays, setScenarioDays] = useState<number | null>(null)
  const [ivScalePct, setIvScalePct] = useState(100)

  const legsFingerprint = legs.map((l) => `${l.id}:${l.expiry}:${l.iv}`).join('|')
  useEffect(() => {
    setScenarioDays(calendarDte)
    setIvScalePct(100)
  }, [calendarDte, legsFingerprint])

  const scenario: ScenarioOverrides = useMemo(
    () => ({
      asOfDays: scenarioDays,
      ivScale: ivScalePct / 100,
    }),
    [scenarioDays, ivScalePct],
  )

  const summary = useMemo(() => buildPayoff(legs, spot, 220, scenario), [legs, spot, scenario])
  const greeks = useMemo(() => strategyGreeks(legs), [legs])
  const premiumKind = useMemo(() => combinedPremium(legs).kind, [legs])
  const premiumTotal = useMemo(() => Math.abs(netPremium(legs)), [legs])
  const pop = useMemo(() => estimatePop(legs, spot, scenario), [legs, spot, scenario])
  const margin = useMemo(() => estimateMargin(legs, spot), [legs, spot])
  const [hoverX, setHoverX] = useState<number | null>(null)
  const [pointer, setPointer] = useState<{ x: number; y: number } | null>(null)
  const [view, setView] = useState<ViewDomain | null>(null)
  const [plotSize, setPlotSize] = useState({ width: 720, height: 420 })
  const chartSvgRef = useRef<SVGSVGElement | null>(null)
  const plotRef = useRef<HTMLDivElement | null>(null)
  const tooltipRef = useRef<HTMLDivElement | null>(null)
  const viewRef = useRef<ViewDomain | null>(null)
  const summaryRangeRef = useRef<{ min: number; max: number } | null>(null)
  const plotSizeRef = useRef(plotSize)
  const { isDark } = useTheme()

  useEffect(() => {
    setView(null)
    setHoverX(null)
    setPointer(null)
  }, [summary?.minSpot, summary?.maxSpot, legs.length])

  useEffect(() => {
    viewRef.current = view
  }, [view])

  useEffect(() => {
    plotSizeRef.current = plotSize
  }, [plotSize])

  useEffect(() => {
    if (!summary) {
      summaryRangeRef.current = null
      return
    }
    summaryRangeRef.current = { min: summary.minSpot, max: summary.maxSpot }
  }, [summary])

  useEffect(() => {
    const node = plotRef.current
    if (!node || typeof ResizeObserver === 'undefined') {
      return
    }
    const update = () => {
      const rect = node.getBoundingClientRect()
      const nextW = Math.max(320, Math.round(rect.width))
      const nextH = Math.max(220, Math.round(rect.height))
      setPlotSize((prev) =>
        prev.width === nextW && prev.height === nextH ? prev : { width: nextW, height: nextH },
      )
    }
    update()
    const ro = new ResizeObserver(update)
    ro.observe(node)
    return () => ro.disconnect()
  }, [summary])

  useEffect(() => {
    const node = chartSvgRef.current
    if (!node) {
      return
    }
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      const range = summaryRangeRef.current
      if (!range) {
        return
      }
      const fullMin = range.min
      const fullMax = range.max
      const current = viewRef.current
      const viewMin = current?.min ?? fullMin
      const viewMax = current?.max ?? fullMax
      const viewSpan = Math.max(viewMax - viewMin, 1e-9)
      const rect = node.getBoundingClientRect()
      const { width } = plotSizeRef.current
      const padLeft = 64
      const padRight = 28
      const svgX = ((event.clientX - rect.left) / rect.width) * width
      const anchor =
        svgX < padLeft || svgX > width - padRight
          ? (viewMin + viewMax) / 2
          : viewMin + ((svgX - padLeft) / (width - padLeft - padRight)) * viewSpan
      const factor = event.deltaY < 0 ? 0.85 : 1.18
      const nextSpan = viewSpan * factor
      const fullSpan = fullMax - fullMin
      if (factor > 1 && nextSpan >= fullSpan) {
        setView(null)
        return
      }
      const ratio = (anchor - viewMin) / viewSpan
      const nextMin = anchor - nextSpan * ratio
      const nextMax = anchor + nextSpan * (1 - ratio)
      setView(clampDomain(nextMin, nextMax, fullMin, fullMax))
    }
    node.addEventListener('wheel', onWheel, { passive: false })
    return () => node.removeEventListener('wheel', onWheel)
  }, [summary])

  if (!summary) {
    if (templatesReady && onApplyTemplate) {
      return (
        <div className="flex min-h-0 flex-1 flex-col rounded-xl border border-slate-200/90 bg-white p-3.5 shadow-sm dark:border-slate-800/90 dark:bg-[#0D1119]">
          <div className="mb-3 flex shrink-0 items-center justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                {title?.trim() || 'Payoff Simulator'}
              </h2>
              <p className="text-[11px] text-slate-500 dark:text-slate-400">
                Launch a preset strategy or click <span className="font-semibold">B / S</span> on the option chain
              </p>
            </div>
          </div>
          <div className="desk-scroll grid min-h-0 flex-1 grid-cols-2 content-start gap-2.5 overflow-y-auto sm:grid-cols-3">
            {SPREAD_TEMPLATES.map((template) => (
              <button
                key={template.id}
                type="button"
                onClick={() => onApplyTemplate(template.id)}
                className="group flex flex-col rounded-xl border border-slate-200/90 bg-slate-50/50 p-3 text-left transition hover:border-blue-400 hover:bg-blue-50/40 dark:border-slate-800 dark:bg-[#131924] dark:hover:border-blue-500/60 dark:hover:bg-blue-950/25"
              >
                <div className="flex items-center justify-between gap-1">
                  <span className="text-xs font-semibold text-slate-900 group-hover:text-blue-600 dark:text-slate-100 dark:group-hover:text-blue-400">
                    {template.name}
                  </span>
                  <span className="text-[10px] text-slate-400 opacity-0 transition group-hover:opacity-100">
                    Apply →
                  </span>
                </div>
                <div className="mt-0.5 text-[10px] leading-snug text-slate-500 dark:text-slate-400">
                  {template.preview}
                </div>
                <div className="mt-2 rounded-lg border border-slate-200/70 bg-white px-1.5 py-1 dark:border-slate-800 dark:bg-[#070A0F]">
                  <MutedTemplatePreview id={template.id} isDark={isDark} />
                </div>
              </button>
            ))}
          </div>
        </div>
      )
    }

    return (
      <div className="flex min-h-[240px] flex-1 items-center justify-center rounded-xl border border-dashed border-slate-200 bg-white px-6 text-center text-xs text-slate-500 dark:border-slate-800 dark:bg-[#0D1119] dark:text-slate-400">
        {templatesReady
          ? 'Click B / S on the option chain to simulate a payoff.'
          : 'Loading option chain… select an expiry to unlock spread templates.'}
      </div>
    )
  }

  const width = plotSize.width
  const height = plotSize.height
  const pad = { top: 28, right: 28, bottom: 48, left: 64 }
  const innerW = Math.max(width - pad.left - pad.right, 1)
  const innerH = Math.max(height - pad.top - pad.bottom, 1)

  // Always show the full fitted domain unless the user has scrolled to zoom.
  const fullMin = summary.minSpot
  const fullMax = summary.maxSpot
  const viewMin = view?.min ?? fullMin
  const viewMax = view?.max ?? fullMax
  const viewSpan = Math.max(viewMax - viewMin, 1e-9)
  const zoomed = viewMin > fullMin + 1e-9 || viewMax < fullMax - 1e-9
  const xStep = pickTickStep(viewSpan)
  const xTicks = buildTicks(viewMin, viewMax, xStep)

  const visiblePoints = summary.points.filter((p) => p.spot >= viewMin && p.spot <= viewMax)
  const edgePoints = visiblePoints.length > 0 ? visiblePoints : summary.points

  const disp = (pnl: number) => pnl
  const ys = edgePoints
    .flatMap((p) => [disp(p.expiryPnl), disp(p.t0Pnl)])
    .filter((v) => Number.isFinite(v))
  const rawPeak = ys.length > 0 ? Math.max(...ys, 0) : 0
  const rawTrough = ys.length > 0 ? Math.min(...ys, 0) : 0
  let yMax = rawPeak
  let yMin = rawTrough
  if (rawPeak > 0) {
    yMax = rawPeak * 1.12
    const minForSixty = yMax - rawPeak / 0.55
    yMin = Math.min(rawTrough, minForSixty)
  } else if (rawTrough < 0) {
    yMin = rawTrough * 1.12
    const maxForSixty = yMin + Math.abs(rawTrough) / 0.55
    yMax = Math.max(rawPeak, maxForSixty)
  }
  if (yMax === yMin) {
    yMax += 1
    yMin -= 1
  }
  const yPad = Math.max((yMax - yMin) * 0.06, 1)
  const y0 = yMin - yPad
  const y1 = yMax + yPad
  const yTicks = pickYTicks(y0, y1)

  const xScale = (spotValue: number) => pad.left + ((spotValue - viewMin) / viewSpan) * innerW
  const yScale = (pnl: number) => pad.top + ((y1 - disp(pnl)) / (y1 - y0)) * innerH
  const xToSpot = (svgX: number) => viewMin + ((svgX - pad.left) / innerW) * viewSpan

  const zeroY = yScale(0)
  const plotPoints = summary.points.filter((p) => p.spot >= viewMin && p.spot <= viewMax)
  const pathPoints = plotPoints.length >= 2 ? plotPoints : summary.points

  const expiryPath = pathPoints
    .map((p, i) => `${i === 0 ? 'M' : 'L'} ${xScale(p.spot)} ${yScale(p.expiryPnl)}`)
    .join(' ')
  const t0Path = pathPoints
    .map((p, i) => `${i === 0 ? 'M' : 'L'} ${xScale(p.spot)} ${yScale(p.t0Pnl)}`)
    .join(' ')

  const areaMin = pathPoints[0]?.spot ?? viewMin
  const areaMax = pathPoints[pathPoints.length - 1]?.spot ?? viewMax

  const profitArea = [
    `M ${xScale(areaMin)} ${zeroY}`,
    ...pathPoints.map((p) => `L ${xScale(p.spot)} ${yScale(Math.max(p.expiryPnl, 0))}`),
    `L ${xScale(areaMax)} ${zeroY}`,
    'Z',
  ].join(' ')

  const lossArea = [
    `M ${xScale(areaMin)} ${zeroY}`,
    ...pathPoints.map((p) => `L ${xScale(p.spot)} ${yScale(Math.min(p.expiryPnl, 0))}`),
    `L ${xScale(areaMax)} ${zeroY}`,
    'Z',
  ].join(' ')

  const hoverSpot = hoverX == null ? null : xToSpot(hoverX)
  const hover =
    hoverSpot == null || hoverSpot < viewMin || hoverSpot > viewMax
      ? null
      : {
          spot: hoverSpot,
          ...pnlAtSpot(legs, hoverSpot, scenario),
        }

  const sellStrikes = legs
    .filter(
      (leg) =>
        leg.side === 'SELL' &&
        leg.strike != null &&
        Number.isFinite(leg.strike) &&
        leg.strike >= viewMin &&
        leg.strike <= viewMax,
    )
    .map((leg) => ({ id: leg.id, strike: leg.strike as number }))

  const chartTitle = title?.trim() || 'Payoff'
  const riskReward =
    summary.maxProfit != null &&
    summary.maxLoss != null &&
    summary.maxLoss !== 0
      ? Math.abs(summary.maxProfit / summary.maxLoss)
      : null

  const axisStroke = isDark ? '#1e293b' : '#e2e8f0'
  const zeroStroke = isDark ? '#64748b' : '#94a3b8'
  const expiryStroke = isDark ? '#2dd4bf' : '#0d9488'
  const t0Stroke = isDark ? '#fb923c' : '#ea580c'
  const spotStroke = isDark ? '#34d399' : '#059669'
  const sellStroke = isDark ? '#64748b' : '#94a3b8'
  const hoverStroke = isDark ? '#94a3b8' : '#64748b'
  const beStroke = isDark ? '#475569' : '#cbd5e1'
  const textFill = isDark ? '#94a3b8' : '#64748b'

  const tooltipStyle = (() => {
    if (!pointer || !plotRef.current || !hover) {
      return undefined
    }
    const box = plotRef.current.getBoundingClientRect()
    const tipW = tooltipRef.current?.offsetWidth ?? 156
    const tipH = tooltipRef.current?.offsetHeight ?? 76
    let left = pointer.x + 14
    let top = pointer.y - 12
    if (left + tipW > box.width - 4) {
      left = pointer.x - tipW - 14
    }
    if (left < 4) {
      left = 4
    }
    if (top + tipH > box.height - 4) {
      top = box.height - tipH - 4
    }
    if (top < 4) {
      top = 4
    }
    return { left, top }
  })()

  const safeScenarioDays =
    scenarioDays != null && Number.isFinite(scenarioDays) ? scenarioDays : null
  const maxDays = Math.max(calendarDte ?? 0, safeScenarioDays ?? 0, 1)
  const displayDays = safeScenarioDays ?? calendarDte ?? 0

  return (
    <div className="flex min-h-0 flex-1 flex-col rounded-xl border border-slate-200/90 bg-white p-3 shadow-sm dark:border-slate-800/90 dark:bg-[#0D1119]">
      <div className="mb-2.5 flex shrink-0 flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-3">
          <h2 className="text-sm font-semibold tracking-tight text-slate-900 dark:text-slate-100">
            {chartTitle}
          </h2>
          <div className="hidden items-center gap-2.5 text-[10px] font-medium text-slate-500 sm:flex dark:text-slate-400">
            <span className="inline-flex items-center gap-1">
              <span className="h-0.5 w-3 rounded bg-teal-500" /> Expiry
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="h-0.5 w-3 border-b-2 border-dashed border-orange-500" /> Today (T0)
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="h-2 w-2 rounded-full bg-emerald-500" /> Spot
            </span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="hidden text-[10px] text-slate-400 md:inline">
            Scroll to zoom · double-click reset
          </span>
          {zoomed && (
            <button
              type="button"
              onClick={() => setView(null)}
              className="rounded-lg border border-slate-200 bg-slate-50 px-2 py-1 text-[10px] font-semibold text-slate-700 hover:bg-slate-100 dark:border-slate-700 dark:bg-[#131924] dark:text-slate-200"
            >
              Reset zoom
            </button>
          )}
        </div>
      </div>

      <div className="mb-2.5 grid shrink-0 grid-cols-2 gap-1.5 sm:grid-cols-5">
        <Stat
          label="Max profit"
          value={summary.maxProfit == null ? 'Unlimited' : formatMoney(summary.maxProfit)}
          tone="profit"
        />
        <Stat
          label="Max loss"
          value={summary.maxLoss == null ? 'Unlimited' : formatMoney(summary.maxLoss)}
          tone="loss"
        />
        <Stat label="POP (est.)" value={pop == null ? '—' : `${pop.toFixed(1)}%`} />
        <Stat
          label={premiumKind === 'credit' ? 'Net credit' : 'Net debit'}
          value={formatMoney(premiumTotal)}
          tone={premiumKind === 'credit' ? 'profit' : 'loss'}
        />
        <Stat label="Est. margin" value={margin == null ? '—' : formatMoney(margin)} />
        <Stat
          label="Breakevens"
          value={
            summary.breakevens.length === 0
              ? '—'
              : summary.breakevens.map((b) => formatAxisPrice(b)).join(', ')
          }
        />
        <Stat label="Risk : Reward" value={riskReward == null ? '—' : `1 : ${riskReward.toFixed(2)}`} />
        <Stat label="Spot" value={spot == null ? '—' : formatPrice(spot)} />
        <Stat label="DTE" value={calendarDte == null ? '—' : `${calendarDte}d`} />
        <Stat label="Legs" value={String(legs.length)} />
      </div>

      <div
        ref={plotRef}
        className="relative min-h-[220px] flex-1 overflow-hidden rounded-xl border border-slate-100 bg-slate-50/40 dark:border-slate-800/80 dark:bg-[#070A0F]/60"
      >
        {hover && tooltipStyle ? (
          <div
            ref={tooltipRef}
            className="pointer-events-none absolute z-20 min-w-[10rem] rounded-lg border border-slate-700/80 bg-slate-950/95 px-2.5 py-2 text-[11px] text-slate-100 shadow-xl backdrop-blur"
            style={{ left: tooltipStyle.left, top: tooltipStyle.top }}
          >
            <div className="font-num font-semibold text-slate-200">{formatPrice(hover.spot)}</div>
            <div className="mt-1 flex items-center justify-between gap-3">
              <span className="inline-flex items-center gap-1.5 text-slate-400">
                <span className="inline-block h-2 w-2 rounded-full bg-orange-400" /> Today
              </span>
              <span
                className={`font-num font-semibold ${
                  hover.t0Pnl >= 0 ? 'text-emerald-400' : 'text-rose-400'
                }`}
              >
                {formatMoney(hover.t0Pnl)}
              </span>
            </div>
            <div className="mt-0.5 flex items-center justify-between gap-3">
              <span className="inline-flex items-center gap-1.5 text-slate-400">
                <span className="inline-block h-2 w-2 rounded-full bg-teal-400" /> Expiry
              </span>
              <span
                className={`font-num font-semibold ${
                  hover.expiryPnl >= 0 ? 'text-emerald-400' : 'text-rose-400'
                }`}
              >
                {formatMoney(hover.expiryPnl)}
              </span>
            </div>
          </div>
        ) : null}

        <svg
          ref={chartSvgRef}
          viewBox={`0 0 ${width} ${height}`}
          preserveAspectRatio="none"
          className="absolute inset-0 h-full w-full select-none"
          onMouseLeave={() => {
            setHoverX(null)
            setPointer(null)
          }}
          onDoubleClick={(event) => {
            event.preventDefault()
            setView(null)
          }}
          onMouseMove={(event) => {
            const rect = event.currentTarget.getBoundingClientRect()
            const plotRect = plotRef.current?.getBoundingClientRect()
            const x = ((event.clientX - rect.left) / rect.width) * width
            if (x < pad.left || x > width - pad.right) {
              setHoverX(null)
              setPointer(null)
              return
            }
            setHoverX(x)
            if (plotRect) {
              setPointer({
                x: event.clientX - plotRect.left,
                y: event.clientY - plotRect.top,
              })
            }
          }}
        >
          <defs>
            <clipPath id="payoff-plot-clip">
              <rect x={pad.left} y={pad.top} width={innerW} height={innerH} />
            </clipPath>
            <linearGradient id="profitFill" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stopColor="#10b981" stopOpacity={isDark ? 0.35 : 0.28} />
              <stop offset="100%" stopColor="#10b981" stopOpacity="0.04" />
            </linearGradient>
            <linearGradient id="lossFill" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stopColor="#f43f5e" stopOpacity="0.04" />
              <stop offset="100%" stopColor="#f43f5e" stopOpacity={isDark ? 0.35 : 0.28} />
            </linearGradient>
          </defs>

          {yTicks.map((tick) => (
            <g key={`y-${tick}`}>
              <line
                x1={pad.left}
                x2={width - pad.right}
                y1={yScale(tick)}
                y2={yScale(tick)}
                stroke={axisStroke}
                strokeDasharray="3 4"
                strokeWidth="1"
              />
              <text
                x={pad.left - 8}
                y={yScale(tick) + 3}
                textAnchor="end"
                fill={textFill}
                className="font-num text-[10px]"
              >
                {formatAxisPnl(tick)}
              </text>
            </g>
          ))}

          {xTicks.map((tick) => (
            <g key={`x-${tick}`}>
              <line
                x1={xScale(tick)}
                x2={xScale(tick)}
                y1={pad.top}
                y2={height - pad.bottom}
                stroke={axisStroke}
                strokeDasharray="2 4"
                strokeWidth="1"
              />
              <text
                x={xScale(tick)}
                y={height - 16}
                textAnchor="middle"
                fill={textFill}
                className="font-num text-[10px]"
              >
                {formatAxisPrice(tick)}
              </text>
            </g>
          ))}

          <g clipPath="url(#payoff-plot-clip)">
            <path d={profitArea} fill="url(#profitFill)" />
            <path d={lossArea} fill="url(#lossFill)" />
            <line
              x1={pad.left}
              x2={width - pad.right}
              y1={zeroY}
              y2={zeroY}
              stroke={zeroStroke}
              strokeDasharray="4 4"
            />
            <path d={expiryPath} fill="none" stroke={expiryStroke} strokeWidth="2.25" />
            <path
              d={t0Path}
              fill="none"
              stroke={t0Stroke}
              strokeWidth="2"
              strokeDasharray="5 4"
            />

            {summary.breakevens
              .filter((be) => be >= viewMin && be <= viewMax)
              .map((be) => (
                <g key={be}>
                  <line
                    x1={xScale(be)}
                    x2={xScale(be)}
                    y1={pad.top}
                    y2={height - pad.bottom}
                    stroke={beStroke}
                    strokeDasharray="3 3"
                  />
                  <circle cx={xScale(be)} cy={zeroY} r="3.5" fill={zeroStroke} />
                </g>
              ))}

            {sellStrikes.map((leg) => (
              <line
                key={`sell-line-${leg.id}`}
                x1={xScale(leg.strike)}
                x2={xScale(leg.strike)}
                y1={pad.top}
                y2={height - pad.bottom}
                stroke={sellStroke}
                strokeWidth="1"
                strokeDasharray="6 5"
                opacity="0.75"
              />
            ))}

            {spot != null && spot >= viewMin && spot <= viewMax && (
              <g>
                <line
                  x1={xScale(spot)}
                  x2={xScale(spot)}
                  y1={pad.top}
                  y2={height - pad.bottom}
                  stroke={spotStroke}
                  strokeWidth="1.5"
                />
                <circle
                  cx={xScale(spot)}
                  cy={yScale(pnlAtSpot(legs, spot, scenario).expiryPnl)}
                  r="4"
                  fill={spotStroke}
                />
              </g>
            )}

            {hover && (
              <g>
                <line
                  x1={xScale(hover.spot)}
                  x2={xScale(hover.spot)}
                  y1={pad.top}
                  y2={height - pad.bottom}
                  stroke={hoverStroke}
                  strokeWidth="1"
                />
                <line
                  x1={pad.left}
                  x2={width - pad.right}
                  y1={yScale(hover.expiryPnl)}
                  y2={yScale(hover.expiryPnl)}
                  stroke={hoverStroke}
                  strokeWidth="1"
                  strokeDasharray="2 3"
                />
                <circle
                  cx={xScale(hover.spot)}
                  cy={yScale(hover.expiryPnl)}
                  r="4.5"
                  fill={expiryStroke}
                />
                <circle
                  cx={xScale(hover.spot)}
                  cy={yScale(hover.t0Pnl)}
                  r="4"
                  fill={t0Stroke}
                />
              </g>
            )}
          </g>

          {sellStrikes.map((leg, index) => {
            const stack = sellStrikes
              .slice(0, index)
              .filter((other) => Math.abs(other.strike - leg.strike) < viewSpan * 0.002)
              .length
            return (
              <text
                key={`sell-label-${leg.id}`}
                x={xScale(leg.strike)}
                y={height - pad.bottom + 14 + stack * 12}
                textAnchor="middle"
                fill={sellStroke}
                className="font-num text-[10px]"
              >
                {formatAxisPrice(leg.strike)}
              </text>
            )
          })}

          {spot != null && spot >= viewMin && spot <= viewMax && (
            <text
              x={xScale(spot)}
              y={pad.top - 4}
              textAnchor="middle"
              fill={spotStroke}
              className="font-num text-[10px] font-semibold"
            >
              {formatAxisPrice(spot)}
            </text>
          )}
        </svg>
      </div>

      <div className="mt-2.5 shrink-0 space-y-2">
        <div className="grid grid-cols-4 gap-1.5">
          <Stat label="Delta" value={formatGreek(greeks.delta, 2)} />
          <Stat label="Theta" value={formatGreek(greeks.theta, 2)} />
          <Stat label="Gamma" value={formatGreek(greeks.gamma, 4)} />
          <Stat label="Vega" value={formatGreek(greeks.vega, 2)} />
        </div>

        <div className="grid gap-3 rounded-xl border border-slate-200/70 bg-slate-50/60 px-3 py-2 sm:grid-cols-2 dark:border-slate-800/80 dark:bg-[#131924]">
          <div className="text-[10px] text-slate-500 dark:text-slate-400">
            <div className="mb-1.5 flex items-center justify-between">
              <span className="font-medium">Days to expiry</span>
              <div className="flex items-center gap-1.5">
                {calendarDte != null && (
                  <button
                    type="button"
                    onClick={() => setScenarioDays(calendarDte)}
                    className="rounded px-1.5 py-0.5 text-[9px] font-semibold text-blue-600 hover:bg-blue-500/10 dark:text-blue-400"
                  >
                    Today
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => setScenarioDays(0)}
                  className="rounded px-1.5 py-0.5 text-[9px] font-semibold text-blue-600 hover:bg-blue-500/10 dark:text-blue-400"
                >
                  Expiry
                </button>
                <span className="font-num font-semibold text-slate-800 dark:text-slate-100">
                  {displayDays}d
                </span>
              </div>
            </div>
            <input
              type="range"
              min={0}
              max={maxDays}
              step={1}
              value={displayDays}
              onChange={(e) => setScenarioDays(Number(e.target.value))}
              className="desk-slider w-full"
            />
          </div>

          <div className="text-[10px] text-slate-500 dark:text-slate-400">
            <div className="mb-1.5 flex items-center justify-between">
              <span className="font-medium">Implied volatility</span>
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setIvScalePct(100)}
                  className="rounded px-1.5 py-0.5 text-[9px] font-semibold text-amber-600 hover:bg-amber-500/10 dark:text-amber-400"
                >
                  Reset 100%
                </button>
                <span className="font-num font-semibold text-slate-800 dark:text-slate-100">
                  {ivScalePct}%{baseIv != null ? ` · ${(baseIv * ivScalePct).toFixed(1)}%` : ''}
                </span>
              </div>
            </div>
            <input
              type="range"
              min={50}
              max={200}
              step={1}
              value={ivScalePct}
              onChange={(e) => setIvScalePct(Number(e.target.value))}
              className="desk-slider desk-slider-amber w-full"
            />
          </div>
        </div>
      </div>
    </div>
  )
})

function Stat({
  label,
  value,
  tone,
}: {
  label: string
  value: string
  tone?: 'profit' | 'loss'
}) {
  const color =
    tone === 'profit'
      ? 'text-emerald-600 dark:text-emerald-400'
      : tone === 'loss'
        ? 'text-rose-600 dark:text-rose-400'
        : 'text-slate-900 dark:text-slate-100'
  return (
    <div className="rounded-lg border border-slate-200/80 bg-slate-50/70 px-2.5 py-1.5 dark:border-slate-800/80 dark:bg-[#131924]">
      <div className="text-[9px] font-medium uppercase tracking-wider text-slate-500 dark:text-slate-400">
        {label}
      </div>
      <div className={`font-num text-xs font-semibold ${color}`}>{value}</div>
    </div>
  )
}
