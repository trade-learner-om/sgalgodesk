import { useEffect, useState } from 'react'
import {
  ApiError,
  api,
  type AlgoStrategyEvent,
  type AlgoStrategyLifecycle,
  type AlgoStrategyRun,
  type AlgoStrategyTrade,
} from '../../api/client'
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
    <div className="fixed inset-0 z-50 flex justify-end bg-black/40">
      <button type="button" className="flex-1 cursor-default" aria-label="Close" onClick={onClose} />
      <aside className="flex h-full w-full max-w-xl flex-col border-l border-slate-200 bg-white shadow-xl dark:border-slate-800 dark:bg-[#0D1119]">
        <div className="flex items-start justify-between gap-3 border-b border-slate-200 px-4 py-3 dark:border-slate-800">
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
              Lifecycle
            </p>
            <h2 className="text-base font-semibold text-slate-900 dark:text-slate-100">
              {strategyName}
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-2 py-1 text-sm text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            Close
          </button>
        </div>

        <div className="min-h-0 flex-1 space-y-5 overflow-auto px-4 py-4">
          {loading && <p className="text-sm text-slate-500">Loading…</p>}
          {error && <p className="text-sm text-rose-600 dark:text-rose-400">{error}</p>}

          {data && (
            <>
              <section>
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Runs
                </h3>
                <div className="space-y-2">
                  {data.runs.length === 0 && (
                    <p className="text-sm text-slate-500">No runs yet.</p>
                  )}
                  {data.runs.map((run: AlgoStrategyRun) => (
                    <div
                      key={run.id}
                      className="rounded-lg border border-slate-200/90 bg-slate-50/80 p-3 text-xs dark:border-slate-800 dark:bg-[#131924]"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-semibold text-slate-800 dark:text-slate-100">
                          {formatLifecycleState(run.lifecycleState)}
                        </span>
                        <span className="rounded bg-slate-200/80 px-1.5 py-0.5 font-medium text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                          {run.executionMode}
                        </span>
                      </div>
                      <div className="mt-2 grid grid-cols-2 gap-2 text-slate-600 dark:text-slate-300">
                        <div>Started {formatTs(run.startedAt)}</div>
                        <div>Ended {formatTs(run.endedAt)}</div>
                        <div>Realised {formatMoney(run.realisedPnl)}</div>
                        <div>Unrealised {formatMoney(run.unrealisedPnl)}</div>
                        <div>Peak {formatMoney(run.peakPnl)}</div>
                        <div>Trail floor {formatMoney(run.trailFloorPnl)}</div>
                      </div>
                    </div>
                  ))}
                </div>
              </section>

              <section>
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Linked trades
                </h3>
                <div className="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800">
                  <table className="min-w-full text-left text-xs">
                    <thead className="bg-slate-50 text-slate-500 dark:bg-[#131924]">
                      <tr>
                        <th className="px-2 py-2 font-medium">Leg</th>
                        <th className="px-2 py-2 font-medium">Symbol</th>
                        <th className="px-2 py-2 font-medium">Order</th>
                        <th className="px-2 py-2 font-medium">Entry</th>
                        <th className="px-2 py-2 font-medium">Exit</th>
                        <th className="px-2 py-2 font-medium">P&amp;L</th>
                        <th className="px-2 py-2 font-medium">Trigger</th>
                        <th className="px-2 py-2 font-medium">Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.trades.length === 0 && (
                        <tr>
                          <td colSpan={8} className="px-2 py-3 text-slate-500">
                            No linked trades.
                          </td>
                        </tr>
                      )}
                      {data.trades.map((t: AlgoStrategyTrade) => (
                        <tr
                          key={t.id}
                          className="border-t border-slate-100 dark:border-slate-800/80"
                        >
                          <td className="px-2 py-2">{t.legIndex + 1}</td>
                          <td className="px-2 py-2 font-medium text-slate-800 dark:text-slate-100">
                            {t.tradingSymbol ?? t.instrumentKey}
                          </td>
                          <td className="px-2 py-2 font-mono text-[10px]">
                            {t.brokerOrderId ?? '—'}
                          </td>
                          <td className="px-2 py-2 font-num">{formatMoney(t.entryPrice)}</td>
                          <td className="px-2 py-2 font-num">{formatMoney(t.exitPrice)}</td>
                          <td
                            className={`px-2 py-2 font-num ${
                              t.pnl >= 0
                                ? 'text-emerald-600 dark:text-emerald-400'
                                : 'text-rose-600 dark:text-rose-400'
                            }`}
                          >
                            {formatMoney(t.pnl)}
                          </td>
                          <td className="px-2 py-2">{t.triggerReason}</td>
                          <td className="px-2 py-2">{t.status}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>

              <section>
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Event timeline
                </h3>
                <ol className="space-y-2">
                  {data.events.length === 0 && (
                    <li className="text-sm text-slate-500">No events yet.</li>
                  )}
                  {data.events.map((ev: AlgoStrategyEvent) => (
                    <li
                      key={ev.id}
                      className="rounded-lg border border-slate-200/90 px-3 py-2 text-xs dark:border-slate-800"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-semibold text-slate-800 dark:text-slate-100">
                          {formatLifecycleState(ev.eventType)}
                        </span>
                        <span className="text-slate-500">{formatTs(ev.createdAt)}</span>
                      </div>
                      <p className="mt-1 text-slate-600 dark:text-slate-300">{ev.message}</p>
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
