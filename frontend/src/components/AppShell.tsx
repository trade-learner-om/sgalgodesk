import { Suspense, lazy, useEffect, useMemo, useRef, useState } from 'react'
import { api, type MeResponse, type UnderlyingHit } from '../api/client'
import { useMarketFeed } from '../hooks/useMarketFeed'
import { BrokersManageModal } from './BrokersManageModal'
import { ThemeToggle } from './ThemeToggle'
import { WorkspaceLoader } from './ui/Primitives'

const StrategyWorkspace = lazy(() =>
  import('./StrategyWorkspace').then((m) => ({ default: m.StrategyWorkspace })),
)
const AlgoExecutionWorkspace = lazy(() =>
  import('./algo/AlgoExecutionWorkspace').then((m) => ({ default: m.AlgoExecutionWorkspace })),
)
const DeltaDriftStrangleWorkspace = lazy(() =>
  import('./dds/DeltaDriftStrangleWorkspace').then((m) => ({
    default: m.DeltaDriftStrangleWorkspace,
  })),
)
const RunningPositions = lazy(() =>
  import('./RunningPositions').then((m) => ({ default: m.RunningPositions })),
)
const StrategyExecution = lazy(() =>
  import('./StrategyExecution').then((m) => ({ default: m.StrategyExecution })),
)

type TabId = 'builder' | 'positions' | 'execution'
type BuilderSubTab = 'manual' | 'algo' | 'deltaDrift'

type Props = {
  me: MeResponse
  onLogout: () => void
  onTokenExpired: () => void
  onMeUpdated?: (me: MeResponse) => void
  onUnauthenticated: () => void
}

const TABS: { id: TabId; label: string }[] = [
  { id: 'positions', label: 'Positions' },
  { id: 'builder', label: 'Strategy Builder' },
  { id: 'execution', label: 'Signals' },
]

const FALLBACK_BENCHMARKS: UnderlyingHit[] = [
  {
    instrumentKey: 'NSE:NIFTY50',
    tradingSymbol: 'NIFTY',
    name: 'Nifty 50',
    assetClass: 'index',
    lotSize: null,
  },
  {
    instrumentKey: 'NSE:NIFTYBANK',
    tradingSymbol: 'BANKNIFTY',
    name: 'Nifty Bank',
    assetClass: 'index',
    lotSize: null,
  },
  {
    instrumentKey: 'BSE:SENSEX',
    tradingSymbol: 'SENSEX',
    name: 'SENSEX',
    assetClass: 'index',
    lotSize: null,
  },
]

function formatPrice(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  return value.toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function formatPct(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

function changePct(ltp: number | null | undefined, close: number | null | undefined): number | null {
  if (ltp == null || close == null || close === 0) {
    return null
  }
  return ((ltp - close) / close) * 100
}

export function AppShell({ me, onLogout, onTokenExpired, onMeUpdated, onUnauthenticated }: Props) {
  const [tab, setTab] = useState<TabId>('positions')
  const [builderSubTab, setBuilderSubTab] = useState<BuilderSubTab>('manual')
  const [seedUnderlyingKey, setSeedUnderlyingKey] = useState<string | null>(null)
  const [benchmarks, setBenchmarks] = useState<UnderlyingHit[]>(FALLBACK_BENCHMARKS)
  const [prices, setPrices] = useState<Record<string, number>>({})
  const [closes, setCloses] = useState<Record<string, number>>({})
  const [brokersOpen, setBrokersOpen] = useState(false)
  const [positionsLivePnl, setPositionsLivePnl] = useState<number | null>(null)

  const mstock = me.brokers?.find((b) => b.id === 'MSTOCK')
  const mstockLinked = mstock?.tokenValid ?? (me.brokerLinked && me.tokenValid)

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const list = await api.benchmarks()
        if (!cancelled && list.length > 0) {
          setBenchmarks(list)
        }
      } catch {
        // keep FALLBACK_BENCHMARKS
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const benchKeys = useMemo(() => benchmarks.map((b) => b.instrumentKey), [benchmarks])
  const benchKeyFingerprint = benchKeys.slice().sort().join('\0')
  const pricesRef = useRef(prices)
  pricesRef.current = prices

  useEffect(() => {
    if (!mstockLinked || benchKeys.length === 0) {
      return
    }
    let cancelled = false
    const keys = benchKeys

    async function seedLtp() {
      try {
        const res = await api.ltp(keys)
        if (cancelled || !res?.prices) {
          return
        }
        setPrices((prev) => {
          const next = { ...prev }
          for (const [key, value] of Object.entries(res.prices)) {
            if (typeof value === 'number') {
              next[key] = value
            }
          }
          return next
        })
      } catch {
        // WS/hub may still deliver
      }
    }

    void seedLtp()

    const timer = window.setInterval(() => {
      const missing = keys.some((k) => pricesRef.current[k] == null)
      if (missing) {
        void seedLtp()
      }
    }, 5000)

    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [mstockLinked, benchKeyFingerprint, benchKeys])

  useMarketFeed({
    instrumentKeys: benchKeys,
    enabled: mstockLinked,
    batchMs: 80,
    onBatch: (ticks) => {
      setPrices((prev) => {
        let changed = false
        const next = { ...prev }
        for (const t of ticks) {
          if (next[t.instrumentKey] !== t.ltp) {
            next[t.instrumentKey] = t.ltp
            changed = true
          }
        }
        return changed ? next : prev
      })
      setCloses((prev) => {
        let changed = false
        const next = { ...prev }
        for (const t of ticks) {
          if (t.close != null && next[t.instrumentKey] !== t.close) {
            next[t.instrumentKey] = t.close
            changed = true
          }
        }
        return changed ? next : prev
      })
    },
    onAuthError: onTokenExpired,
  })

  return (
    <div className="flex h-screen w-full flex-col overflow-hidden bg-[#F5F5F2] dark:bg-[#0B0E14]">
      <header className="shrink-0 border-b border-stone-200/80 bg-white/90 backdrop-blur-md dark:border-slate-800/80 dark:bg-[#121721]/90">
        <div className="flex w-full flex-wrap items-center justify-between gap-2 px-3 py-2 lg:px-4">
          {/* Left: Brand + Primary Navigation */}
          <div className="flex flex-wrap items-center gap-2.5 sm:gap-4">
            <div className="flex items-center gap-2.5">
              <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-teal-700 to-slate-800 text-xs font-bold tracking-tight text-white shadow-xs dark:from-teal-500 dark:to-teal-700">
                SG
              </div>
              <div className="hidden sm:block">
                <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400 dark:text-slate-500">
                  Algo Desk
                </p>
                <h1 className="text-xs font-semibold leading-none text-stone-900 dark:text-slate-100">
                  F&amp;O Terminal
                </h1>
              </div>
            </div>

            <nav
              aria-label="Primary navigation"
              className="flex items-center gap-1 rounded-xl bg-stone-100/90 p-1 dark:bg-[#0B0E14]"
            >
              {TABS.map((item) => {
                const active = tab === item.id
                const showPnl = item.id === 'positions' && positionsLivePnl != null
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => setTab(item.id)}
                    className={`flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold transition-all duration-200 ${
                      active
                        ? 'bg-white text-stone-900 shadow-xs dark:bg-[#181F2C] dark:text-slate-100'
                        : 'text-stone-500 hover:text-stone-900 dark:text-slate-400 dark:hover:text-slate-200'
                    }`}
                  >
                    <span>{item.label}</span>
                    {showPnl && (
                      <span
                        className={`font-num rounded px-1.5 py-0.5 text-[10px] font-semibold ${
                          positionsLivePnl >= 0
                            ? 'bg-emerald-600/10 text-emerald-700 dark:bg-emerald-400/15 dark:text-emerald-300'
                            : 'bg-rose-600/10 text-rose-700 dark:bg-rose-400/15 dark:text-rose-300'
                        }`}
                      >
                        {positionsLivePnl >= 0 ? '+' : ''}
                        {formatPrice(positionsLivePnl)}
                      </span>
                    )}
                  </button>
                )
              })}
            </nav>

            {tab === 'builder' && (
              <div
                role="tablist"
                aria-label="Strategy Builder mode"
                className="animate-desk-in flex items-center gap-1 rounded-xl border border-stone-200/80 bg-stone-50/90 p-0.5 dark:border-slate-800 dark:bg-[#0B0E14]"
              >
                {(
                  [
                    { id: 'manual' as const, label: 'Manual' },
                    { id: 'algo' as const, label: 'Execution' },
                    { id: 'deltaDrift' as const, label: 'Delta Drift' },
                  ] as const
                ).map((sub) => {
                  const subActive = builderSubTab === sub.id
                  return (
                    <button
                      key={sub.id}
                      type="button"
                      role="tab"
                      aria-selected={subActive}
                      onClick={() => setBuilderSubTab(sub.id)}
                      className={`rounded-lg px-2.5 py-1 text-xs font-semibold transition-all duration-200 ${
                        subActive
                          ? 'bg-teal-700 text-white shadow-xs dark:bg-teal-600'
                          : 'text-stone-600 hover:text-stone-900 dark:text-slate-400 dark:hover:text-slate-100'
                      }`}
                    >
                      {sub.label}
                    </button>
                  )
                })}
              </div>
            )}
          </div>

          {/* Right: Live Benchmarks + Broker + Theme + User */}
          <div className="flex flex-wrap items-center justify-end gap-2">
            <div className="hidden items-center gap-1.5 lg:flex">
              {benchmarks.map((bench) => {
                const ltp = prices[bench.instrumentKey]
                const close = closes[bench.instrumentKey]
                const pct = changePct(ltp, close)
                return (
                  <div
                    key={bench.instrumentKey}
                    className="flex items-center gap-1.5 rounded-lg border border-stone-200/80 bg-stone-50/80 px-2.5 py-1 text-xs transition-colors dark:border-slate-800 dark:bg-[#171D29]"
                  >
                    <span className="font-semibold text-stone-700 dark:text-slate-200">
                      {bench.tradingSymbol}
                    </span>
                    <span className="font-num font-medium text-stone-900 dark:text-slate-100">
                      {formatPrice(ltp)}
                    </span>
                    <span
                      className={`font-num text-[11px] font-medium ${
                        pct == null
                          ? 'text-stone-400 dark:text-slate-500'
                          : pct >= 0
                            ? 'text-emerald-700 dark:text-emerald-400'
                            : 'text-rose-700 dark:text-rose-400'
                      }`}
                    >
                      {formatPct(pct)}
                    </span>
                  </div>
                )
              })}
            </div>

            <button
              type="button"
              onClick={() => setBrokersOpen(true)}
              title="Manage broker connection"
              className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-semibold transition-all duration-200 ${
                mstockLinked
                  ? 'border-teal-200 bg-teal-50/80 text-teal-800 hover:bg-teal-100/70 dark:border-teal-900/60 dark:bg-teal-950/30 dark:text-teal-300 dark:hover:bg-teal-950/50'
                  : 'border-amber-200 bg-amber-50/80 text-amber-800 hover:bg-amber-100/70 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300'
              }`}
            >
              <span className="relative flex h-2 w-2">
                {mstockLinked && (
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-teal-400 opacity-75" />
                )}
                <span
                  className={`relative inline-flex h-2 w-2 rounded-full ${
                    mstockLinked ? 'bg-teal-600 dark:bg-teal-400' : 'bg-amber-500'
                  }`}
                />
              </span>
              <span>MStock</span>
            </button>

            <ThemeToggle />

            {me.userName && (
              <div className="hidden items-center gap-1.5 rounded-lg border border-stone-200 bg-stone-50 px-2.5 py-1.5 text-xs font-medium text-stone-700 sm:inline-flex dark:border-slate-800 dark:bg-[#171D29] dark:text-slate-200">
                <span className="h-1.5 w-1.5 rounded-full bg-teal-600 dark:bg-teal-400" />
                <span className="max-w-[110px] truncate">{me.userName}</span>
              </div>
            )}

            <button
              type="button"
              onClick={onLogout}
              title="Log out"
              className="rounded-lg border border-stone-200 px-2.5 py-1.5 text-xs font-medium text-stone-500 transition hover:bg-stone-100 hover:text-stone-900 dark:border-slate-800 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-100"
            >
              Log out
            </button>
          </div>
        </div>
      </header>

      <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
        <Suspense fallback={<WorkspaceLoader />}>
          {tab === 'builder' && (
            <div key={`builder-${builderSubTab}`} className="animate-desk-in flex min-h-0 flex-1 flex-col overflow-hidden">
              {builderSubTab === 'manual' ? (
                <StrategyWorkspace
                  me={me}
                  onLogout={onLogout}
                  onTokenExpired={onTokenExpired}
                  hideChrome
                  seedUnderlyingKey={seedUnderlyingKey}
                />
              ) : builderSubTab === 'algo' ? (
                <AlgoExecutionWorkspace />
              ) : (
                <DeltaDriftStrangleWorkspace />
              )}
            </div>
          )}
          <div className={tab === 'positions' ? 'animate-desk-in flex min-h-0 flex-1 flex-col overflow-hidden' : 'hidden'}>
            <RunningPositions
              onTokenExpired={onTokenExpired}
              onLivePnlChange={setPositionsLivePnl}
            />
          </div>
          {tab === 'execution' && (
            <div className="animate-desk-in flex min-h-0 flex-1 flex-col overflow-hidden">
              <StrategyExecution
                onTokenExpired={onTokenExpired}
                onCreateOwn={(instrumentKey) => {
                  setSeedUnderlyingKey(instrumentKey)
                  setTab('builder')
                  setBuilderSubTab('manual')
                }}
                onExecuted={() => setTab('positions')}
              />
            </div>
          )}
        </Suspense>
      </div>

      {brokersOpen && (
        <BrokersManageModal
          me={me}
          onClose={() => setBrokersOpen(false)}
          onUpdated={(profile) => onMeUpdated?.(profile)}
          onNoValidBroker={onTokenExpired}
          onUnauthenticated={onUnauthenticated}
        />
      )}
    </div>
  )
}
