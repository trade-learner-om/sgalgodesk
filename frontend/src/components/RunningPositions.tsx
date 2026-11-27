import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ApiError,
  api,
  type PositionGroup,
  type PositionItem,
  type PositionsResponse,
} from '../api/client'
import { useMarketFeed } from '../hooks/useMarketFeed'
import { usePortfolioFeed } from '../hooks/usePortfolioFeed'
import {
  blackScholesDelta,
  blackScholesGamma,
  blackScholesTheta,
  blackScholesVega,
  impliedVolatility,
  type OptionKind,
} from '../payoff/blackScholes'
import {
  normalizeExpiryIso,
  yearsToExpiry,
  type LegKind,
  type LegSide,
  type StrategyLeg,
} from '../payoff/payoff'
import { combinedPremium, groupTitle } from '../payoff/spreadClassify'
import { useFeedback } from './Feedback'
import { PayoffChart } from './PayoffChart'
import { SpreadPnlChart } from './SpreadPnlChart'
import { StatTile } from './ui/Primitives'

type Props = {
  onTokenExpired: () => void
  onLivePnlChange?: (pnl: number | null) => void
}

function formatPrice(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '0.00'
  }
  return value.toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function liveLtp(item: PositionItem, prices: Record<string, number>): number {
  const fromFeed = prices[item.instrumentKey]
  if (typeof fromFeed === 'number' && fromFeed > 0) {
    return fromFeed
  }
  return item.lastPrice > 0 ? item.lastPrice : 0
}

function livePnl(item: PositionItem, prices: Record<string, number>): number {
  const ltp = liveLtp(item, prices)
  if (item.quantity !== 0 && ltp > 0 && item.averagePrice > 0) {
    return item.realised + item.quantity * (ltp - item.averagePrice)
  }
  return item.pnl
}

function isOptionLikeType(t: string | null | undefined, symbol?: string | null): boolean {
  const u = (t ?? '').toUpperCase()
  if (u === 'CE' || u === 'PE' || u === 'FUT') {
    return true
  }
  if (u.includes('OPT') || u.includes('FUT')) {
    return true
  }
  const s = (symbol ?? '').toUpperCase()
  return s.endsWith('CE') || s.endsWith('PE') || s.includes('FUT')
}

function resolveKind(item: PositionItem): LegKind {
  const kindRaw = (item.instrumentType ?? '').toUpperCase()
  if (kindRaw === 'CE' || kindRaw === 'PE' || kindRaw === 'FUT') {
    return kindRaw
  }
  const sym = (item.tradingSymbol ?? '').toUpperCase()
  if (sym.endsWith('CE') || kindRaw.includes('CE')) {
    return 'CE'
  }
  if (sym.endsWith('PE') || kindRaw.includes('PE')) {
    return 'PE'
  }
  if (sym.includes('FUT') || kindRaw.includes('FUT')) {
    return 'FUT'
  }
  return 'CE'
}

function toLeg(
  item: PositionItem,
  spot: number | null,
  prices: Record<string, number>,
): StrategyLeg | null {
  const qty = item.quantity
  if (qty === 0) {
    return null
  }
  const kind = resolveKind(item)
  const lotSize = item.lotSize && item.lotSize > 0 ? item.lotSize : 1
  const lots = Math.max(1, Math.round(Math.abs(qty) / lotSize))
  const side: LegSide = qty > 0 ? 'BUY' : 'SELL'
  const ltp = liveLtp(item, prices)
  const expiry =
    normalizeExpiryIso(item.expiry) ??
    normalizeExpiryIso(item.tradingSymbol) ??
    item.expiry
  let iv: number | null = null
  let delta: number | null = null
  let gamma: number | null = null
  let theta: number | null = null
  let vega: number | null = null
  if (kind !== 'FUT' && spot != null && spot > 0 && item.strike != null && item.strike > 0) {
    const tRaw = yearsToExpiry(expiry)
    const t = Number.isFinite(tRaw) && tRaw > 0 ? tRaw : 7 / 365
    const premium = ltp > 0 ? ltp : item.averagePrice
    iv = impliedVolatility(premium, spot, item.strike, t, kind as OptionKind) ?? 0.15
    delta = blackScholesDelta(spot, item.strike, t, iv, kind as OptionKind)
    gamma = blackScholesGamma(spot, item.strike, t, iv)
    theta = blackScholesTheta(spot, item.strike, t, iv, kind as OptionKind)
    vega = blackScholesVega(spot, item.strike, t, iv)
    if (delta != null && !Number.isFinite(delta)) delta = null
    if (gamma != null && !Number.isFinite(gamma)) gamma = null
    if (theta != null && !Number.isFinite(theta)) theta = null
    if (vega != null && !Number.isFinite(vega)) vega = null
    if (iv != null && !Number.isFinite(iv)) iv = 0.15
  }
  return {
    id: item.instrumentKey,
    instrumentKey: item.instrumentKey,
    tradingSymbol: item.tradingSymbol ?? item.instrumentKey,
    kind,
    side,
    lots,
    lotSize,
    entryPrice: item.averagePrice,
    strike: item.strike,
    expiry,
    underlyingKey: item.underlyingKey,
    underlyingSymbol: item.underlyingSymbol,
    assetClass: 'index',
    iv,
    delta,
    gamma,
    theta,
    vega,
  }
}

function GroupBlock({
  group,
  mode,
  prices,
  onExitSpread,
  onExitLeg,
  busyKey,
  onTokenExpired,
}: {
  group: PositionGroup
  mode: 'open' | 'closed'
  prices: Record<string, number>
  onExitSpread: (keys: string[], label: string) => void
  onExitLeg: (key: string, label: string) => void
  busyKey: string | null
  onTokenExpired: () => void
}) {
  const optionLike = group.positions.some((p) => isOptionLikeType(p.instrumentType, p.tradingSymbol))
  const [chartView, setChartView] = useState<'pnl' | 'payoff'>('pnl')
  const [selected, setSelected] = useState<Record<string, boolean>>(() => {
    const init: Record<string, boolean> = {}
    for (const p of group.positions) {
      if (p.quantity !== 0) {
        init[p.instrumentKey] = true
      }
    }
    return init
  })

  const underlyingPx =
    group.underlyingKey != null ? prices[group.underlyingKey] : undefined
  const spot =
    underlyingPx != null && Number.isFinite(underlyingPx) && underlyingPx > 0
      ? underlyingPx
      : (group.positions.map((p) => liveLtp(p, prices)).find((v) => v > 0) ?? null)

  const legs = useMemo(() => {
    const out: StrategyLeg[] = []
    for (const p of group.positions) {
      if (!selected[p.instrumentKey]) {
        continue
      }
      const leg = toLeg(p, spot, prices)
      if (leg) {
        out.push(leg)
      }
    }
    return out
  }, [group.positions, selected, spot, prices])

  const title = useMemo(
    () =>
      groupTitle(
        group.underlyingSymbol ?? group.underlyingKey,
        legs.length > 0
          ? legs
          : (group.positions.map((p) => toLeg(p, spot, prices)).filter(Boolean) as StrategyLeg[]),
      ),
    [group, legs, spot, prices],
  )
  const premium = useMemo(() => combinedPremium(legs), [legs])

  // Identity-only legs for SpreadPnlChart — must not change when live LTP ticks.
  const spreadLegs = useMemo(() => {
    const out: {
      instrumentKey: string
      side: 'BUY' | 'SELL'
      quantity: number
      lotSize: number
      lots: number
      entryPrice: number
    }[] = []
    for (const p of group.positions) {
      if (!selected[p.instrumentKey] || p.quantity === 0) {
        continue
      }
      const lotSize = p.lotSize && p.lotSize > 0 ? p.lotSize : 1
      const lots = Math.max(1, Math.round(Math.abs(p.quantity) / lotSize))
      out.push({
        instrumentKey: p.instrumentKey,
        side: p.quantity > 0 ? 'BUY' : 'SELL',
        quantity: Math.abs(p.quantity),
        lotSize,
        lots,
        entryPrice: p.averagePrice,
      })
    }
    return out
  }, [group.positions, selected])

  const realisedSum = useMemo(
    () =>
      group.positions
        .filter((p) => selected[p.instrumentKey])
        .reduce((sum, p) => sum + (p.realised ?? 0), 0),
    [group.positions, selected],
  )

  /** Spread established when the last selected leg filled. */
  const spreadEntryAt = useMemo(() => {
    let latest: string | null = null
    let latestMs = Number.NEGATIVE_INFINITY
    for (const p of group.positions) {
      if (!selected[p.instrumentKey] || p.quantity === 0 || !p.entryAt) {
        continue
      }
      const ms = Date.parse(p.entryAt)
      if (!Number.isFinite(ms)) {
        continue
      }
      if (ms > latestMs) {
        latestMs = ms
        latest = p.entryAt
      }
    }
    return latest
  }, [group.positions, selected])

  return (
    <div
      className={`rounded-xl border border-slate-200/90 bg-white p-3.5 shadow-sm dark:border-slate-800/90 dark:bg-[#0D1119] ${
        mode === 'open' && optionLike ? 'flex min-h-0 flex-1 flex-col' : ''
      }`}
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">{title}</h3>
          <p className="text-[11px] text-slate-500 dark:text-slate-400">
            {group.positions.length} instrument{group.positions.length === 1 ? '' : 's'}
            {optionLike && legs.length > 0
              ? ` · ${premium.kind} ${formatPrice(premium.value)}`
              : ''}
          </p>
        </div>
        {mode === 'open' && (
          <button
            type="button"
            disabled={busyKey === group.underlyingKey}
            onClick={() => {
              const keys = group.positions.filter((p) => p.quantity !== 0).map((p) => p.instrumentKey)
              if (keys.length === 0) {
                return
              }
              onExitSpread(keys, group.underlyingSymbol ?? group.underlyingKey)
            }}
            className="rounded-lg bg-rose-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-rose-500 disabled:opacity-50"
          >
            Exit entire spread
          </button>
        )}
      </div>

      <div className="desk-scroll mb-3 overflow-x-auto rounded-lg border border-slate-100 dark:border-slate-800/80">
        <table className="w-full min-w-[640px] text-left text-xs">
          <thead className="bg-slate-50/90 text-[10px] font-semibold uppercase tracking-wider text-slate-500 dark:bg-[#131924] dark:text-slate-400">
            <tr>
              {mode === 'open' && optionLike && <th className="px-2.5 py-2">Incl.</th>}
              <th className="px-2.5 py-2">Symbol</th>
              <th className="px-2.5 py-2">Qty</th>
              <th className="px-2.5 py-2">Avg</th>
              <th className="px-2.5 py-2">LTP</th>
              <th className="px-2.5 py-2">PnL</th>
              {mode === 'open' && <th className="px-2.5 py-2 text-right">Action</th>}
            </tr>
          </thead>
          <tbody>
            {group.positions.map((p) => {
              const ltp = liveLtp(p, prices)
              const pnl = livePnl(p, prices)
              return (
                <tr
                  key={p.instrumentKey}
                  className="border-t border-slate-100 hover:bg-slate-50/60 dark:border-slate-800/70 dark:hover:bg-slate-800/30"
                >
                  {mode === 'open' && optionLike && (
                    <td className="px-2.5 py-2">
                      <input
                        type="checkbox"
                        checked={!!selected[p.instrumentKey]}
                        onChange={(e) =>
                          setSelected((prev) => ({ ...prev, [p.instrumentKey]: e.target.checked }))
                        }
                        className="accent-blue-600"
                      />
                    </td>
                  )}
                  <td className="px-2.5 py-2 font-medium text-slate-800 dark:text-slate-100">
                    {p.tradingSymbol}
                  </td>
                  <td className="px-2.5 py-2 font-num">{p.quantity}</td>
                  <td className="px-2.5 py-2 font-num">{formatPrice(p.averagePrice)}</td>
                  <td className="px-2.5 py-2 font-num">{formatPrice(ltp)}</td>
                  <td
                    className={`px-2.5 py-2 font-num font-semibold ${
                      pnl >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-rose-600 dark:text-rose-400'
                    }`}
                  >
                    {pnl >= 0 ? '+' : ''}
                    {formatPrice(pnl)}
                  </td>
                  {mode === 'open' && (
                    <td className="px-2.5 py-2 text-right">
                      <button
                        type="button"
                        disabled={busyKey === p.instrumentKey || p.quantity === 0}
                        onClick={() => onExitLeg(p.instrumentKey, p.tradingSymbol ?? p.instrumentKey)}
                        className="rounded-md border border-rose-200 px-2.5 py-1 text-[11px] font-semibold text-rose-600 transition hover:bg-rose-50 disabled:opacity-40 dark:border-rose-900/60 dark:text-rose-400 dark:hover:bg-rose-950/30"
                      >
                        Exit
                      </button>
                    </td>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {mode === 'open' && optionLike && legs.length > 0 && (
        <div className="mt-2 flex min-h-0 flex-1 flex-col gap-2">
          <div className="flex shrink-0 items-center gap-1 self-start rounded-lg border border-slate-200 bg-slate-50 p-0.5 dark:border-slate-700 dark:bg-[#131924]">
            <button
              type="button"
              onClick={() => setChartView('pnl')}
              className={`rounded-md px-2.5 py-1 text-[11px] font-semibold transition ${
                chartView === 'pnl'
                  ? 'bg-white text-slate-900 shadow-sm dark:bg-slate-800 dark:text-slate-100'
                  : 'text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200'
              }`}
            >
              PnL
            </button>
            <button
              type="button"
              onClick={() => setChartView('payoff')}
              className={`rounded-md px-2.5 py-1 text-[11px] font-semibold transition ${
                chartView === 'payoff'
                  ? 'bg-white text-slate-900 shadow-sm dark:bg-slate-800 dark:text-slate-100'
                  : 'text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200'
              }`}
            >
              Payoff
            </button>
          </div>
          <div className="flex min-h-[360px] min-w-0 flex-1 flex-col">
            {chartView === 'pnl' ? (
              <SpreadPnlChart
                legs={spreadLegs}
                entryAt={spreadEntryAt}
                realised={realisedSum}
                title={`${title} · Spread price`}
                onAuthError={onTokenExpired}
              />
            ) : (
              <div className="flex min-h-[480px] flex-1 flex-col">
                <PayoffChart
                  legs={legs}
                  spot={spot}
                  title={`${title} · Expiry`}
                  templatesReady={false}
                />
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

export function RunningPositions({ onTokenExpired, onLivePnlChange }: Props) {
  const { confirm, toast } = useFeedback()
  const [data, setData] = useState<PositionsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const [prices, setPrices] = useState<Record<string, number>>({})
  const [openExpanded, setOpenExpanded] = useState(true)
  const [closedExpanded, setClosedExpanded] = useState(false)
  const sectionDefaultsApplied = useRef(false)

  useEffect(() => {
    if (!data || sectionDefaultsApplied.current) {
      return
    }
    sectionDefaultsApplied.current = true
    const hasOpen = data.openGroups.length > 0
    const hasClosed = data.closedGroups.length > 0
    setOpenExpanded(hasOpen)
    setClosedExpanded(!hasOpen && hasClosed)
  }, [data])

  const seedPricesFromPositions = useCallback((res: PositionsResponse) => {
    setPrices((prev) => {
      const next = { ...prev }
      for (const group of [...res.openGroups, ...res.closedGroups]) {
        for (const p of group.positions) {
          if (p.lastPrice > 0 && (next[p.instrumentKey] == null || next[p.instrumentKey]! <= 0)) {
            next[p.instrumentKey] = p.lastPrice
          }
        }
      }
      return next
    })
  }, [])

  const load = useCallback(
    async (signal?: AbortSignal) => {
      setLoading(true)
      setError(null)
      try {
        const res = await api.positions()
        if (signal?.aborted) {
          return
        }
        setData(res)
        seedPricesFromPositions(res)
      } catch (err) {
        if (signal?.aborted) {
          return
        }
        if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
          onTokenExpired()
          return
        }
        setError(err instanceof Error ? err.message : 'Failed to load positions')
      } finally {
        if (!signal?.aborted) {
          setLoading(false)
        }
      }
    },
    [onTokenExpired, seedPricesFromPositions],
  )

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  usePortfolioFeed({
    enabled: true,
    onSnapshot: (snapshot) => {
      setData(snapshot)
      seedPricesFromPositions(snapshot)
      setLoading(false)
      setError(null)
    },
    onAuthError: onTokenExpired,
  })

  const subscribeKeys = useMemo(() => {
    const keys = new Set<string>()
    if (!data) {
      return [] as string[]
    }
    for (const group of [...data.openGroups, ...data.closedGroups]) {
      if (group.underlyingKey) {
        keys.add(group.underlyingKey)
      }
      for (const p of group.positions) {
        keys.add(p.instrumentKey)
      }
    }
    return [...keys]
  }, [data])

  useMarketFeed({
    instrumentKeys: subscribeKeys,
    enabled: subscribeKeys.length > 0,
    batchMs: 80,
    onBatch: (ticks) => {
      setPrices((prev) => {
        let changed = false
        const next = { ...prev }
        for (const tick of ticks) {
          if (typeof tick.ltp === 'number' && tick.ltp > 0 && next[tick.instrumentKey] !== tick.ltp) {
            next[tick.instrumentKey] = tick.ltp
            changed = true
          }
        }
        return changed ? next : prev
      })
    },
    onAuthError: onTokenExpired,
  })

  const openPositions = useMemo(() => {
    if (!data) {
      return [] as PositionItem[]
    }
    return data.openGroups.flatMap((g) => g.positions.filter((p) => p.quantity !== 0))
  }, [data])

  const totalLivePnl = useMemo(() => {
    if (openPositions.length === 0) {
      return null
    }
    return openPositions.reduce((sum, p) => sum + livePnl(p, prices), 0)
  }, [openPositions, prices])

  const portfolioBreakdown = useMemo(() => {
    if (!data) return { realised: 0, unrealised: 0 }
    let realised = 0
    let unrealised = 0
    for (const g of [...data.openGroups, ...data.closedGroups]) {
      for (const p of g.positions) {
        realised += p.realised ?? 0
        if (p.quantity !== 0) {
          const ltp = liveLtp(p, prices)
          if (ltp > 0 && p.averagePrice > 0) {
            unrealised += p.quantity * (ltp - p.averagePrice)
          }
        }
      }
    }
    return {
      realised: Math.round(realised * 100) / 100,
      unrealised: Math.round(unrealised * 100) / 100,
    }
  }, [data, prices])

  useEffect(() => {
    onLivePnlChange?.(totalLivePnl)
  }, [totalLivePnl, onLivePnlChange])

  async function executeExit(keys: string[]) {
    setBusyKey(keys.length === 1 ? keys[0]! : keys.join(','))
    try {
      await api.exitPositions(keys)
      toast('Exit order submitted', 'success')
      await load()
    } catch (err) {
      if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
        onTokenExpired()
        return
      }
      toast(err instanceof Error ? err.message : 'Exit failed', 'error')
    } finally {
      setBusyKey(null)
    }
  }

  async function handleExitSpread(keys: string[], label: string) {
    const ok = await confirm({
      title: `Exit ${label} spread`,
      message: `Place live MARKET exit orders for all ${keys.length} legs in ${label}?`,
      confirmLabel: 'Exit Spread',
      tone: 'danger',
    })
    if (ok) {
      await executeExit(keys)
    }
  }

  async function handleExitLeg(key: string, label: string) {
    const ok = await confirm({
      title: `Exit ${label}`,
      message: `Place a live MARKET order to close ${label}?`,
      confirmLabel: 'Exit Position',
      tone: 'danger',
    })
    if (ok) {
      await executeExit([key])
    }
  }

  return (
    <div className="desk-scroll flex h-full min-h-0 flex-1 flex-col overflow-auto bg-[#F5F5F2] p-3 dark:bg-[#0B0E14] lg:p-4">
      <div className="mb-3 flex shrink-0 flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="text-base font-semibold text-stone-900 dark:text-slate-100">
            Running Positions
          </h2>
          <p className="text-xs text-stone-500 dark:text-slate-400">
            Live portfolio stream seeded from today&apos;s day trades
          </p>
        </div>
        <div className="flex items-center gap-2">
          {loading && (
            <span className="text-[11px] font-medium text-teal-700 dark:text-teal-400">
              Syncing…
            </span>
          )}
          <button
            type="button"
            onClick={() => void load()}
            className="rounded-lg border border-stone-200 bg-white px-3 py-1.5 text-xs font-semibold text-stone-700 transition hover:bg-stone-50 dark:border-slate-800 dark:bg-[#121721] dark:text-slate-200 dark:hover:bg-slate-800"
          >
            Refresh
          </button>
        </div>
      </div>

      {data && (
        <div className="mb-4 grid shrink-0 grid-cols-2 gap-2 sm:grid-cols-4">
          <StatTile
            label="Live MTM"
            value={totalLivePnl != null ? `${totalLivePnl >= 0 ? '+' : ''}${formatPrice(totalLivePnl)}` : '—'}
            tone={totalLivePnl == null ? 'neutral' : totalLivePnl >= 0 ? 'profit' : 'loss'}
          />
          <StatTile
            label="Unrealised"
            value={`${portfolioBreakdown.unrealised >= 0 ? '+' : ''}${formatPrice(portfolioBreakdown.unrealised)}`}
            tone={portfolioBreakdown.unrealised >= 0 ? 'profit' : 'loss'}
          />
          <StatTile
            label="Realised"
            value={`${portfolioBreakdown.realised >= 0 ? '+' : ''}${formatPrice(portfolioBreakdown.realised)}`}
            tone={portfolioBreakdown.realised >= 0 ? 'profit' : 'loss'}
          />
          <StatTile
            label="Open Groups"
            value={`${data.openGroups.length} open · ${data.closedGroups.length} closed`}
            tone="accent"
          />
        </div>
      )}

      {error && !data && (
        <div className="mb-3 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300">
          {error}
        </div>
      )}

      <div className="flex min-h-0 flex-1 flex-col gap-5">
        <section className="flex min-h-0 flex-1 flex-col gap-3">
          <button
            type="button"
            onClick={() => setOpenExpanded((v) => !v)}
            className="flex w-full shrink-0 items-center gap-2 text-left"
          >
            <svg
              viewBox="0 0 24 24"
              className={`h-3.5 w-3.5 text-emerald-600 transition-transform dark:text-emerald-400 ${
                openExpanded ? 'rotate-90' : ''
              }`}
              fill="none"
              stroke="currentColor"
              strokeWidth="2.5"
            >
              <path d="M9 6l6 6-6 6" />
            </svg>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-emerald-700 dark:text-emerald-400">
              Open Positions ({data?.openGroups.length ?? 0})
            </h3>
          </button>
          {openExpanded && (
            <div className="flex min-h-0 flex-1 flex-col gap-3">
              {!data && (
                <div className="h-32 animate-pulse rounded-xl bg-slate-200/70 dark:bg-slate-800/60" />
              )}
              {data && data.openGroups.length === 0 && (
                <p className="rounded-xl border border-dashed border-slate-200 bg-white px-4 py-6 text-center text-xs text-slate-500 dark:border-slate-800 dark:bg-[#0D1119] dark:text-slate-400">
                  {data.brokerRowCount === 0
                    ? 'No open positions yet. Book is seeded from day trades, then updated live from the portfolio stream.'
                    : data.skippedRowCount > 0
                      ? `No open nets (seed trades=${data.brokerRowCount}; skipped ${data.skippedRowCount}).`
                      : `No open nets (seed trades=${data.brokerRowCount}; all flat / closed).`}
                </p>
              )}
              {data?.openGroups.map((g) => (
                <GroupBlock
                  key={`open-${g.underlyingKey}`}
                  group={g}
                  mode="open"
                  prices={prices}
                  onExitSpread={(keys, label) => void handleExitSpread(keys, label)}
                  onExitLeg={(key, label) => void handleExitLeg(key, label)}
                  busyKey={busyKey}
                  onTokenExpired={onTokenExpired}
                />
              ))}
            </div>
          )}
        </section>

        <section className="shrink-0 space-y-3">
          <button
            type="button"
            onClick={() => setClosedExpanded((v) => !v)}
            className="flex w-full items-center gap-2 text-left"
          >
            <svg
              viewBox="0 0 24 24"
              className={`h-3.5 w-3.5 text-slate-500 transition-transform ${
                closedExpanded ? 'rotate-90' : ''
              }`}
              fill="none"
              stroke="currentColor"
              strokeWidth="2.5"
            >
              <path d="M9 6l6 6-6 6" />
            </svg>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Closed Positions ({data?.closedGroups.length ?? 0})
            </h3>
          </button>
          {closedExpanded && (
            <>
              {data && data.closedGroups.length === 0 && (
                <p className="rounded-xl border border-dashed border-slate-200 bg-white px-4 py-5 text-center text-xs text-slate-500 dark:border-slate-800 dark:bg-[#0D1119] dark:text-slate-400">
                  {data.brokerRowCount === 0
                    ? 'No closed positions yet.'
                    : 'No closed (flat) nets in the book.'}
                </p>
              )}
              {data?.closedGroups.map((g) => (
                <GroupBlock
                  key={`closed-${g.underlyingKey}`}
                  group={g}
                  mode="closed"
                  prices={prices}
                  onExitSpread={() => undefined}
                  onExitLeg={() => undefined}
                  busyKey={null}
                  onTokenExpired={onTokenExpired}
                />
              ))}
            </>
          )}
        </section>
      </div>
    </div>
  )
}
