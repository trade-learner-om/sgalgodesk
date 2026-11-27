import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ApiError,
  api,
  type AlgoExecutionMode,
  type DdsSettings,
  type DdsStrategySummary,
  type DdsTrade,
} from '../../api/client'
import { useFeedback } from '../Feedback'
import { EmptyState, StatTile } from '../ui/Primitives'
import { defaultDdsSettings } from './ddsDefaults'
import { DdsHistoryDrawer } from './DdsHistoryDrawer'

function formatPnl(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '—'
  return value.toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function settingsChips(s: DdsSettings): string {
  const loss =
    (s.maxLossType ?? 'POINTS').toUpperCase().startsWith('MONEY')
      ? `₹${s.maxLossValue}×lots`
      : `${s.maxLossValue} pts`
  return `${s.initialDelta}Δ · ${s.lots} lot${s.lots === 1 ? '' : 's'} · max ${loss} · exit ${s.exitTime}`
}

type EditorState = {
  id?: string
  name: string
  settings: DdsSettings
}

function DdsEditor({
  initial,
  onCancel,
  onSaved,
}: {
  initial?: DdsStrategySummary | null
  onCancel: () => void
  onSaved: (s: DdsStrategySummary) => void
}) {
  const { toast } = useFeedback()
  const [name, setName] = useState(initial?.name ?? 'Delta Drift Strangle')
  const [settings, setSettings] = useState<DdsSettings>(
    initial?.settings ?? defaultDdsSettings(),
  )
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const save = async () => {
    setSaving(true)
    setError(null)
    try {
      const body = { name: name.trim() || 'Delta Drift Strangle', settings }
      const saved = initial
        ? await api.ddsStrategies.update(initial.id, body)
        : await api.ddsStrategies.create(body)
      toast(initial ? 'Strategy updated' : 'Strategy created', 'success')
      onSaved(saved)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-4 p-4 sm:p-6">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">
            Delta Drift
          </p>
          <h2 className="text-base font-semibold text-stone-900 dark:text-slate-100">
            {initial ? 'Edit strangle' : 'Create strangle'}
          </h2>
        </div>
        <button
          type="button"
          onClick={onCancel}
          className="rounded-lg px-2.5 py-1.5 text-xs font-semibold text-stone-500 hover:bg-stone-100 dark:hover:bg-slate-800"
        >
          Cancel
        </button>
      </div>

      <p className="text-xs text-stone-500 dark:text-slate-400">
        NIFTY · entry 09:45 IST · SELL CE+PE at initial delta · 2× premium adjust · Tue–Thu
        current expiry, else next.
      </p>

      <label className="block text-[11px] font-medium text-stone-500">
        Name
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="mt-1 h-9 w-full rounded-lg border border-stone-200 bg-white px-2.5 text-xs font-medium text-stone-900 dark:border-slate-700 dark:bg-[#0B0E14] dark:text-slate-100"
        />
      </label>

      <div className="grid grid-cols-2 gap-3">
        <label className="block text-[11px] font-medium text-stone-500">
          Initial delta
          <input
            type="number"
            min={1}
            max={50}
            step={1}
            value={settings.initialDelta}
            onChange={(e) =>
              setSettings((s) => ({ ...s, initialDelta: Number(e.target.value) || 25 }))
            }
            className="mt-1 h-9 w-full rounded-lg border border-stone-200 bg-white px-2.5 font-num text-xs dark:border-slate-700 dark:bg-[#0B0E14] dark:text-slate-100"
          />
        </label>
        <label className="block text-[11px] font-medium text-stone-500">
          Lots
          <input
            type="number"
            min={1}
            step={1}
            value={settings.lots}
            onChange={(e) =>
              setSettings((s) => ({ ...s, lots: Math.max(1, Number(e.target.value) || 1) }))
            }
            className="mt-1 h-9 w-full rounded-lg border border-stone-200 bg-white px-2.5 font-num text-xs dark:border-slate-700 dark:bg-[#0B0E14] dark:text-slate-100"
          />
        </label>
        <label className="block text-[11px] font-medium text-stone-500">
          Max loss type
          <select
            value={(settings.maxLossType ?? 'POINTS').toUpperCase().startsWith('MONEY') ? 'MONEY' : 'POINTS'}
            onChange={(e) =>
              setSettings((s) => ({ ...s, maxLossType: e.target.value as DdsSettings['maxLossType'] }))
            }
            className="mt-1 h-9 w-full rounded-lg border border-stone-200 bg-white px-2.5 text-xs dark:border-slate-700 dark:bg-[#0B0E14] dark:text-slate-100"
          >
            <option value="POINTS">Points</option>
            <option value="MONEY">Money (₹ × lots)</option>
          </select>
        </label>
        <label className="block text-[11px] font-medium text-stone-500">
          Max loss value
          <input
            type="number"
            min={0}
            step={1}
            value={settings.maxLossValue}
            onChange={(e) =>
              setSettings((s) => ({ ...s, maxLossValue: Math.max(0, Number(e.target.value) || 0) }))
            }
            className="mt-1 h-9 w-full rounded-lg border border-stone-200 bg-white px-2.5 font-num text-xs dark:border-slate-700 dark:bg-[#0B0E14] dark:text-slate-100"
          />
        </label>
        <label className="col-span-2 block text-[11px] font-medium text-stone-500">
          Exit time (IST)
          <input
            type="time"
            value={settings.exitTime}
            onChange={(e) => setSettings((s) => ({ ...s, exitTime: e.target.value || '15:00' }))}
            className="mt-1 h-9 w-full rounded-lg border border-stone-200 bg-white px-2.5 font-num text-xs dark:border-slate-700 dark:bg-[#0B0E14] dark:text-slate-100"
          />
        </label>
      </div>

      {error && (
        <p className="rounded-lg bg-rose-500/10 px-3 py-2 text-xs text-rose-700 dark:text-rose-300">
          {error}
        </p>
      )}

      <button
        type="button"
        disabled={saving}
        onClick={() => void save()}
        className="h-10 rounded-xl bg-gradient-to-r from-teal-700 to-slate-800 text-sm font-semibold text-white shadow-md shadow-teal-700/15 disabled:opacity-60"
      >
        {saving ? 'Saving…' : 'Save strategy'}
      </button>
    </div>
  )
}

function OpenLegRow({ trade }: { trade: DdsTrade }) {
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-lg border border-slate-200/80 bg-white px-3 py-2 text-xs dark:border-slate-800 dark:bg-[#070A0F]">
      <span className="rounded bg-rose-500/15 px-1.5 py-0.5 text-[10px] font-bold uppercase text-rose-600 dark:text-rose-400">
        Sell
      </span>
      <span className="font-bold text-slate-900 dark:text-slate-100">
        {trade.legRole || trade.optionType}
      </span>
      <span className="font-num text-slate-600 dark:text-slate-300">{trade.strike ?? '—'}</span>
      <span className="text-slate-500">{trade.tradingSymbol}</span>
      <span className="ml-auto font-num text-slate-700 dark:text-slate-200">
        @ {trade.entryPrice.toFixed(2)}
      </span>
      {trade.deltaAtEntry != null && (
        <span className="rounded bg-blue-500/10 px-1.5 py-0.5 text-[10px] font-semibold text-blue-700 dark:text-blue-300">
          Δ {Math.abs(trade.deltaAtEntry) > 1 ? trade.deltaAtEntry.toFixed(0) : (trade.deltaAtEntry * 100).toFixed(1)}
        </span>
      )}
    </div>
  )
}

function StrategyCard({
  strategy,
  running,
  onEdit,
  onDelete,
  onRun,
  onStop,
  onHistory,
}: {
  strategy: DdsStrategySummary
  running?: boolean
  onEdit: () => void
  onDelete: () => void
  onRun: () => void
  onStop: () => void
  onHistory: () => void
}) {
  const open = useMemo(
    () => (strategy.activeTrades ?? []).filter((t) => (t.status ?? '').toUpperCase() === 'OPEN'),
    [strategy.activeTrades],
  )
  const mode = (strategy.activeRun?.executionMode ?? strategy.executionMode ?? 'PAPER').toUpperCase()

  return (
    <div className="overflow-hidden rounded-xl border border-stone-200/90 bg-white shadow-xs dark:border-slate-800 dark:bg-[#121721]">
      <div className="flex items-start justify-between gap-2 px-3.5 py-3">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-semibold text-stone-900 dark:text-slate-100">
            {strategy.name}
          </h3>
          <p className="mt-0.5 text-[11px] text-stone-500 dark:text-slate-400">
            NIFTY · {settingsChips(strategy.settings)}
          </p>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          <span
            className={`rounded-md px-2 py-0.5 text-[10px] font-bold uppercase ${
              strategy.status === 'RUNNING'
                ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300'
                : 'bg-stone-100 text-stone-600 dark:bg-slate-800 dark:text-slate-300'
            }`}
          >
            {strategy.status}
          </span>
          <span className="text-[10px] font-semibold uppercase text-stone-400">{mode}</span>
        </div>
      </div>

      <div className="grid grid-cols-3 gap-2 border-t border-stone-100 px-3.5 py-2.5 dark:border-slate-800/80">
        <StatTile label="Today" value={formatPnl(strategy.todayPnl)} />
        <StatTile label="Total" value={formatPnl(strategy.totalPnl)} />
        <StatTile
          label="Trades"
          value={`${strategy.totalTrades ?? 0}`}
          subValue={`${strategy.tradesToday ?? 0} today`}
        />
      </div>

      {running && open.length > 0 && (
        <div className="space-y-1.5 border-t border-stone-100 px-3.5 py-2.5 dark:border-slate-800/80">
          <p className="text-[10px] font-semibold uppercase tracking-wide text-stone-400">
            Open legs
          </p>
          {open.map((t) => (
            <OpenLegRow key={t.id} trade={t} />
          ))}
        </div>
      )}

      <div className="flex flex-wrap gap-1.5 border-t border-stone-100 px-3.5 py-2.5 dark:border-slate-800/80">
        {strategy.status === 'RUNNING' ? (
          <button
            type="button"
            onClick={onStop}
            className="rounded-lg bg-rose-600 px-2.5 py-1.5 text-[11px] font-semibold text-white"
          >
            Stop
          </button>
        ) : (
          <>
            <button
              type="button"
              onClick={onRun}
              className="rounded-lg bg-teal-700 px-2.5 py-1.5 text-[11px] font-semibold text-white dark:bg-teal-600"
            >
              Run
            </button>
            <button
              type="button"
              onClick={onEdit}
              className="rounded-lg border border-stone-200 px-2.5 py-1.5 text-[11px] font-semibold text-stone-700 dark:border-slate-700 dark:text-slate-200"
            >
              Edit
            </button>
            <button
              type="button"
              onClick={onDelete}
              className="rounded-lg border border-stone-200 px-2.5 py-1.5 text-[11px] font-semibold text-rose-600 dark:border-slate-700"
            >
              Delete
            </button>
          </>
        )}
        <button
          type="button"
          onClick={onHistory}
          className="ml-auto rounded-lg border border-stone-200 px-2.5 py-1.5 text-[11px] font-semibold text-stone-700 dark:border-slate-700 dark:text-slate-200"
        >
          History
        </button>
      </div>
    </div>
  )
}

export function DeltaDriftStrangleWorkspace() {
  const { toast } = useFeedback()
  const [strategies, setStrategies] = useState<DdsStrategySummary[]>([])
  const [loading, setLoading] = useState(true)
  const [editor, setEditor] = useState<EditorState | 'create' | null>(null)
  const [runTarget, setRunTarget] = useState<DdsStrategySummary | null>(null)
  const [historyTarget, setHistoryTarget] = useState<DdsStrategySummary | null>(null)
  const [stopTarget, setStopTarget] = useState<DdsStrategySummary | null>(null)

  const refresh = useCallback(async () => {
    try {
      const list = await api.ddsStrategies.list()
      setStrategies(list)
    } catch (err) {
      toast(err instanceof ApiError ? err.message : 'Could not load strategies', 'error')
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void refresh()
    const id = window.setInterval(() => void refresh(), 15000)
    return () => window.clearInterval(id)
  }, [refresh])

  const saved = strategies.filter((s) => s.status !== 'RUNNING')
  const running = strategies.filter((s) => s.status === 'RUNNING')
  const editing = editor === 'create' ? null : strategies.find((s) => s.id === editor?.id) ?? null

  if (editor === 'create' || (editor && editing)) {
    return (
      <div className="min-h-0 flex-1 overflow-y-auto">
        <DdsEditor
          initial={editor === 'create' ? null : editing}
          onCancel={() => setEditor(null)}
          onSaved={(s) => {
            setEditor(null)
            setStrategies((prev) => {
              const rest = prev.filter((p) => p.id !== s.id)
              return [s, ...rest]
            })
          }}
        />
      </div>
    )
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
      <div className="flex items-center justify-between gap-3 border-b border-stone-200/80 px-4 py-3 dark:border-slate-800">
        <div>
          <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">
            Automation
          </p>
          <h2 className="text-sm font-semibold text-stone-900 dark:text-slate-100">
            Delta Drift Strangle
          </h2>
        </div>
        <button
          type="button"
          onClick={() => setEditor('create')}
          className="rounded-lg bg-teal-700 px-3 py-1.5 text-xs font-semibold text-white dark:bg-teal-600"
        >
          + Create
        </button>
      </div>

      <div className="grid min-h-0 flex-1 gap-4 overflow-hidden p-4 lg:grid-cols-2">
        <section className="flex min-h-0 flex-col overflow-hidden">
          <h3 className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-stone-400">
            Saved
          </h3>
          <div className="min-h-0 flex-1 space-y-3 overflow-y-auto pr-1">
            {loading && <p className="text-xs text-stone-500">Loading…</p>}
            {!loading && saved.length === 0 && (
              <EmptyState
                title="No Delta Drift strategies"
                description="Create one with initial delta, max loss, exit time, and lots."
              />
            )}
            {saved.map((s) => (
              <StrategyCard
                key={s.id}
                strategy={s}
                onEdit={() => setEditor({ id: s.id, name: s.name, settings: s.settings })}
                onDelete={() => {
                  void (async () => {
                    try {
                      await api.ddsStrategies.delete(s.id)
                      setStrategies((prev) => prev.filter((p) => p.id !== s.id))
                      toast('Deleted', 'success')
                    } catch (err) {
                      toast(err instanceof ApiError ? err.message : 'Delete failed', 'error')
                    }
                  })()
                }}
                onRun={() => setRunTarget(s)}
                onStop={() => setStopTarget(s)}
                onHistory={() => setHistoryTarget(s)}
              />
            ))}
          </div>
        </section>

        <section className="flex min-h-0 flex-col overflow-hidden">
          <h3 className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-stone-400">
            Running
          </h3>
          <div className="min-h-0 flex-1 space-y-3 overflow-y-auto pr-1">
            {running.length === 0 && (
              <EmptyState title="Nothing running" description="Run a saved strategy in Paper or Live." />
            )}
            {running.map((s) => (
              <StrategyCard
                key={s.id}
                strategy={s}
                running
                onEdit={() => undefined}
                onDelete={() => undefined}
                onRun={() => undefined}
                onStop={() => setStopTarget(s)}
                onHistory={() => setHistoryTarget(s)}
              />
            ))}
          </div>
        </section>
      </div>

      {runTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/35 p-4">
          <div className="w-full max-w-sm rounded-2xl border border-stone-200 bg-white p-4 shadow-xl dark:border-slate-800 dark:bg-[#121721]">
            <h3 className="text-sm font-semibold text-stone-900 dark:text-slate-100">
              Run {runTarget.name}
            </h3>
            <p className="mt-1 text-xs text-stone-500">Choose execution mode</p>
            <div className="mt-4 flex gap-2">
              {(['PAPER', 'LIVE'] as AlgoExecutionMode[]).map((mode) => (
                <button
                  key={mode}
                  type="button"
                  onClick={() => {
                    void (async () => {
                      try {
                        const updated = await api.ddsStrategies.run(runTarget.id, mode)
                        setStrategies((prev) =>
                          prev.map((p) => (p.id === updated.id ? updated : p)),
                        )
                        setRunTarget(null)
                        toast(`Started in ${mode}`, 'success')
                      } catch (err) {
                        toast(err instanceof ApiError ? err.message : 'Run failed', 'error')
                      }
                    })()
                  }}
                  className={`flex-1 rounded-xl py-2.5 text-xs font-semibold ${
                    mode === 'LIVE'
                      ? 'bg-rose-600 text-white'
                      : 'bg-teal-700 text-white dark:bg-teal-600'
                  }`}
                >
                  {mode}
                </button>
              ))}
            </div>
            <button
              type="button"
              onClick={() => setRunTarget(null)}
              className="mt-3 w-full rounded-lg py-2 text-xs font-semibold text-stone-500"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {stopTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/35 p-4">
          <div className="w-full max-w-sm rounded-2xl border border-stone-200 bg-white p-4 shadow-xl dark:border-slate-800 dark:bg-[#121721]">
            <h3 className="text-sm font-semibold text-stone-900 dark:text-slate-100">
              Stop {stopTarget.name}?
            </h3>
            <p className="mt-1 text-xs text-stone-500">Open legs will be squared off.</p>
            <div className="mt-4 flex gap-2">
              <button
                type="button"
                onClick={() => setStopTarget(null)}
                className="flex-1 rounded-xl border border-stone-200 py-2.5 text-xs font-semibold dark:border-slate-700"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => {
                  void (async () => {
                    try {
                      const updated = await api.ddsStrategies.stop(stopTarget.id)
                      setStrategies((prev) =>
                        prev.map((p) => (p.id === updated.id ? updated : p)),
                      )
                      setStopTarget(null)
                      toast('Stopped', 'success')
                    } catch (err) {
                      toast(err instanceof ApiError ? err.message : 'Stop failed', 'error')
                    }
                  })()
                }}
                className="flex-1 rounded-xl bg-rose-600 py-2.5 text-xs font-semibold text-white"
              >
                Stop
              </button>
            </div>
          </div>
        </div>
      )}

      {historyTarget && (
        <DdsHistoryDrawer
          strategyId={historyTarget.id}
          strategyName={historyTarget.name}
          onClose={() => setHistoryTarget(null)}
        />
      )}
    </div>
  )
}
