import { useEffect, useMemo, useRef, useState } from 'react'
import { api, type MeResponse, type UnderlyingHit } from '../api/client'
import { useMarketFeed } from '../hooks/useMarketFeed'
import { AlgoExecutionWorkspace } from './algo/AlgoExecutionWorkspace'
import { BrokersManageModal } from './BrokersManageModal'
import { RunningPositions } from './RunningPositions'
import { StrategyExecution } from './StrategyExecution'
import { StrategyWorkspace } from './StrategyWorkspace'
import { ThemeToggle } from './ThemeToggle'

type TabId = 'builder' | 'positions' | 'execution'
type BuilderSubTab = 'manual' | 'algo'

type Props = {
  me: MeResponse
  onLogout: () => void
  onTokenExpired: () => void
  onMeUpdated?: (me: MeResponse) => void
  onUnauthenticated: () => void
}

const TABS: { id: TabId; label: string }[] = [
  { id: 'builder', label: 'Strategy Builder' },
  { id: 'positions', label: 'Positions' },
  { id: 'execution', label: 'Execution' },
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
    <div className="flex h-screen w-full flex-col overflow-hidden bg-[#F6F8FB] dark:bg-[#070A0F]">
      <header className="shrink-0 border-b border-slate-200/80 bg-white/95 backdrop-blur-md dark:border-slate-800/80 dark:bg-[#0D1119]/95">
        <div className="flex w-full flex-wrap items-center justify-between gap-2 px-3 py-2 lg:px-4">
          {/* Left: Brand + Navigation */}
          <div className="flex items-center gap-3 sm:gap-4">
            <div className="flex items-center gap-2.5">
              <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 to-indigo-600 text-xs font-bold tracking-tight text-white shadow-sm shadow-blue-500/20">
                SG
              </div>
              <div className="hidden sm:block">
                <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate-400 dark:text-slate-500">
                  Algo Desk
                </p>
                <h1 className="text-xs font-semibold leading-none text-slate-900 dark:text-slate-100">
                  F&amp;O Terminal
                </h1>
              </div>
            </div>

            <nav className="flex items-center gap-1 rounded-xl bg-slate-100/90 p-1 dark:bg-[#070A0F]">
              {TABS.map((item) => {
                const active = tab === item.id
                const showPnl = item.id === 'positions' && positionsLivePnl != null
                const tabBtnClass = `flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-semibold transition ${
                  active
                    ? 'bg-white text-slate-900 shadow-sm dark:bg-[#131924] dark:text-slate-100'
                    : 'text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200'
                }`

                if (item.id === 'builder') {
                  return (
                    <div key={item.id} className="group relative">
                      <button
                        type="button"
                        onClick={() => setTab('builder')}
                        className={tabBtnClass}
                      >
                        <span>{item.label}</span>
                        <svg
                          viewBox="0 0 12 12"
                          className="h-2.5 w-2.5 opacity-60"
                          fill="currentColor"
                          aria-hidden
                        >
                          <path d="M2.5 4.5 6 8l3.5-3.5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round" />
                        </svg>
                      </button>
                      <div
                        className="invisible absolute left-0 top-full z-30 min-w-[9.5rem] pt-1 opacity-0 transition group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100"
                      >
                        <div className="rounded-xl border border-slate-200/90 bg-white p-1 shadow-lg dark:border-slate-700 dark:bg-[#131924]">
                          {(
                            [
                              { id: 'manual' as const, label: 'Manual' },
                              { id: 'algo' as const, label: 'Execution' },
                            ] as const
                          ).map((sub) => {
                            const subActive =
                              tab === 'builder' && builderSubTab === sub.id
                            return (
                              <button
                                key={sub.id}
                                type="button"
                                onClick={() => {
                                  setTab('builder')
                                  setBuilderSubTab(sub.id)
                                }}
                                className={`flex w-full items-center rounded-lg px-2.5 py-1.5 text-left text-xs font-semibold transition ${
                                  subActive
                                    ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900'
                                    : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'
                                }`}
                              >
                                {sub.label}
                              </button>
                            )
                          })}
                        </div>
                      </div>
                    </div>
                  )
                }

                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => setTab(item.id)}
                    className={tabBtnClass}
                  >
                    <span>{item.label}</span>
                    {showPnl && (
                      <span
                        className={`font-num rounded px-1.5 py-0.5 text-[10px] font-semibold ${
                          positionsLivePnl >= 0
                            ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
                            : 'bg-rose-500/15 text-rose-600 dark:text-rose-400'
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
          </div>

          {/* Center/Right: Live Benchmarks + Broker + User */}
          <div className="flex flex-wrap items-center justify-end gap-2">
            <div className="hidden items-center gap-1.5 xl:flex">
              {benchmarks.map((bench) => {
                const ltp = prices[bench.instrumentKey]
                const close = closes[bench.instrumentKey]
                const pct = changePct(ltp, close)
                return (
                  <div
                    key={bench.instrumentKey}
                    className="flex items-center gap-1.5 rounded-lg border border-slate-200/80 bg-slate-50/80 px-2.5 py-1 text-xs dark:border-slate-800 dark:bg-[#131924]"
                  >
                    <span className="font-semibold text-slate-700 dark:text-slate-200">
                      {bench.tradingSymbol}
                    </span>
                    <span className="font-num font-medium text-slate-900 dark:text-slate-100">
                      {formatPrice(ltp)}
                    </span>
                    <span
                      className={`font-num text-[11px] font-medium ${
                        pct == null
                          ? 'text-slate-400'
                          : pct >= 0
                            ? 'text-emerald-600 dark:text-emerald-400'
                            : 'text-rose-600 dark:text-rose-400'
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
              className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-semibold transition ${
                mstockLinked
                  ? 'border-emerald-200 bg-emerald-50/70 text-emerald-700 hover:bg-emerald-100/70 dark:border-emerald-900/60 dark:bg-emerald-950/30 dark:text-emerald-300 dark:hover:bg-emerald-950/50'
                  : 'border-amber-200 bg-amber-50/70 text-amber-700 hover:bg-amber-100/70 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300'
              }`}
            >
              <span className="relative flex h-2 w-2">
                {mstockLinked && (
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
                )}
                <span
                  className={`relative inline-flex h-2 w-2 rounded-full ${
                    mstockLinked ? 'bg-emerald-500' : 'bg-amber-500'
                  }`}
                />
              </span>
              <span>MStock</span>
            </button>

            <ThemeToggle />

            {me.userName && (
              <div className="hidden items-center gap-1.5 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-xs font-medium text-slate-700 sm:inline-flex dark:border-slate-800 dark:bg-[#131924] dark:text-slate-200">
                <span className="h-1.5 w-1.5 rounded-full bg-blue-500" />
                <span className="max-w-[110px] truncate">{me.userName}</span>
              </div>
            )}

            <button
              type="button"
              onClick={onLogout}
              title="Log out"
              className="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-500 transition hover:bg-slate-100 hover:text-slate-900 dark:border-slate-800 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-100"
            >
              Log out
            </button>
          </div>
        </div>
      </header>

      <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
        {tab === 'builder' && (
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
            {builderSubTab === 'manual' ? (
              <StrategyWorkspace
                me={me}
                onLogout={onLogout}
                onTokenExpired={onTokenExpired}
                hideChrome
                seedUnderlyingKey={seedUnderlyingKey}
              />
            ) : (
              <AlgoExecutionWorkspace />
            )}
          </div>
        )}
        <div className={tab === 'positions' ? 'flex min-h-0 flex-1 flex-col overflow-hidden' : 'hidden'}>
          <RunningPositions
            onTokenExpired={onTokenExpired}
            onLivePnlChange={setPositionsLivePnl}
          />
        </div>
        {tab === 'execution' && (
          <StrategyExecution
            onTokenExpired={onTokenExpired}
            onCreateOwn={(instrumentKey) => {
              setSeedUnderlyingKey(instrumentKey)
              setTab('builder')
              setBuilderSubTab('manual')
            }}
            onExecuted={() => setTab('positions')}
          />
        )}
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
