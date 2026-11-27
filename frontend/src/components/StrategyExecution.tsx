import { useCallback, useEffect, useState } from 'react'
import {
  ApiError,
  api,
  type ExpiryScenario,
  type PlaceOrderBody,
  type SuggestResponse,
  type UnderlyingHit,
  type WingTrial,
} from '../api/client'
import { useFeedback } from './Feedback'

type Props = {
  onTokenExpired: () => void
  onCreateOwn: (instrumentKey: string) => void
  onExecuted?: () => void
}

const INDEX_SYMBOLS = ['NIFTY', 'BANKNIFTY', 'SENSEX'] as const

function formatMoney(value: number): string {
  return value.toLocaleString('en-IN', {
    maximumFractionDigits: 2,
    signDisplay: 'exceptZero',
  })
}

function ScenarioCard({
  scenario,
  debit,
  executing,
  onExecute,
}: {
  scenario: ExpiryScenario
  debit: boolean
  executing: boolean
  onExecute: (wing: WingTrial) => void
}) {
  const wing = scenario.bestWing
  return (
    <div
      className={`animate-desk-in flex flex-col rounded-xl border p-4 shadow-xs transition-all duration-200 ${
        scenario.preferred
          ? 'border-teal-600/40 bg-teal-50/40 dark:border-teal-500/40 dark:bg-teal-950/20'
          : 'border-stone-200/90 bg-white dark:border-slate-800/90 dark:bg-[#121721]'
      }`}
    >
      <div className="mb-3 flex items-start justify-between gap-2">
        <div>
          <div className="flex items-center gap-2">
            <h3 className="text-sm font-semibold text-stone-900 dark:text-slate-100">
              Expiry {scenario.expiry}
            </h3>
            {scenario.preferred && (
              <span className="rounded-full bg-teal-600/12 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-teal-800 dark:bg-teal-400/15 dark:text-teal-300">
                Preferred
              </span>
            )}
          </div>
          <p className="mt-0.5 text-[11px] text-stone-500 dark:text-slate-400">
            {scenario.daysToExpiry} DTE
          </p>
        </div>
        {wing && (
          <div className="rounded-lg border border-stone-200/80 bg-white px-2.5 py-1 text-right dark:border-slate-800 dark:bg-[#171D29]">
            <div className="text-[9px] font-medium uppercase tracking-wider text-stone-500 dark:text-slate-400">
              R:R
            </div>
            <div className="font-num text-xs font-bold text-stone-900 dark:text-slate-100">
              {wing.riskReward.toFixed(3)}
            </div>
          </div>
        )}
      </div>

      {!wing && <p className="text-xs text-stone-500 dark:text-slate-400">No viable wing on this expiry.</p>}

      {wing && (
        <>
          <dl className="mb-3 grid grid-cols-3 gap-2 rounded-xl border border-stone-200/70 bg-white/80 p-2.5 text-xs dark:border-slate-800 dark:bg-[#171D29]">
            <div>
              <dt className="text-[10px] text-stone-500 dark:text-slate-400">Sell strike</dt>
              <dd className="font-num font-semibold text-stone-900 dark:text-slate-100">
                {wing.sellStrike}
              </dd>
            </div>
            <div>
              <dt className="text-[10px] text-stone-500 dark:text-slate-400">Buy strike</dt>
              <dd className="font-num font-semibold text-stone-900 dark:text-slate-100">
                {wing.buyStrike}
              </dd>
            </div>
            <div>
              <dt className="text-[10px] text-stone-500 dark:text-slate-400">OTM steps</dt>
              <dd className="font-num font-semibold text-stone-900 dark:text-slate-100">
                +{wing.otmSteps}
              </dd>
            </div>
            <div>
              <dt className="text-[10px] text-stone-500 dark:text-slate-400">{debit ? 'Debit / lot' : 'Credit / lot'}</dt>
              <dd className="font-num font-semibold text-stone-900 dark:text-slate-100">
                {formatMoney(wing.creditPerLot)}
              </dd>
            </div>
            <div>
              <dt className="text-[10px] text-stone-500 dark:text-slate-400">Max profit</dt>
              <dd className="font-num font-semibold text-emerald-700 dark:text-emerald-400">
                {formatMoney(wing.maxProfitPerLot)}
              </dd>
            </div>
            <div>
              <dt className="text-[10px] text-stone-500 dark:text-slate-400">Max loss</dt>
              <dd className="font-num font-semibold text-rose-700 dark:text-rose-400">
                {formatMoney(wing.maxLossPerLot)}
              </dd>
            </div>
          </dl>

          {scenario.allWings.length > 1 && (
            <div className="mb-3 rounded-xl border border-stone-200/70 bg-stone-50/70 p-2.5 text-[11px] dark:border-slate-800 dark:bg-[#0B0E14]/60">
              <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
                Wing Trials
              </p>
              <ul className="space-y-1 font-num text-stone-600 dark:text-slate-300">
                {scenario.allWings.map((t) => (
                  <li key={`${t.otmSteps}-${t.sellStrike}-${t.buyStrike}`}>
                    OTM+{t.otmSteps}: {formatMoney(t.creditPerLot)} · R:R {t.riskReward.toFixed(3)} ·
                    max loss {formatMoney(t.maxLossPerLot)}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <ul className="mb-4 space-y-1.5 text-xs">
            {wing.legs.map((leg) => (
              <li
                key={leg.instrumentKey}
                className="flex items-center justify-between rounded-lg border border-stone-100 bg-white px-2.5 py-1.5 dark:border-slate-800/80 dark:bg-[#171D29]"
              >
                <div className="flex items-center gap-2">
                  <span
                    className={`rounded px-1.5 py-0.5 text-[10px] font-bold ${
                      leg.side === 'BUY'
                        ? 'bg-emerald-600/12 text-emerald-700 dark:text-emerald-400'
                        : 'bg-rose-600/12 text-rose-700 dark:text-rose-400'
                    }`}
                  >
                    {leg.side}
                  </span>
                  <span className="font-medium text-stone-800 dark:text-slate-100">
                    {leg.tradingSymbol}
                  </span>
                </div>
                <span className="font-num text-stone-600 dark:text-slate-300">
                  @ {formatMoney(leg.premium)}
                </span>
              </li>
            ))}
          </ul>

          <button
            type="button"
            disabled={executing}
            onClick={() => onExecute(wing)}
            className="mt-auto h-9 rounded-xl bg-teal-700 px-3 text-xs font-semibold text-white shadow-xs transition hover:bg-teal-600 disabled:opacity-50 dark:bg-teal-600 dark:hover:bg-teal-500"
          >
            {executing ? 'Placing orders…' : 'Execute Strategy'}
          </button>
        </>
      )}
    </div>
  )
}

export function StrategyExecution({ onTokenExpired, onCreateOwn, onExecuted }: Props) {
  const { confirm, toast } = useFeedback()
  const [indices, setIndices] = useState<UnderlyingHit[]>([])
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [debit, setDebit] = useState(false)
  const [suggestion, setSuggestion] = useState<SuggestResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [executing, setExecuting] = useState(false)

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const benches = await api.benchmarks()
        const filtered = benches.filter((b) =>
          INDEX_SYMBOLS.includes(b.tradingSymbol.toUpperCase() as (typeof INDEX_SYMBOLS)[number]),
        )
        const bySymbol = new Map(filtered.map((b) => [b.tradingSymbol.toUpperCase(), b]))
        for (const sym of INDEX_SYMBOLS) {
          if (!bySymbol.has(sym)) {
            try {
              const hits = await api.searchUnderlyings(sym, 'index')
              const hit = hits.find((h) => h.tradingSymbol.toUpperCase() === sym) ?? hits[0]
              if (hit) {
                bySymbol.set(sym, hit)
              }
            } catch {
              // ignore
            }
          }
        }
        const list = INDEX_SYMBOLS.map((s) => bySymbol.get(s)).filter(Boolean) as UnderlyingHit[]
        if (!cancelled) {
          setIndices(list)
          if (list[0]) {
            setSelectedKey(list[0].instrumentKey)
          }
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to load indices')
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const refresh = useCallback(async () => {
    if (!selectedKey) {
      return
    }
    setLoading(true)
    setError(null)
    try {
      const res = await api.suggestCreditSpread(selectedKey, debit)
      setSuggestion(res)
    } catch (err) {
      if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
        onTokenExpired()
        return
      }
      setError(err instanceof Error ? err.message : 'Suggestion failed')
      setSuggestion(null)
    } finally {
      setLoading(false)
    }
  }, [selectedKey, debit, onTokenExpired])

  useEffect(() => {
    void refresh()
  }, [refresh])

  async function executeWing(wing: WingTrial) {
    const lines = wing.legs
      .map((l) => `${l.side} ${l.tradingSymbol} × ${l.lots} lot(s) @ ~${formatMoney(l.premium)}`)
      .join('\n')
    const ok = await confirm({
      title: 'Execute Live Strategy',
      message: `Place live MARKET orders (product NRML / D)?\n\n${lines}`,
      confirmLabel: 'Place Orders',
      tone: 'primary',
    })
    if (!ok) {
      return
    }
    setExecuting(true)
    try {
      const orders: PlaceOrderBody[] = wing.legs.map((leg) => {
        const lotSize = leg.lotSize && leg.lotSize > 0 ? leg.lotSize : 1
        return {
          instrumentKey: leg.instrumentKey,
          transactionType: leg.side === 'SELL' ? 'SELL' : 'BUY',
          quantity: Math.abs(leg.lots) * lotSize,
          product: 'D',
          orderType: 'MARKET',
          validity: 'DAY',
          price: 0,
          tag: 'strategy-exec',
          slice: true,
        }
      })
      const result = await api.placeMulti(orders)
      const failed = result.results.filter((r) => r.status === 'error')
      if (failed.length > 0) {
        toast(`Some legs failed: ${failed.map((f) => f.raw ?? f.status).join('; ')}`, 'error')
      } else {
        toast('Orders placed successfully', 'success')
        onExecuted?.()
      }
    } catch (err) {
      if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
        onTokenExpired()
        return
      }
      toast(err instanceof Error ? err.message : 'Order placement failed', 'error')
    } finally {
      setExecuting(false)
    }
  }

  const signal = suggestion?.signal

  return (
    <div className="desk-scroll flex h-full min-h-0 flex-1 flex-col overflow-auto bg-[#F5F5F2] p-3 dark:bg-[#0B0E14] lg:p-4">
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-stone-900 dark:text-slate-100">
            Signal Scanner
          </h2>
          <p className="text-xs text-stone-500 dark:text-slate-400">
            Supertrend(10, 3.5) + EMA(9) on 75m candles · credit spreads by default
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex rounded-xl bg-stone-200/70 p-0.5 dark:bg-[#121721]">
            {indices.map((idx) => (
              <button
                key={idx.instrumentKey}
                type="button"
                onClick={() => setSelectedKey(idx.instrumentKey)}
                className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-all duration-200 ${
                  selectedKey === idx.instrumentKey
                    ? 'bg-white text-stone-900 shadow-xs dark:bg-[#181F2C] dark:text-slate-100'
                    : 'text-stone-500 hover:text-stone-800 dark:text-slate-400'
                }`}
              >
                {idx.tradingSymbol}
              </button>
            ))}
          </div>
          <label className="flex cursor-pointer items-center gap-1.5 rounded-lg border border-stone-200 bg-white px-2.5 py-1.5 text-xs font-medium text-stone-700 dark:border-slate-800 dark:bg-[#121721] dark:text-slate-300">
            <input
              type="checkbox"
              checked={debit}
              onChange={(e) => setDebit(e.target.checked)}
              className="accent-teal-700 dark:accent-teal-400"
            />
            Debit spread
          </label>
          <button
            type="button"
            onClick={() => void refresh()}
            className="rounded-lg border border-stone-200 bg-white px-3 py-1.5 text-xs font-semibold text-stone-700 transition hover:bg-stone-50 dark:border-slate-800 dark:bg-[#121721] dark:text-slate-200 dark:hover:bg-slate-800"
          >
            Refresh
          </button>
        </div>
      </div>

      {loading && (
        <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          {Array.from({ length: 5 }).map((_, i) => (
            <div
              key={i}
              className="h-14 animate-pulse rounded-xl bg-stone-200/70 dark:bg-slate-800/60"
            />
          ))}
        </div>
      )}

      {error && (
        <div className="mb-4 rounded-xl border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-xs font-medium text-rose-700 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300">
          {error}
        </div>
      )}

      {signal && !loading && (
        <div className="animate-desk-in mb-4 grid gap-2.5 rounded-xl border border-stone-200/90 bg-white p-3.5 text-xs shadow-xs sm:grid-cols-2 lg:grid-cols-5 dark:border-slate-800/90 dark:bg-[#121721]">
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
              Bias
            </p>
            <p
              className={`mt-0.5 text-sm font-bold ${
                signal.bias === 'SHORT'
                  ? 'text-rose-700 dark:text-rose-400'
                  : signal.bias === 'LONG'
                    ? 'text-emerald-700 dark:text-emerald-400'
                    : 'text-stone-700 dark:text-slate-200'
              }`}
            >
              {signal.bias}
            </p>
          </div>
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
              Close (75m)
            </p>
            <p className="mt-0.5 font-num font-semibold text-stone-900 dark:text-slate-100">
              {formatMoney(signal.close)}
            </p>
          </div>
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
              EMA 9
            </p>
            <p className="mt-0.5 font-num font-semibold text-stone-900 dark:text-slate-100">
              {formatMoney(signal.ema9)}
            </p>
          </div>
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
              Supertrend
            </p>
            <p
              className={`mt-0.5 font-num font-semibold ${
                signal.supertrendDirection === 'UP'
                  ? 'text-emerald-700 dark:text-emerald-400'
                  : 'text-rose-700 dark:text-rose-400'
              }`}
            >
              {formatMoney(signal.supertrend)} ({signal.supertrendDirection})
            </p>
          </div>
          <div className="sm:col-span-2 lg:col-span-1">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
              Rule
            </p>
            <p className="mt-0.5 font-medium text-stone-700 dark:text-slate-200">{signal.reason}</p>
          </div>
        </div>
      )}

      {suggestion && suggestion.bias === 'NONE' && (
        <div className="animate-desk-in mb-4 rounded-xl border border-dashed border-stone-200 bg-white px-4 py-6 text-center text-xs text-stone-600 dark:border-slate-800 dark:bg-[#121721] dark:text-slate-300">
          No entry signal right now. Switch index or wait for the next 75m candle close.
        </div>
      )}

      {suggestion && suggestion.scenarios.length > 0 && (
        <div className="mb-4 grid gap-3 lg:grid-cols-2">
          {suggestion.scenarios.map((scenario) => (
            <ScenarioCard
              key={scenario.expiry}
              scenario={scenario}
              debit={debit}
              executing={executing}
              onExecute={(wing) => void executeWing(wing)}
            />
          ))}
        </div>
      )}

      <div className="mt-auto pt-2">
        <button
          type="button"
          onClick={() => {
            if (selectedKey) {
              onCreateOwn(selectedKey)
            }
          }}
          className="w-full rounded-xl border border-stone-200 bg-white px-3 py-2.5 text-xs font-semibold text-stone-700 transition hover:bg-stone-50 dark:border-slate-800 dark:bg-[#121721] dark:text-slate-200 dark:hover:bg-slate-800"
        >
          Open in Strategy Builder →
        </button>
      </div>
    </div>
  )
}
