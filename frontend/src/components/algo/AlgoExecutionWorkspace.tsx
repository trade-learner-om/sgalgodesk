import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ApiError,
  api,
  type AlgoExecutionMode,
  type AlgoLegConfig,
  type AlgoStrategyEvent,
  type AlgoStrategyLifecycle,
  type AlgoStrategySummary,
  type AlgoStrategyTrade,
} from '../../api/client'
import { useMarketFeed } from '../../hooks/useMarketFeed'
import type { StrategyLeg } from '../../payoff/payoff'
import { useFeedback } from '../Feedback'
import { PayoffChart } from '../PayoffChart'
import { EmptyState, StatTile } from '../ui/Primitives'
import {
  algoTradeToLeg,
  distanceToSl,
  legSlWidth,
  openPremiumPointPnl,
  openTradesUnrealised,
  overallSlDistancePoints,
  resolveLegSlTrigger,
  slDistanceTone,
  tradeLivePnl,
  underlyingKeyForIndex,
} from './algoTradeToLeg'
import { classifyAlgoStructure } from './algoStructureClassify'
import { StrategyTitle } from './BiasIcon'
import { StrategyCreator } from './StrategyCreator'
import { StrategyLifecycleDrawer } from './StrategyLifecycleDrawer'
import {
  formatIstClock,
  formatLifecycleState,
  istTodayAt,
  istTodayKey,
  useIstCountdown,
} from './useIstCountdown'

function formatPrice(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  return value.toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function formatPnl(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  return value.toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function riskText(
  enabled: boolean,
  unit: string | null | undefined,
  value: number | null | undefined,
): string {
  if (!enabled || value == null) return 'Off'
  return `${value} ${unit ?? ''}`.trim()
}

function eventTone(type: string): string {
  const t = type.toUpperCase()
  if (t.includes('ERROR') || t.includes('FAILED')) {
    return 'text-rose-600 dark:text-rose-400'
  }
  if (t.includes('SL') || t.includes('EXIT') || t.includes('STOP')) {
    return 'text-amber-600 dark:text-amber-400'
  }
  if (t.includes('ENTRY') || t.includes('STARTED') || t.includes('FILLED')) {
    return 'text-emerald-600 dark:text-emerald-400'
  }
  return 'text-slate-700 dark:text-slate-200'
}

function LegReadonlyRow({ leg, index }: { leg: AlgoLegConfig; index: number }) {
  const sell = (leg.position ?? 'Sell').toUpperCase().startsWith('S')
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-lg border border-slate-200/80 bg-white px-3 py-2 dark:border-slate-800 dark:bg-[#070A0F]">
      <span className="text-[10px] font-bold uppercase tracking-wide text-slate-400">
        L{index + 1}
      </span>
      <span
        className={`rounded px-1.5 py-0.5 text-[10px] font-bold uppercase ${
          sell
            ? 'bg-rose-500/15 text-rose-600 dark:text-rose-400'
            : 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
        }`}
      >
        {sell ? 'Sell' : 'Buy'}
      </span>
      <span className="text-xs font-bold text-slate-900 dark:text-slate-100">
        {leg.optionType ?? 'CE'}
      </span>
      <span className="rounded bg-blue-500/10 px-1.5 py-0.5 text-[11px] font-semibold text-blue-700 dark:text-blue-300">
        {leg.strikeCriteria ?? 'ATM'}
      </span>
      <span className="text-xs font-medium text-slate-600 dark:text-slate-300">
        {leg.expiry ?? 'Weekly'}
      </span>
      <span className="text-xs text-slate-500">
        {leg.lots} lot{leg.lots === 1 ? '' : 's'}
      </span>
      <span className="ml-auto flex flex-wrap gap-2 text-[11px]">
        <span
          className={
            leg.slEnabled
              ? 'font-semibold text-rose-600 dark:text-rose-400'
              : 'text-slate-400'
          }
        >
          SL {riskText(leg.slEnabled, leg.slUnit, leg.slValue)}
        </span>
        <span
          className={
            leg.targetEnabled
              ? 'font-semibold text-emerald-600 dark:text-emerald-400'
              : 'text-slate-400'
          }
        >
          Tgt {riskText(leg.targetEnabled, leg.targetUnit, leg.targetValue)}
        </span>
      </span>
    </div>
  )
}

function isClosedTrade(t: AlgoStrategyTrade): boolean {
  const s = (t.status ?? '').toUpperCase()
  return s === 'CLOSED' || s.includes('CLOSED')
}

function closedReasonChip(reason: string | null | undefined): string {
  const r = (reason ?? '').toUpperCase()
  if (r.includes('LEG_SL') || r === 'SL' || r.includes('SOFTWARE_SL')) return 'SL'
  if (r.includes('OVERALL_SL')) return 'Overall SL'
  if (r.includes('TARGET')) return 'Target'
  if (r.includes('TRAIL')) return 'Trail'
  if (r.includes('STOP') || r.includes('MANUAL')) return 'Stopped'
  return 'Closed'
}

function OpenPositionsBlock({
  trades,
  prices,
  legs,
}: {
  trades: AlgoStrategyTrade[]
  prices: Record<string, number>
  legs: AlgoLegConfig[]
}) {
  const rows = useMemo(() => {
    const open = trades.filter((t) => t.status === 'OPEN')
    const closed = trades
      .filter(isClosedTrade)
      .slice()
      .sort((a, b) => {
        const ta = a.closedAt ? Date.parse(a.closedAt) : 0
        const tb = b.closedAt ? Date.parse(b.closedAt) : 0
        return ta - tb
      })
    return [...open, ...closed]
  }, [trades])
  if (rows.length === 0) return null
  return (
    <div className="space-y-1.5">
      <div className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
        Positions
      </div>
      <ul className="space-y-1.5">
        {rows.map((t) => {
          const buy = t.side.toUpperCase().startsWith('B')
          const closed = isClosedTrade(t)
          const absQty = Math.abs(t.quantity)
          const signedQty = buy ? absQty : -absQty
          if (closed) {
            const exit = t.exitPrice != null && t.exitPrice > 0 ? t.exitPrice : null
            const pnl = t.pnl
            return (
              <li
                key={t.id}
                className="flex flex-wrap items-center gap-x-1.5 gap-y-1 rounded-lg border border-slate-200/60 bg-slate-50/80 px-3 py-2 text-[11px] opacity-80 dark:border-slate-800/80 dark:bg-[#070A0F]/70"
              >
                <span
                  className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] font-bold uppercase ${
                    buy
                      ? 'bg-emerald-500/10 text-emerald-600/80 dark:text-emerald-400/80'
                      : 'bg-rose-500/10 text-rose-600/80 dark:text-rose-400/80'
                  }`}
                >
                  {buy ? 'Buy' : 'Sell'}
                </span>
                <span className="min-w-0 truncate font-semibold text-slate-600 dark:text-slate-300">
                  {t.tradingSymbol ?? t.instrumentKey}
                </span>
                <span className="shrink-0 font-num text-slate-500">{signedQty}</span>
                <span className="shrink-0 font-num text-slate-500">
                  @ {formatPrice(t.entryPrice)}
                </span>
                <span
                  className="inline-block h-1 w-1 shrink-0 rounded-full bg-slate-400 dark:bg-slate-500"
                  aria-hidden
                />
                <span className="shrink-0 font-num tabular-nums text-slate-500">
                  {exit != null ? formatPrice(exit) : '—'}
                </span>
                <span className="shrink-0 rounded bg-slate-500/15 px-1.5 py-0.5 text-[10px] font-bold uppercase text-slate-600 dark:text-slate-300">
                  {closedReasonChip(t.triggerReason)}
                </span>
                <span
                  className={`ml-auto min-w-[4.5rem] shrink-0 text-right font-num font-semibold tabular-nums ${
                    pnl >= 0
                      ? 'text-emerald-600/80 dark:text-emerald-400/80'
                      : 'text-rose-600/80 dark:text-rose-400/80'
                  }`}
                >
                  {formatPnl(pnl)}
                </span>
              </li>
            )
          }
          const ltp = prices[t.instrumentKey]
          const mark = typeof ltp === 'number' && ltp > 0 ? ltp : t.entryPrice
          const pnl = tradeLivePnl(t, prices)
          const legCfg = legs[t.legIndex] ?? null
          const slTrigger = resolveLegSlTrigger(t, legCfg)
          const slDist = distanceToSl(t.side, mark, slTrigger)
          const slTone = slDistanceTone(slDist, legSlWidth(t.entryPrice, slTrigger))
          return (
            <li
              key={t.id}
              className="flex flex-wrap items-center gap-x-1.5 gap-y-1 rounded-lg border border-slate-200/80 bg-white px-3 py-2 text-[11px] dark:border-slate-800 dark:bg-[#070A0F]"
            >
              <span
                className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] font-bold uppercase ${
                  buy
                    ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
                    : 'bg-rose-500/15 text-rose-600 dark:text-rose-400'
                }`}
              >
                {buy ? 'Buy' : 'Sell'}
              </span>
              <span className="min-w-0 truncate font-semibold text-slate-800 dark:text-slate-100">
                {t.tradingSymbol ?? t.instrumentKey}
              </span>
              <span className="shrink-0 font-num text-slate-600 dark:text-slate-300">
                {signedQty}
              </span>
              <span className="shrink-0 font-num text-slate-500">
                @ {formatPrice(t.entryPrice)}
              </span>
              <span
                className="inline-block h-1 w-1 shrink-0 rounded-full bg-slate-400 dark:bg-slate-500"
                aria-hidden
              />
              <span className="shrink-0 font-num tabular-nums text-slate-700 dark:text-slate-200">
                {formatPrice(mark)}
              </span>
              <span
                className="inline-block h-1 w-1 shrink-0 rounded-full bg-slate-400 dark:bg-slate-500"
                aria-hidden
              />
              <span className="shrink-0 font-num tabular-nums text-slate-500">
                SL {slTrigger != null ? formatPrice(slTrigger) : '—'}
              </span>
              <span className={`shrink-0 ${slTone.className} ${slTone.breatheClass}`}>
                Δ {slDist != null ? formatPnl(slDist) : '—'}
              </span>
              <span
                className={`ml-auto min-w-[4.5rem] shrink-0 text-right font-num font-semibold tabular-nums ${
                  pnl >= 0
                    ? 'text-emerald-600 dark:text-emerald-400'
                    : 'text-rose-600 dark:text-rose-400'
                }`}
              >
                {formatPnl(pnl)}
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

function StrategyCard({
  strategy,
  onEdit,
  onDelete,
  onRun,
  onStop,
  onInspectLifecycle,
  selected,
  onSelect,
  liveUnrealised,
  collapsible = true,
  prices = {},
}: {
  strategy: AlgoStrategySummary
  onEdit?: () => void
  onDelete?: () => void
  onRun?: () => void
  onStop?: () => void
  onInspectLifecycle?: () => void
  selected?: boolean
  onSelect?: () => void
  liveUnrealised?: number | null
  collapsible?: boolean
  prices?: Record<string, number>
}) {
  const [expanded, setExpanded] = useState(false)
  const [lifecycle, setLifecycle] = useState<AlgoStrategyLifecycle | null>(null)
  const [lifeLoading, setLifeLoading] = useState(false)

  const run = strategy.activeRun
  const waiting = run?.lifecycleState === 'WAITING_ENTRY'
  const active = run?.lifecycleState === 'ACTIVE'
  const structure = useMemo(
    () => classifyAlgoStructure(strategy.config.legs),
    [strategy.config.legs],
  )
  const todayKey = istTodayKey()
  const runDateKey = run?.runDate ?? null
  const exitPastToday = useMemo(() => {
    const exitAt = istTodayAt(strategy.config.entry.exitTime)
    return exitAt != null && Date.now() >= exitAt.getTime()
  }, [strategy.config.entry.exitTime])
  const nextDeployment =
    waiting && (Boolean(runDateKey && runDateKey > todayKey) || exitPastToday)
  const countdownTarget = waiting
    ? strategy.config.entry.entryTime
    : active
      ? strategy.config.entry.exitTime
      : null
  const countdown = useIstCountdown(
    strategy.status === 'RUNNING' ? countdownTarget : null,
    { rollIfPast: nextDeployment },
  )
  const openTrades = useMemo(
    () => (strategy.activeTrades ?? []).filter((t) => t.status === 'OPEN'),
    [strategy.activeTrades],
  )
  const runPositionTrades = useMemo(
    () =>
      (strategy.activeTrades ?? []).filter(
        (t) => t.status === 'OPEN' || isClosedTrade(t),
      ),
    [strategy.activeTrades],
  )
  const closedRealisedPnl = useMemo(() => {
    const closed = (strategy.activeTrades ?? []).filter(isClosedTrade)
    if (closed.length === 0) return null
    let sum = 0
    for (const t of closed) sum += t.pnl
    return Math.round(sum * 100) / 100
  }, [strategy.activeTrades])

  useEffect(() => {
    if (!collapsible || !expanded) return
    let cancelled = false
    setLifeLoading(true)
    void api.algoStrategies
      .lifecycle(strategy.id)
      .then((data) => {
        if (!cancelled) setLifecycle(data)
      })
      .catch(() => {
        if (!cancelled) setLifecycle(null)
      })
      .finally(() => {
        if (!cancelled) setLifeLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [collapsible, expanded, strategy.id])

  const overall = strategy.config.overall
  const events: AlgoStrategyEvent[] = lifecycle?.events?.slice(0, 12) ?? []

  const unrealisedDisplay =
    liveUnrealised != null ? liveUnrealised : run?.unrealisedPnl

  const openUnrealised =
    liveUnrealised != null ? liveUnrealised : openTrades.length > 0 ? 0 : null
  const displayTotalPnl = (() => {
    const u = openUnrealised ?? 0
    const r = closedRealisedPnl ?? 0
    if (openUnrealised == null && closedRealisedPnl == null) {
      return strategy.totalPnl
    }
    return Math.round((u + r) * 100) / 100
  })()
  const showOpenClosedSplit =
    openUnrealised != null || closedRealisedPnl != null
  const overallSlDist = overallSlDistancePoints(
    overall.overallSlEnabled,
    overall.overallSlType,
    overall.overallSlValue,
    openPremiumPointPnl(openTrades, prices),
  )
  const overallSlTone = slDistanceTone(
    overallSlDist,
    overall.overallSlEnabled && overall.overallSlValue != null
      ? Math.abs(overall.overallSlValue)
      : null,
  )

  function toggleExpand() {
    if (!collapsible) return
    setExpanded((v) => !v)
  }

  function handleHeaderClick() {
    if (collapsible) toggleExpand()
    onSelect?.()
  }

  const headerInteractive = collapsible || Boolean(onSelect)
  const modeLabel = (run?.executionMode ?? strategy.executionMode ?? 'PAPER').toUpperCase()

  return (
    <div
      className={`rounded-xl border bg-white shadow-xs transition dark:bg-[#0D1119] ${
        selected
          ? 'border-blue-500 ring-1 ring-blue-500/40 dark:border-blue-400'
          : 'border-slate-200/90 hover:border-slate-300 dark:border-slate-800 dark:hover:border-slate-700'
      }`}
    >
      <div
        className={`flex items-center gap-2.5 px-3.5 py-3 ${headerInteractive ? 'cursor-pointer' : ''}`}
        onClick={handleHeaderClick}
        onKeyDown={(e) => {
          if (!headerInteractive) return
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            handleHeaderClick()
          }
        }}
        role={headerInteractive ? 'button' : undefined}
        tabIndex={headerInteractive ? 0 : undefined}
        aria-expanded={collapsible ? expanded : undefined}
      >
        {collapsible && (
          <span aria-hidden className="rounded-md p-0.5 text-slate-400">
            <svg
              viewBox="0 0 20 20"
              className={`h-4 w-4 transition-transform ${expanded ? 'rotate-180' : ''}`}
              fill="currentColor"
            >
              <path
                d="M5.5 7.5 10 12l4.5-4.5"
                stroke="currentColor"
                strokeWidth="1.8"
                fill="none"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </span>
        )}
        <div className="min-w-0 flex-1">
          <StrategyTitle
            name={strategy.name}
            structureLabel={structure.label}
            bias={structure.bias}
            size="sm"
          />
          <p className="mt-1 truncate text-[11px] leading-tight text-slate-500 dark:text-slate-400">
            {strategy.config.instrument.index} · {strategy.config.entry.entryTime}–
            {strategy.config.entry.exitTime} · {strategy.config.legs.length} leg
            {strategy.config.legs.length === 1 ? '' : 's'}
          </p>
        </div>
        <div className="flex shrink-0 flex-col items-end justify-center gap-1">
          <div className="flex items-center gap-1.5">
            {strategy.status === 'RUNNING' && (
              <span
                className={`rounded px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide leading-none ${
                  modeLabel === 'LIVE'
                    ? 'bg-amber-500/15 text-amber-700 dark:text-amber-300'
                    : 'bg-blue-500/12 text-blue-700 dark:text-blue-300'
                }`}
              >
                {modeLabel}
              </span>
            )}
            <span
              className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide leading-none ${
                strategy.status === 'RUNNING'
                  ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
                  : 'bg-slate-200/80 text-slate-600 dark:bg-slate-800 dark:text-slate-300'
              }`}
            >
              {strategy.status === 'RUNNING' && (
                <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
              )}
              {strategy.status}
            </span>
          </div>
          {collapsible && !expanded && (
            <span
              className={`font-num text-sm font-bold tabular-nums leading-tight ${
                (displayTotalPnl ?? 0) >= 0
                  ? 'text-emerald-600 dark:text-emerald-400'
                  : 'text-rose-600 dark:text-rose-400'
              }`}
            >
              {formatPnl(displayTotalPnl)}
            </span>
          )}
        </div>
      </div>

      {collapsible && expanded && (
        <div className="mx-3.5 mb-3 grid grid-cols-2 gap-2 rounded-xl border border-slate-200/70 bg-slate-50/80 p-3 text-[11px] dark:border-slate-800 dark:bg-[#131924]">
          <div>
            <span className="text-slate-500">Trades</span>
            <div className="font-num font-semibold text-slate-800 dark:text-slate-100">
              {strategy.totalTrades ?? 0}
              <span className="ml-1 font-normal text-slate-500">
                ({strategy.tradesToday ?? 0} today)
              </span>
            </div>
          </div>
          <div>
            <span className="text-slate-500">Total PnL</span>
            <div
              className={`font-num text-base font-bold tabular-nums ${
                (displayTotalPnl ?? 0) >= 0
                  ? 'text-emerald-600 dark:text-emerald-400'
                  : 'text-rose-600 dark:text-rose-400'
              }`}
            >
              {formatPnl(displayTotalPnl)}
            </div>
            {showOpenClosedSplit && (
              <div className="mt-0.5 space-x-2 text-[10px] text-slate-500">
                {openUnrealised != null && (
                  <span>
                    Open{' '}
                    <span className="font-num font-semibold tabular-nums">
                      {formatPnl(openUnrealised)}
                    </span>
                  </span>
                )}
                {closedRealisedPnl != null && (
                  <span>
                    Closed{' '}
                    <span className="font-num font-semibold tabular-nums">
                      {formatPnl(closedRealisedPnl)}
                    </span>
                  </span>
                )}
              </div>
            )}
          </div>
          <div>
            <span className="text-slate-500">Today PnL</span>
            <div className="font-num font-semibold text-slate-800 dark:text-slate-100">
              {formatPnl(strategy.todayPnl)}
            </div>
          </div>
          <div>
            <span className="text-slate-500">{run ? 'Session' : 'Mode'}</span>
            <div className="font-semibold text-slate-800 dark:text-slate-100">
              {run ? formatLifecycleState(run.lifecycleState) : strategy.executionMode}
            </div>
          </div>
          {run && (
            <>
              <div>
                <span className="text-slate-500">Unrealised</span>
                <div className="font-num font-semibold text-slate-800 dark:text-slate-100">
                  {formatPnl(unrealisedDisplay)}
                </div>
              </div>
              <div>
                <span className="text-slate-500">Realised</span>
                <div className="font-num font-semibold text-slate-800 dark:text-slate-100">
                  {formatPnl(run.realisedPnl)}
                </div>
              </div>
            </>
          )}
          {strategy.status === 'RUNNING' && run && (
            <div className="col-span-2 grid grid-cols-2 gap-2 border-t border-slate-200/70 pt-2 dark:border-slate-700/80">
              <div>
                <span className="text-slate-500">
                  {nextDeployment ? 'Next session' : waiting ? 'Armed' : 'Started'}
                </span>
                <div className="font-semibold text-slate-800 dark:text-slate-100">
                  {nextDeployment && runDateKey
                    ? runDateKey
                    : formatIstClock(run.startedAt)}
                </div>
              </div>
              <div>
                <span className="text-slate-500">
                  {nextDeployment
                    ? 'Next deployment'
                    : waiting
                      ? countdown.expired
                        ? 'Status'
                        : 'Time to entry'
                      : countdown.expired
                        ? 'Status'
                        : 'Time to exit'}
                </span>
                <div
                  className={`font-num text-base font-semibold tabular-nums ${
                    countdown.expired && !nextDeployment
                      ? 'text-amber-600 dark:text-amber-400'
                      : 'text-slate-900 dark:text-slate-50'
                  }`}
                >
                  {nextDeployment
                    ? countdown.expired
                      ? '—'
                      : countdown.label
                    : countdown.expired
                      ? waiting
                        ? 'Entering…'
                        : 'Exiting…'
                      : countdown.label}
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {!collapsible && (
        <div className="mx-3.5 mb-3 space-y-0">
          {runPositionTrades.length > 0 && (
            <OpenPositionsBlock
              trades={runPositionTrades}
              prices={prices}
              legs={strategy.config.legs}
            />
          )}
          <div
            className={`flex items-center justify-between gap-3 px-1 ${
              runPositionTrades.length > 0
                ? 'mt-2 border-t border-slate-200/80 pt-2 dark:border-slate-700/80'
                : ''
            }`}
          >
            <div>
              <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                Total PnL
              </span>
              <div
                className={`font-num text-base font-bold tabular-nums ${
                  (displayTotalPnl ?? 0) >= 0
                    ? 'text-emerald-600 dark:text-emerald-400'
                    : 'text-rose-600 dark:text-rose-400'
                }`}
              >
                {formatPnl(displayTotalPnl)}
              </div>
              {showOpenClosedSplit && (
                <div className="mt-0.5 space-x-2 text-[10px] text-slate-500">
                  {openUnrealised != null && (
                    <span>
                      Open{' '}
                      <span className="font-num font-semibold tabular-nums">
                        {formatPnl(openUnrealised)}
                      </span>
                    </span>
                  )}
                  {closedRealisedPnl != null && (
                    <span>
                      Closed{' '}
                      <span className="font-num font-semibold tabular-nums">
                        {formatPnl(closedRealisedPnl)}
                      </span>
                    </span>
                  )}
                </div>
              )}
            </div>
            <div className="text-right">
              <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                Overall SL Δ
              </span>
              <div
                className={`mt-0.5 inline-flex ${overallSlTone.className} ${overallSlTone.breatheClass}`}
              >
                {overallSlDist != null ? `${formatPnl(overallSlDist)} pts` : '—'}
              </div>
            </div>
          </div>
        </div>
      )}

      {collapsible && expanded && (
        <div className="space-y-3 border-t border-slate-200/80 px-3.5 py-3 dark:border-slate-800">
          <div className="grid grid-cols-2 gap-2">
            <div className="rounded-lg bg-rose-500/10 px-3 py-2">
              <div className="text-[10px] font-semibold uppercase tracking-wide text-rose-500/80">
                Overall SL
              </div>
              <div className="text-xs font-bold text-rose-700 dark:text-rose-300">
                {riskText(
                  overall.overallSlEnabled,
                  overall.overallSlType,
                  overall.overallSlValue,
                )}
              </div>
            </div>
            <div className="rounded-lg bg-emerald-500/10 px-3 py-2">
              <div className="text-[10px] font-semibold uppercase tracking-wide text-emerald-500/80">
                Overall target
              </div>
              <div className="text-xs font-bold text-emerald-700 dark:text-emerald-300">
                {riskText(
                  overall.overallTargetEnabled,
                  overall.overallTargetType,
                  overall.overallTargetValue,
                )}
              </div>
            </div>
          </div>

          <div className="space-y-1.5">
            <div className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
              Legs
            </div>
            {strategy.config.legs.map((leg, i) => (
              <LegReadonlyRow key={i} leg={leg} index={i} />
            ))}
          </div>

          <div className="space-y-1.5">
            <div className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
              Recent events
            </div>
            {run && (
              <div className="mb-2 flex flex-wrap gap-3 text-[11px]">
                <span className="font-semibold text-slate-800 dark:text-slate-100">
                  {formatLifecycleState(run.lifecycleState)}
                </span>
                <span className="text-slate-500">
                  Started {formatIstClock(run.startedAt)}
                </span>
                {run.endedAt && (
                  <span className="text-slate-500">
                    Ended {formatIstClock(run.endedAt)}
                  </span>
                )}
              </div>
            )}
            {lifeLoading && <p className="text-xs text-slate-400">Loading events…</p>}
            {!lifeLoading && events.length === 0 && (
              <p className="text-xs text-slate-400">No lifecycle events yet.</p>
            )}
            <ul className="desk-scroll max-h-44 space-y-1.5 overflow-auto">
              {events.map((ev) => (
                <li
                  key={ev.id}
                  className="flex gap-2 rounded-md bg-slate-50/90 px-2 py-1.5 text-[11px] dark:bg-[#131924]"
                >
                  <span className="shrink-0 font-num font-medium tabular-nums text-slate-400">
                    {formatIstClock(ev.createdAt)}
                  </span>
                  <span className={`shrink-0 font-bold ${eventTone(ev.eventType)}`}>
                    {formatLifecycleState(ev.eventType)}
                  </span>
                  <span className="min-w-0 truncate text-slate-600 dark:text-slate-300">
                    {ev.message}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {(onEdit || onDelete || onRun || onStop || onInspectLifecycle) && (
        <div
          className="flex flex-wrap items-center gap-2 border-t border-slate-200/70 px-3.5 py-2.5 dark:border-slate-800"
          onClick={(e) => e.stopPropagation()}
        >
          {onEdit && strategy.status !== 'RUNNING' && (
            <button
              type="button"
              onClick={onEdit}
              className="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-700 transition hover:bg-slate-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
            >
              Edit
            </button>
          )}
          {onInspectLifecycle && (
            <button
              type="button"
              onClick={onInspectLifecycle}
              className="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-700 transition hover:bg-slate-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
            >
              Trades &amp; Logs
            </button>
          )}
          {onDelete && (
            <button
              type="button"
              onClick={onDelete}
              className="rounded-lg border border-rose-200 px-2.5 py-1.5 text-xs font-medium text-rose-600 transition hover:bg-rose-50 dark:border-rose-900/60 dark:text-rose-400 dark:hover:bg-rose-950/30"
            >
              Delete
            </button>
          )}
          <div className="ml-auto flex items-center gap-2">
            {onRun && (
              <button
                type="button"
                onClick={onRun}
                className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-blue-500"
              >
                Run
              </button>
            )}
            {onStop && (
              <button
                type="button"
                onClick={onStop}
                className="rounded-lg bg-rose-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-rose-500"
              >
                Stop
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

export function AlgoExecutionWorkspace() {
  const { confirm, toast } = useFeedback()
  const [strategies, setStrategies] = useState<AlgoStrategySummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<AlgoStrategySummary | null>(null)
  const [runChooserId, setRunChooserId] = useState<string | null>(null)
  const [stopConfirm, setStopConfirm] = useState<AlgoStrategySummary | null>(null)
  const [lifecycleTarget, setLifecycleTarget] = useState<AlgoStrategySummary | null>(null)
  const [selectedRunningId, setSelectedRunningId] = useState<string | null>(null)
  const [prices, setPrices] = useState<Record<string, number>>({})

  const refresh = useCallback(async () => {
    try {
      const list = await api.algoStrategies.list()
      setStrategies(list)
      setError(null)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load strategies')
    }
  }, [])

  useEffect(() => {
    void refresh()
    const id = window.setInterval(() => void refresh(), 15000)
    return () => window.clearInterval(id)
  }, [refresh])

  const saved = useMemo(() => strategies, [strategies])
  const running = useMemo(
    () => strategies.filter((s) => s.status === 'RUNNING'),
    [strategies],
  )
  const runningWithOpen = useMemo(
    () =>
      running.filter((s) =>
        (s.activeTrades ?? []).some((t) => t.status === 'OPEN'),
      ),
    [running],
  )

  useEffect(() => {
    if (runningWithOpen.length === 0) {
      setSelectedRunningId(null)
      return
    }
    setSelectedRunningId((prev) => {
      if (prev && runningWithOpen.some((s) => s.id === prev)) return prev
      return runningWithOpen[0]!.id
    })
  }, [runningWithOpen])

  const selectedRunning = useMemo(
    () => running.find((s) => s.id === selectedRunningId) ?? null,
    [running, selectedRunningId],
  )

  const feedKeys = useMemo(() => {
    const keys = new Set<string>()
    for (const s of running) {
      keys.add(underlyingKeyForIndex(s.config.instrument.index))
      for (const t of s.activeTrades ?? []) {
        if (t.status === 'OPEN' && t.instrumentKey) keys.add(t.instrumentKey)
      }
    }
    return [...keys]
  }, [running])

  useMarketFeed({
    instrumentKeys: feedKeys,
    enabled: feedKeys.length > 0,
    onBatch: (ticks) => {
      setPrices((prev) => {
        const next = { ...prev }
        for (const t of ticks) {
          if (t.ltp > 0) next[t.instrumentKey] = t.ltp
        }
        return next
      })
    },
  })

  const selectedUnderlyingKey = selectedRunning
    ? underlyingKeyForIndex(selectedRunning.config.instrument.index)
    : null
  const selectedSpot =
    selectedUnderlyingKey && prices[selectedUnderlyingKey] != null
      ? prices[selectedUnderlyingKey]!
      : null

  const payoffLegs: StrategyLeg[] = useMemo(() => {
    if (!selectedRunning || !selectedUnderlyingKey) return []
    const underSym = selectedRunning.config.instrument.index ?? 'NIFTY'
    const out: StrategyLeg[] = []
    for (const t of selectedRunning.activeTrades ?? []) {
      const leg = algoTradeToLeg(
        t,
        selectedSpot,
        prices,
        selectedUnderlyingKey,
        underSym,
      )
      if (leg) out.push(leg)
    }
    return out
  }, [selectedRunning, selectedUnderlyingKey, selectedSpot, prices])

  const runningSummary = useMemo(() => {
    let liveCount = 0
    let paperCount = 0
    let totalPnl = 0
    for (const s of running) {
      const mode = (s.activeRun?.executionMode ?? s.executionMode ?? 'PAPER').toUpperCase()
      if (mode === 'LIVE') liveCount++
      else paperCount++
      const openU = openTradesUnrealised(s.activeTrades, prices) ?? 0
      const closedR = (s.activeTrades ?? [])
        .filter(isClosedTrade)
        .reduce((acc, t) => acc + t.pnl, 0)
      totalPnl += openU + closedR
    }
    return { liveCount, paperCount, totalPnl: Math.round(totalPnl * 100) / 100 }
  }, [running, prices])

  async function runStrategy(id: string, mode: AlgoExecutionMode) {
    setBusy(true)
    setError(null)
    try {
      await api.algoStrategies.run(id, mode)
      setRunChooserId(null)
      toast(`Strategy started in ${mode} mode`, 'success')
      await refresh()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not start strategy')
    } finally {
      setBusy(false)
    }
  }

  async function confirmStop() {
    if (!stopConfirm) return
    setBusy(true)
    setError(null)
    try {
      await api.algoStrategies.stop(stopConfirm.id)
      toast(`Stopped ${stopConfirm.name}`, 'info')
      setStopConfirm(null)
      await refresh()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not stop strategy')
    } finally {
      setBusy(false)
    }
  }

  async function deleteStrategy(strategy: AlgoStrategySummary) {
    const ok = await confirm({
      title: `Delete ${strategy.name}?`,
      message: 'This removes the saved strategy configuration. Past run history will no longer appear here.',
      confirmLabel: 'Delete',
      tone: 'danger',
    })
    if (!ok) return
    setBusy(true)
    try {
      await api.algoStrategies.delete(strategy.id)
      toast(`Deleted ${strategy.name}`, 'info')
      await refresh()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not delete strategy')
    } finally {
      setBusy(false)
    }
  }

  if (editorOpen) {
    return (
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
        <StrategyCreator
          initial={editing}
          onClose={() => {
            setEditorOpen(false)
            setEditing(null)
          }}
          onSaved={() => {
            toast(editing ? 'Strategy updated' : 'Strategy saved', 'success')
            void refresh()
          }}
        />
      </div>
    )
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden bg-[#F4F7FB] dark:bg-[#070A0F]">
      {/* Header + KPI Strip */}
      <div className="shrink-0 border-b border-slate-200/80 bg-white px-4 py-3 dark:border-slate-800/80 dark:bg-[#0D1119]">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
              Algo Execution
            </h2>
            <p className="text-[11px] text-slate-500 dark:text-slate-400">
              Create rule-based strategies, deploy Paper or Live, and monitor live payoff &amp; lifecycle.
            </p>
          </div>
          <button
            type="button"
            onClick={() => {
              setEditing(null)
              setEditorOpen(true)
            }}
            className="rounded-xl bg-gradient-to-r from-blue-600 to-indigo-600 px-3.5 py-2 text-xs font-semibold text-white shadow-sm shadow-blue-500/20 transition hover:brightness-110"
          >
            + Create Strategy
          </button>
        </div>

        <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
          <StatTile label="Saved Strategies" value={saved.length} />
          <StatTile
            label="Running Now"
            value={running.length}
            tone={running.length > 0 ? 'accent' : 'neutral'}
          />
          <StatTile
            label="Active Modes"
            value={`${runningSummary.liveCount} Live · ${runningSummary.paperCount} Paper`}
          />
          <StatTile
            label="Running MTM P&L"
            value={running.length > 0 ? formatPnl(runningSummary.totalPnl) : '—'}
            tone={
              running.length === 0
                ? 'neutral'
                : runningSummary.totalPnl >= 0
                  ? 'profit'
                  : 'loss'
            }
          />
        </div>
      </div>

      {error && (
        <p className="border-b border-rose-200/60 bg-rose-50 px-4 py-2 text-xs font-medium text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300">
          {error}
        </p>
      )}

      <div className="grid min-h-0 flex-1 gap-4 overflow-hidden p-3 lg:grid-cols-[1.05fr_1.95fr] lg:p-4">
        <section className="desk-scroll min-h-0 overflow-auto pr-0.5">
          <div className="mb-2 flex items-center justify-between">
            <h3 className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
              Saved Strategies ({saved.length})
            </h3>
          </div>
          <div className="space-y-2.5">
            {saved.length === 0 && (
              <EmptyState
                title="No saved strategies yet"
                description="Build an intraday or positional multi-leg rule set to run in Paper or Live mode."
                action={
                  <button
                    type="button"
                    onClick={() => {
                      setEditing(null)
                      setEditorOpen(true)
                    }}
                    className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-500"
                  >
                    + Create Strategy
                  </button>
                }
              />
            )}
            {saved.map((s) => {
              const isRunning = s.status === 'RUNNING'
              return (
                <StrategyCard
                  key={s.id}
                  strategy={s}
                  collapsible
                  prices={isRunning ? prices : undefined}
                  liveUnrealised={
                    isRunning ? openTradesUnrealised(s.activeTrades, prices) : undefined
                  }
                  onEdit={
                    isRunning
                      ? undefined
                      : () => {
                          setEditing(s)
                          setEditorOpen(true)
                        }
                  }
                  onInspectLifecycle={() => setLifecycleTarget(s)}
                  onDelete={isRunning ? undefined : () => void deleteStrategy(s)}
                  onRun={isRunning ? undefined : () => setRunChooserId(s.id)}
                  onStop={isRunning ? () => setStopConfirm(s) : undefined}
                />
              )
            })}
          </div>
        </section>

        <section className="flex min-h-0 flex-col overflow-hidden">
          <h3 className="mb-2 shrink-0 text-[11px] font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
            Running ({running.length})
          </h3>
          <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-hidden">
            <div className="desk-scroll max-h-[40%] shrink-0 space-y-2.5 overflow-auto pr-0.5">
              {running.length === 0 && (
                <EmptyState
                  title="No strategies running"
                  description="Click Run on any saved strategy on the left to deploy it in Paper or Live mode."
                />
              )}
              {running.map((s) => (
                <StrategyCard
                  key={s.id}
                  strategy={s}
                  collapsible={false}
                  prices={prices}
                  selected={s.id === selectedRunningId}
                  onSelect={
                    (s.activeTrades ?? []).some((t) => t.status === 'OPEN')
                      ? () => setSelectedRunningId(s.id)
                      : undefined
                  }
                  onInspectLifecycle={() => setLifecycleTarget(s)}
                  onStop={() => setStopConfirm(s)}
                  liveUnrealised={openTradesUnrealised(s.activeTrades, prices)}
                />
              ))}
            </div>

            <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border border-slate-200/90 bg-white shadow-xs dark:border-slate-800 dark:bg-[#0D1119]">
              {payoffLegs.length > 0 ? (
                <div className="desk-scroll flex min-h-0 flex-1 flex-col overflow-auto p-2 sm:p-3">
                  <PayoffChart
                    legs={payoffLegs}
                    spot={selectedSpot}
                    title={
                      selectedRunning
                        ? `${selectedRunning.name} · Live payoff`
                        : 'Live payoff'
                    }
                    templatesReady={false}
                  />
                </div>
              ) : (
                <div className="flex flex-1 items-center justify-center px-4 py-10 text-center text-xs text-slate-500 dark:text-slate-400">
                  {running.length === 0
                    ? 'Start a strategy to plot its live payoff diagram here.'
                    : 'Waiting for open positions to plot live payoff.'}
                </div>
              )}
            </div>
          </div>
        </section>
      </div>

      {runChooserId && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 p-4 backdrop-blur-sm">
          <div className="w-full max-w-sm rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-800 dark:bg-[#0D1119]">
            <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
              Choose Execution Mode
            </h3>
            <p className="mt-1 text-xs leading-relaxed text-slate-500 dark:text-slate-400">
              Paper simulates fills using live LTP. Live places real MARKET orders through MStock.
            </p>
            <div className="mt-4 grid grid-cols-2 gap-2.5">
              <button
                type="button"
                disabled={busy}
                onClick={() => void runStrategy(runChooserId, 'PAPER')}
                className="rounded-xl border border-slate-200 bg-slate-50/70 px-3 py-2.5 text-xs font-semibold text-slate-800 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-[#131924] dark:text-slate-100 dark:hover:bg-slate-800"
              >
                Paper Mode
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => void runStrategy(runChooserId, 'LIVE')}
                className="rounded-xl bg-blue-600 px-3 py-2.5 text-xs font-semibold text-white transition hover:bg-blue-500 disabled:opacity-60"
              >
                Live Mode
              </button>
            </div>
            <button
              type="button"
              onClick={() => setRunChooserId(null)}
              className="mt-3 w-full rounded-lg py-2 text-xs font-medium text-slate-500 hover:text-slate-800 dark:hover:text-slate-200"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {stopConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 p-4 backdrop-blur-sm">
          <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-800 dark:bg-[#0D1119]">
            <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">
              Stop {stopConfirm.name}?
            </h3>
            <p className="mt-2 text-xs leading-relaxed text-slate-500 dark:text-slate-400">
              {stopConfirm.executionMode === 'LIVE' ||
              stopConfirm.activeRun?.executionMode === 'LIVE'
                ? 'Live stop verifies open positions at the broker before placing exit orders. Positions already flat are reconciled without new orders.'
                : 'Paper stop marks open legs closed at the latest LTP and finalises the run.'}
            </p>
            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setStopConfirm(null)}
                className="rounded-lg border border-slate-200 px-3.5 py-2 text-xs font-medium text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => void confirmStop()}
                className="rounded-lg bg-rose-600 px-3.5 py-2 text-xs font-semibold text-white transition hover:bg-rose-500 disabled:opacity-60"
              >
                Stop strategy
              </button>
            </div>
          </div>
        </div>
      )}

      {lifecycleTarget && (
        <StrategyLifecycleDrawer
          strategyId={lifecycleTarget.id}
          strategyName={lifecycleTarget.name}
          onClose={() => setLifecycleTarget(null)}
        />
      )}
    </div>
  )
}
