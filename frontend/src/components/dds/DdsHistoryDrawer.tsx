import { useCallback, useEffect, useState } from 'react'
import {
  ApiError,
  api,
  type DdsHistoryDayGroup,
  type DdsTrade,
} from '../../api/client'

type Props = {
  strategyId: string
  strategyName: string
  onClose: () => void
}

function todayIst(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' })
}

function daysAgoIst(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' })
}

function formatPnl(value: number): string {
  return value.toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function TradeRow({ trade }: { trade: DdsTrade }) {
  const closed = (trade.status ?? '').toUpperCase().includes('CLOSED')
  return (
    <tr className="border-b border-stone-100 text-xs dark:border-slate-800/80">
      <td className="px-2 py-1.5 font-semibold text-stone-800 dark:text-slate-100">
        {trade.legRole || trade.optionType || '—'}
      </td>
      <td className="px-2 py-1.5 text-stone-600 dark:text-slate-300">
        {trade.tradingSymbol ?? trade.instrumentKey}
      </td>
      <td className="px-2 py-1.5 font-num">{trade.strike ?? '—'}</td>
      <td className="px-2 py-1.5 font-num">{trade.entryPrice.toFixed(2)}</td>
      <td className="px-2 py-1.5 font-num">
        {trade.exitPrice != null ? trade.exitPrice.toFixed(2) : '—'}
      </td>
      <td
        className={`px-2 py-1.5 font-num font-semibold ${
          trade.pnl >= 0
            ? 'text-emerald-700 dark:text-emerald-400'
            : 'text-rose-700 dark:text-rose-400'
        }`}
      >
        {formatPnl(trade.pnl)}
      </td>
      <td className="px-2 py-1.5 text-stone-500">{closed ? trade.triggerReason : trade.status}</td>
    </tr>
  )
}

export function DdsHistoryDrawer({ strategyId, strategyName, onClose }: Props) {
  const [from, setFrom] = useState(() => daysAgoIst(30))
  const [to, setTo] = useState(() => todayIst())
  const [groups, setGroups] = useState<DdsHistoryDayGroup[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(
    async (reset: boolean) => {
      setLoading(true)
      setError(null)
      try {
        const res = await api.ddsStrategies.history(strategyId, {
          from,
          to,
          cursor: reset ? null : cursor,
          limit: 10,
        })
        setGroups((prev) => (reset ? res.groups : [...prev, ...res.groups]))
        setCursor(res.nextCursor)
        setHasMore(res.hasMore)
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Could not load history')
      } finally {
        setLoading(false)
      }
    },
    [strategyId, from, to, cursor],
  )

  useEffect(() => {
    setGroups([])
    setCursor(null)
    setHasMore(false)
    void (async () => {
      setLoading(true)
      setError(null)
      try {
        const res = await api.ddsStrategies.history(strategyId, {
          from,
          to,
          limit: 10,
        })
        setGroups(res.groups)
        setCursor(res.nextCursor)
        setHasMore(res.hasMore)
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Could not load history')
      } finally {
        setLoading(false)
      }
    })()
  }, [strategyId, from, to])

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-black/30 backdrop-blur-[1px]">
      <button type="button" className="flex-1 cursor-default" aria-label="Close" onClick={onClose} />
      <aside className="flex h-full w-full max-w-xl flex-col border-l border-stone-200 bg-white shadow-xl dark:border-slate-800 dark:bg-[#0B0E14]">
        <header className="flex items-start justify-between gap-3 border-b border-stone-200 px-4 py-3 dark:border-slate-800">
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">
              Trade history
            </p>
            <h2 className="text-sm font-semibold text-stone-900 dark:text-slate-100">
              {strategyName}
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-2 py-1 text-xs font-semibold text-stone-500 hover:bg-stone-100 dark:hover:bg-slate-800"
          >
            Close
          </button>
        </header>

        <div className="flex flex-wrap items-end gap-2 border-b border-stone-200 px-4 py-3 dark:border-slate-800">
          <label className="text-[11px] font-medium text-stone-500">
            From
            <input
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              className="mt-1 block h-8 rounded-lg border border-stone-200 bg-white px-2 text-xs dark:border-slate-700 dark:bg-[#121721] dark:text-slate-100"
            />
          </label>
          <label className="text-[11px] font-medium text-stone-500">
            To
            <input
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className="mt-1 block h-8 rounded-lg border border-stone-200 bg-white px-2 text-xs dark:border-slate-700 dark:bg-[#121721] dark:text-slate-100"
            />
          </label>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
          {error && (
            <p className="mb-3 rounded-lg bg-rose-500/10 px-3 py-2 text-xs text-rose-700 dark:text-rose-300">
              {error}
            </p>
          )}
          {!loading && groups.length === 0 && !error && (
            <p className="text-xs text-stone-500">No trades in this range.</p>
          )}
          <div className="space-y-4">
            {groups.map((g) => (
              <section key={g.date}>
                <div className="mb-1.5 flex items-center justify-between">
                  <h3 className="text-xs font-semibold text-stone-800 dark:text-slate-100">
                    {g.date}
                  </h3>
                  <span
                    className={`font-num text-xs font-semibold ${
                      g.dayPnl >= 0
                        ? 'text-emerald-700 dark:text-emerald-400'
                        : 'text-rose-700 dark:text-rose-400'
                    }`}
                  >
                    {formatPnl(g.dayPnl)}
                  </span>
                </div>
                <div className="overflow-x-auto rounded-lg border border-stone-200 dark:border-slate-800">
                  <table className="min-w-full">
                    <thead className="bg-stone-50 text-[10px] uppercase tracking-wide text-stone-400 dark:bg-[#121721]">
                      <tr>
                        <th className="px-2 py-1.5 text-left">Leg</th>
                        <th className="px-2 py-1.5 text-left">Symbol</th>
                        <th className="px-2 py-1.5 text-left">Strike</th>
                        <th className="px-2 py-1.5 text-left">Entry</th>
                        <th className="px-2 py-1.5 text-left">Exit</th>
                        <th className="px-2 py-1.5 text-left">PnL</th>
                        <th className="px-2 py-1.5 text-left">Reason</th>
                      </tr>
                    </thead>
                    <tbody>
                      {g.trades.map((t) => (
                        <TradeRow key={t.id} trade={t} />
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            ))}
          </div>
          {hasMore && (
            <button
              type="button"
              disabled={loading}
              onClick={() => void load(false)}
              className="mt-4 w-full rounded-lg border border-stone-200 py-2 text-xs font-semibold text-stone-700 hover:bg-stone-50 disabled:opacity-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
            >
              {loading ? 'Loading…' : 'Load more'}
            </button>
          )}
          {loading && groups.length === 0 && (
            <p className="text-xs text-stone-500">Loading…</p>
          )}
        </div>
      </aside>
    </div>
  )
}
