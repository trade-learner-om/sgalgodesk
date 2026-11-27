import { useEffect, useState } from 'react'
import {
  ApiError,
  api,
  type AlgoStrategyEvent,
  type AlgoStrategyLifecycle,
  type AlgoStrategyRun,
  type AlgoStrategyTrade,
} from '../../api/client'
import { Badge } from '../ui/Primitives'
import { formatLifecycleState } from './useIstCountdown'

type Props = {
  strategyId: string
  strategyName: string
  onClose: () => void
}

function formatMoney(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  return value.toLocaleString('en-IN', {
    maximumFractionDigits: 2,
    signDisplay: 'exceptZero',
  })
}

function formatTs(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  try {
    return new Date(value).toLocaleString('en-IN', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
  } catch {
    return value
  }
}

export function StrategyLifecycleDrawer({ strategyId, strategyName, onClose }: Props) {
  const [data, setData] = useState<AlgoStrategyLifecycle | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      setLoading(true)
      setError(null)
      try {
        const lifecycle = await api.algoStrategies.lifecycle(strategyId)
        if (!cancelled) {
          setData(lifecycle)
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof ApiError ? err.message : 'Failed to load lifecycle')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [strategyId])

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-slate-950/60 backdrop-blur-sm">
      <button type="button" className="flex-1 cursor-default" aria-label="Close" onClick={onClose} />
      <aside className="animate-drawer-slide flex h-full w-full max-w-2xl flex-col border-l border-stone-200 bg-white shadow-2xl dark:border-slate-800 dark:bg-[#121721]">
        <div className="flex items-center justify-between gap-3 border-b border-stone-200 px-5 py-3.5 dark:border-slate-800">
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wider text-teal-700 dark:text-teal-400">
              Strategy Lifecycle &amp; Linked Trades
            </p>
            <h2 className="text-base font-semibold text-stone-900 dark:text-slate-100">
              {strategyName}
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close drawer"
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-stone-400 transition hover:bg-stone-100 hover:text-stone-700 dark:hover:bg-slate-800 dark:hover:text-slate-200"
          >
            ✕
          </button>
        </div>

        <div className="desk-scroll min-h-0 flex-1 space-y-6 overflow-auto px-5 py-4">
          {loading && (
            <div className="space-y-3">
              <div className="h-20 animate-pulse rounded-xl bg-stone-100 dark:bg-slate-800/60" />
              <div className="h-32 animate-pulse rounded-xl bg-stone-100 dark:bg-slate-800/60" />
            </div>
          )}
          {error && (
            <p className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300">
              {error}
            </p>
          )}

          {data && (
            <>
              <section>
                <h3 className="mb-2.5 text-[11px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
                  Execution Runs ({data.runs.length})
                </h3>
                <div className="space-y-2">
                  {data.runs.length === 0 && (
                    <p className="text-xs text-stone-500 dark:text-slate-400">No runs recorded yet.</p>
                  )}
                  {data.runs.map((run: AlgoStrategyRun) => (
                    <div
                      key={run.id}
                      className="rounded-xl border border-stone-200/90 bg-stone-50/70 p-3.5 text-xs dark:border-slate-800 dark:bg-[#171D29]"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-semibold text-stone-900 dark:text-slate-100">
                          {formatLifecycleState(run.lifecycleState)}
                        </span>
                        <Badge tone={run.executionMode === 'LIVE' ? 'amber' : 'accent'}>
                          {run.executionMode}
                        </Badge>
                      </div>
                      <div className="mt-2.5 grid grid-cols-2 gap-2 text-[11px] text-stone-600 sm:grid-cols-3 dark:text-slate-300">
                        <div>
                          <span className="text-stone-400 dark:text-slate-500">Started:</span> {formatTs(run.startedAt)}
                        </div>
                        <div>
                          <span className="text-stone-400 dark:text-slate-500">Ended:</span> {formatTs(run.endedAt)}
                        </div>
                        <div>
                          <span className="text-stone-400 dark:text-slate-500">Realised:</span>{' '}
                          <span className="font-num font-semibold">{formatMoney(run.realisedPnl)}</span>
                        </div>
                        <div>
                          <span className="text-stone-400 dark:text-slate-500">Unrealised:</span>{' '}
                          <span className="font-num font-semibold">{formatMoney(run.unrealisedPnl)}</span>
                        </div>
                        <div>
                          <span className="text-stone-400 dark:text-slate-500">Peak:</span>{' '}
                          <span className="font-num font-semibold">{formatMoney(run.peakPnl)}</span>
                        </div>
                        <div>
                          <span className="text-stone-400 dark:text-slate-500">Trail floor:</span>{' '}
                          <span className="font-num font-semibold">{formatMoney(run.trailFloorPnl)}</span>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </section>

              <section>
                <h3 className="mb-2.5 text-[11px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
                  Linked Trades ({data.trades.length})
                </h3>
                <div className="desk-scroll overflow-x-auto rounded-xl border border-stone-200 dark:border-slate-800">
                  <table className="min-w-full text-left text-xs">
                    <thead className="bg-stone-50 text-[10px] font-semibold uppercase tracking-wider text-stone-500 dark:bg-[#171D29] dark:text-slate-400">
                      <tr>
                        <th className="px-3 py-2">Leg</th>
                        <th className="px-3 py-2">Symbol</th>
                        <th className="px-3 py-2">Order ID</th>
                        <th className="px-3 py-2">Entry</th>
                        <th className="px-3 py-2">Exit</th>
                        <th className="px-3 py-2">P&amp;L</th>
                        <th className="px-3 py-2">Trigger</th>
                        <th className="px-3 py-2">Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.trades.length === 0 && (
                        <tr>
                          <td colSpan={8} className="px-3 py-4 text-center text-stone-500 dark:text-slate-400">
                            No linked trades yet.
                          </td>
                        </tr>
                      )}
                      {data.trades.map((t: AlgoStrategyTrade) => (
                        <tr
                          key={t.id}
                          className="border-t border-stone-100 hover:bg-stone-50/70 dark:border-slate-800/80 dark:hover:bg-slate-800/30"
                        >
                          <td className="px-3 py-2 font-num">{t.legIndex + 1}</td>
                          <td className="px-3 py-2 font-medium text-stone-800 dark:text-slate-100">
                            {t.tradingSymbol ?? t.instrumentKey}
                          </td>
                          <td className="px-3 py-2 font-mono text-[10px] text-stone-500 dark:text-slate-400">
                            {t.brokerOrderId ?? '—'}
                          </td>
                          <td className="px-3 py-2 font-num">{formatMoney(t.entryPrice)}</td>
                          <td className="px-3 py-2 font-num">{formatMoney(t.exitPrice)}</td>
                          <td
                            className={`px-3 py-2 font-num font-semibold ${
                              t.pnl >= 0
                                ? 'text-emerald-700 dark:text-emerald-400'
                                : 'text-rose-700 dark:text-rose-400'
                            }`}
                          >
                            {formatMoney(t.pnl)}
                          </td>
                          <td className="px-3 py-2 text-[11px]">{t.triggerReason}</td>
                          <td className="px-3 py-2">
                            <Badge tone={t.status === 'OPEN' ? 'emerald' : 'neutral'}>
                              {t.status}
                            </Badge>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>

              <section>
                <h3 className="mb-2.5 text-[11px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
                  Event Timeline ({data.events.length})
                </h3>
                <ol className="space-y-2">
                  {data.events.length === 0 && (
                    <li className="text-xs text-stone-500 dark:text-slate-400">No events yet.</li>
                  )}
                  {data.events.map((ev: AlgoStrategyEvent) => (
                    <li
                      key={ev.id}
                      className="rounded-xl border border-stone-200/90 bg-stone-50/50 px-3.5 py-2.5 text-xs dark:border-slate-800 dark:bg-[#171D29]/70"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-semibold text-stone-800 dark:text-slate-100">
                          {formatLifecycleState(ev.eventType)}
                        </span>
                        <span className="font-num text-[11px] text-stone-500 dark:text-slate-400">
                          {formatTs(ev.createdAt)}
                        </span>
                      </div>
                      <p className="mt-1 text-stone-600 dark:text-slate-300">{ev.message}</p>
                    </li>
                  ))}
                </ol>
              </section>
            </>
          )}
        </div>
      </aside>
    </div>
  )
}
