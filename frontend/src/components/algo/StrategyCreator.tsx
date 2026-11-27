import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import {
  ApiError,
  api,
  type AlgoLegConfig,
  type AlgoStrategyConfig,
  type AlgoStrategySummary,
} from '../../api/client'
import { classifyAlgoStructure } from './algoStructureClassify'
import { StrategyTitle } from './BiasIcon'
import { defaultConfig, defaultLeg } from './strategyDefaults'

type Props = {
  initial?: AlgoStrategySummary | null
  onClose: () => void
  onSaved: (strategy: AlgoStrategySummary) => void
}

const UNDERLYING_FROM = ['Cash', 'Futures']
const STRATEGY_TYPES = ['Time based', 'Premium based', 'Indicator based']
const SQUARE_OFF = ['Partial', 'Complete']
const POSITIONS = ['Buy', 'Sell']
const OPTION_TYPES = ['CE', 'PE']
const EXPIRIES = ['Weekly', 'Monthly', 'Next Weekly']
const STRIKE_LADDER: string[] = [
  ...Array.from({ length: 10 }, (_, i) => `ITM${10 - i}`),
  'ATM',
  ...Array.from({ length: 10 }, (_, i) => `OTM${i + 1}`),
]
const STRIKE_CRITERIA = [...STRIKE_LADDER, 'Closest Premium', 'Absolute Strike']
const UNITS = ['Points', 'Percentage', 'Premium']

function isStrikeLadder(criteria: string | null | undefined): boolean {
  if (!criteria) return true
  const c = criteria.trim().toUpperCase()
  return c === 'ATM' || /^ITM\d+$/.test(c) || /^OTM\d+$/.test(c)
}

function FieldLabel({ children }: { children: ReactNode }) {
  return (
    <span className="mb-1 block text-[11px] font-medium text-stone-500 dark:text-slate-400">
      {children}
    </span>
  )
}

function Select({
  value,
  onChange,
  options,
  disabled,
}: {
  value: string
  onChange: (v: string) => void
  options: string[]
  disabled?: boolean
}) {
  return (
    <select
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
      className="h-9 w-full rounded-lg border border-stone-200 bg-white px-2.5 text-xs font-medium text-stone-900 transition disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-700 dark:bg-[#0B0E14] dark:text-slate-100"
    >
      {options.map((o) => (
        <option key={o} value={o}>
          {o}
        </option>
      ))}
    </select>
  )
}

function TextInput({
  value,
  onChange,
  placeholder,
  type = 'text',
}: {
  value: string
  onChange: (v: string) => void
  placeholder?: string
  type?: string
}) {
  return (
    <input
      type={type}
      value={value}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value)}
      className="h-9 w-full rounded-lg border border-stone-200 bg-white px-2.5 text-xs font-medium text-stone-900 transition dark:border-slate-700 dark:bg-[#0B0E14] dark:text-slate-100"
    />
  )
}

const ENTRY_TIME_MIN = '09:16'
const ENTRY_TIME_MAX = '15:00'
const EXIT_TIME_MIN = '09:45'
const EXIT_TIME_MAX = '15:15'

function clampTime(value: string, min: string, max: string): string {
  if (!value) return min
  if (value < min) return min
  if (value > max) return max
  return value
}

function TimeInput({
  value,
  onChange,
  min,
  max,
}: {
  value: string
  onChange: (v: string) => void
  min: string
  max: string
}) {
  return (
    <input
      type="time"
      value={value}
      min={min}
      max={max}
      step={60}
      onChange={(e) => onChange(clampTime(e.target.value, min, max))}
      className="h-9 w-full rounded-lg border border-stone-200 bg-white px-2.5 font-num text-xs font-medium text-stone-900 transition dark:border-slate-700 dark:bg-[#0B0E14] dark:text-slate-100"
    />
  )
}

function NumberInput({
  value,
  onChange,
  placeholder,
  disabled,
}: {
  value: number | null
  onChange: (v: number | null) => void
  placeholder?: string
  disabled?: boolean
}) {
  return (
    <input
      type="number"
      value={value ?? ''}
      placeholder={placeholder}
      disabled={disabled}
      onChange={(e) => {
        const raw = e.target.value
        onChange(raw === '' ? null : Number(raw))
      }}
      className="h-9 w-full rounded-lg border border-stone-200 bg-white px-2.5 font-num text-xs font-medium text-stone-900 transition disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-700 dark:bg-[#0B0E14] dark:text-slate-100"
    />
  )
}

function Switch({
  checked,
  onChange,
  label,
}: {
  checked: boolean
  onChange: (v: boolean) => void
  label: string
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className="inline-flex items-center gap-2 text-xs font-semibold text-stone-700 dark:text-slate-200"
    >
      <span
        className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors duration-200 ${
          checked ? 'bg-teal-700 dark:bg-teal-500' : 'bg-stone-300 dark:bg-slate-700'
        }`}
      >
        <span
          className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform duration-200 ${
            checked ? 'translate-x-4' : 'translate-x-0.5'
          }`}
        />
      </span>
      {label}
    </button>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="animate-desk-in space-y-3 rounded-xl border border-stone-200/90 bg-white p-4 shadow-xs dark:border-slate-800 dark:bg-[#121721]">
      <h3 className="text-[11px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
        {title}
      </h3>
      {children}
    </section>
  )
}

function LegRow({
  index,
  leg,
  canRemove,
  onChange,
  onRemove,
}: {
  index: number
  leg: AlgoLegConfig
  canRemove: boolean
  onChange: (patch: Partial<AlgoLegConfig>) => void
  onRemove: () => void
}) {
  return (
    <div className="rounded-xl border border-stone-200/90 bg-stone-50/60 p-3.5 transition hover:border-stone-300 dark:border-slate-800 dark:bg-[#171D29]/70 dark:hover:border-slate-700">
      <div className="mb-2.5 flex items-center justify-between">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-stone-500 dark:text-slate-400">
          Leg {index + 1}
        </span>
        {canRemove && (
          <button
            type="button"
            onClick={onRemove}
            className="text-xs font-medium text-rose-600 transition hover:text-rose-500 dark:text-rose-400"
          >
            Remove
          </button>
        )}
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-8">
        <label>
          <FieldLabel>Lots</FieldLabel>
          <NumberInput value={leg.lots} onChange={(v) => onChange({ lots: v ?? 1 })} />
        </label>
        <label>
          <FieldLabel>Position</FieldLabel>
          <Select
            value={leg.position ?? 'Sell'}
            onChange={(v) => onChange({ position: v })}
            options={POSITIONS}
          />
        </label>
        <label>
          <FieldLabel>Option</FieldLabel>
          <Select
            value={leg.optionType ?? 'CE'}
            onChange={(v) => onChange({ optionType: v })}
            options={OPTION_TYPES}
          />
        </label>
        <label>
          <FieldLabel>Expiry</FieldLabel>
          <Select
            value={leg.expiry ?? 'Weekly'}
            onChange={(v) => onChange({ expiry: v })}
            options={EXPIRIES}
          />
        </label>
        <label className={isStrikeLadder(leg.strikeCriteria) ? 'xl:col-span-1' : ''}>
          <FieldLabel>Strike</FieldLabel>
          <Select
            value={leg.strikeCriteria ?? 'ATM'}
            onChange={(v) =>
              onChange({
                strikeCriteria: v,
                ...(isStrikeLadder(v) ? { strikeParam1: null, strikeParam2: null } : {}),
              })
            }
            options={STRIKE_CRITERIA}
          />
        </label>
        {!isStrikeLadder(leg.strikeCriteria) && (
          <label>
            <FieldLabel>
              {leg.strikeCriteria === 'Closest Premium' ? 'Premium' : 'Strike value'}
            </FieldLabel>
            <NumberInput
              value={leg.strikeParam1}
              onChange={(v) => onChange({ strikeParam1: v })}
            />
          </label>
        )}
        <div className="space-y-1.5 sm:col-span-2 lg:col-span-2">
          <Switch
            checked={leg.slEnabled}
            onChange={(v) => onChange({ slEnabled: v })}
            label="Stop loss"
          />
          <div className="grid grid-cols-2 gap-2">
            <Select
              value={leg.slUnit ?? 'Points'}
              onChange={(v) => onChange({ slUnit: v })}
              options={UNITS}
              disabled={!leg.slEnabled}
            />
            <NumberInput
              value={leg.slValue}
              onChange={(v) => onChange({ slValue: v })}
              disabled={!leg.slEnabled}
            />
          </div>
        </div>
        <div className="space-y-1.5 sm:col-span-2 lg:col-span-2">
          <Switch
            checked={leg.targetEnabled}
            onChange={(v) => onChange({ targetEnabled: v })}
            label="Target"
          />
          <div className="grid grid-cols-2 gap-2">
            <Select
              value={leg.targetUnit ?? 'Points'}
              onChange={(v) => onChange({ targetUnit: v })}
              options={UNITS}
              disabled={!leg.targetEnabled}
            />
            <NumberInput
              value={leg.targetValue}
              onChange={(v) => onChange({ targetValue: v })}
              disabled={!leg.targetEnabled}
            />
          </div>
        </div>
      </div>
    </div>
  )
}

export function StrategyCreator({ initial, onClose, onSaved }: Props) {
  const [config, setConfig] = useState<AlgoStrategyConfig>(
    initial?.config ?? defaultConfig(),
  )
  const [name, setName] = useState(initial?.name ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [indexOptions, setIndexOptions] = useState<string[]>(['NIFTY'])

  useEffect(() => {
    let cancelled = false
    void api
      .tradableInstruments()
      .then((res) => {
        if (cancelled) return
        const symbols = (res.instruments ?? [])
          .map((i) => i.symbol)
          .filter(Boolean)
        if (symbols.length === 0) return
        setIndexOptions(symbols)
        setConfig((c) => {
          if (initial) return c
          if (symbols.includes(c.instrument.index)) return c
          return {
            ...c,
            instrument: { ...c.instrument, index: symbols[0]! },
          }
        })
      })
      .catch(() => {
        /* keep NIFTY fallback */
      })
    return () => {
      cancelled = true
    }
  }, [initial])

  const indexSelectOptions = useMemo(() => {
    const current = config.instrument.index
    if (current && !indexOptions.includes(current)) {
      return [current, ...indexOptions]
    }
    return indexOptions
  }, [config.instrument.index, indexOptions])

  const structure = useMemo(
    () => classifyAlgoStructure(config.legs),
    [config.legs],
  )
  const displayName = name.trim() || (initial ? initial.name : 'New strategy')

  function updateLeg(index: number, patch: Partial<AlgoLegConfig>) {
    setConfig((c) => ({
      ...c,
      legs: c.legs.map((leg, i) => (i === index ? { ...leg, ...patch } : leg)),
    }))
  }

  async function persist(e: FormEvent) {
    e.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      setError('Strategy name is required')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const body = {
        name: trimmed,
        executionMode: initial?.executionMode ?? 'PAPER',
        config,
      }
      const saved = initial
        ? await api.algoStrategies.update(initial.id, body)
        : await api.algoStrategies.create(body)
      onSaved(saved)
      onClose()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save strategy')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      onSubmit={(e) => void persist(e)}
      className="animate-desk-in flex min-h-0 flex-1 flex-col overflow-hidden bg-[#F5F5F2] dark:bg-[#0B0E14]"
    >
      <div className="sticky top-0 z-10 flex items-center justify-between gap-3 border-b border-stone-200/80 bg-white/95 px-4 py-3 backdrop-blur dark:border-slate-800/80 dark:bg-[#121721]/95">
        <div className="min-w-0">
          <p className="text-[10px] font-semibold uppercase tracking-wider text-teal-700 dark:text-teal-400">
            {initial ? 'Edit strategy' : 'Create strategy'}
          </p>
          <StrategyTitle
            name={displayName}
            structureLabel={structure.label}
            bias={structure.bias}
            size="md"
          />
        </div>
        <div className="flex shrink-0 gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-stone-200 bg-white px-3.5 py-2 text-xs font-medium text-stone-700 transition hover:bg-stone-50 dark:border-slate-700 dark:bg-[#171D29] dark:text-slate-200 dark:hover:bg-slate-800"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={busy}
            className="rounded-lg bg-teal-700 px-4 py-2 text-xs font-semibold text-white shadow-xs transition hover:bg-teal-600 disabled:opacity-60 dark:bg-teal-600 dark:hover:bg-teal-500"
          >
            {busy ? 'Saving…' : 'Save Strategy'}
          </button>
        </div>
      </div>

      <div className="desk-scroll min-h-0 flex-1 space-y-4 overflow-auto p-4">
        {error && (
          <p className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300">
            {error}
          </p>
        )}

        <div className="grid gap-4 lg:grid-cols-2">
          <Section title="Basics">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="sm:col-span-2">
                <FieldLabel>Strategy Name</FieldLabel>
                <TextInput value={name} onChange={setName} placeholder="My Special Strategy" />
              </label>
              <label>
                <FieldLabel>Index</FieldLabel>
                <Select
                  value={config.instrument.index}
                  onChange={(v) =>
                    setConfig((c) => ({
                      ...c,
                      instrument: { ...c.instrument, index: v },
                    }))
                  }
                  options={indexSelectOptions}
                />
              </label>
              <label>
                <FieldLabel>Underlying</FieldLabel>
                <Select
                  value={config.instrument.underlyingFrom}
                  onChange={(v) =>
                    setConfig((c) => ({
                      ...c,
                      instrument: { ...c.instrument, underlyingFrom: v },
                    }))
                  }
                  options={UNDERLYING_FROM}
                />
              </label>
            </div>
          </Section>

          <Section title="Entry / Exit">
            <div className="grid gap-3 sm:grid-cols-2">
              <label>
                <FieldLabel>Strategy type</FieldLabel>
                <Select
                  value={config.entry.strategyType}
                  onChange={(v) =>
                    setConfig((c) => ({
                      ...c,
                      entry: { ...c.entry, strategyType: v },
                    }))
                  }
                  options={STRATEGY_TYPES}
                />
              </label>
              <label>
                <FieldLabel>Square off</FieldLabel>
                <Select
                  value={config.legwise.squareOff}
                  onChange={(v) =>
                    setConfig((c) => ({
                      ...c,
                      legwise: { ...c.legwise, squareOff: v },
                    }))
                  }
                  options={SQUARE_OFF}
                />
              </label>
              <label>
                <FieldLabel>Entry time</FieldLabel>
                <TimeInput
                  value={config.entry.entryTime}
                  min={ENTRY_TIME_MIN}
                  max={ENTRY_TIME_MAX}
                  onChange={(v) =>
                    setConfig((c) => ({
                      ...c,
                      entry: { ...c.entry, entryTime: v },
                    }))
                  }
                />
              </label>
              <label>
                <FieldLabel>Exit time</FieldLabel>
                <TimeInput
                  value={config.entry.exitTime}
                  min={EXIT_TIME_MIN}
                  max={EXIT_TIME_MAX}
                  onChange={(v) =>
                    setConfig((c) => ({
                      ...c,
                      entry: { ...c.entry, exitTime: v },
                    }))
                  }
                />
              </label>
            </div>
          </Section>
        </div>

        <Section title={`Legs (${config.legs.length})`}>
          <div className="space-y-3">
            {config.legs.map((leg, i) => (
              <LegRow
                key={i}
                index={i}
                leg={leg}
                canRemove={config.legs.length > 1}
                onChange={(patch) => updateLeg(i, patch)}
                onRemove={() =>
                  setConfig((c) => ({
                    ...c,
                    legs: c.legs.filter((_, j) => j !== i),
                  }))
                }
              />
            ))}
            <button
              type="button"
              onClick={() =>
                setConfig((c) => ({ ...c, legs: [...c.legs, defaultLeg()] }))
              }
              className="w-full rounded-xl border border-dashed border-stone-300 px-3 py-2.5 text-xs font-semibold text-stone-600 transition hover:border-teal-600 hover:bg-teal-50/40 hover:text-teal-800 dark:border-slate-700 dark:text-slate-300 dark:hover:border-teal-500/60 dark:hover:bg-teal-950/20 dark:hover:text-teal-300"
            >
              + Add leg
            </button>
          </div>
        </Section>

        <Section title="Overall Risk">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2.5 rounded-xl border border-stone-200/80 bg-stone-50/50 p-3.5 dark:border-slate-800 dark:bg-[#171D29]/60">
              <Switch
                checked={config.overall.overallSlEnabled}
                onChange={(v) =>
                  setConfig((c) => ({
                    ...c,
                    overall: { ...c.overall, overallSlEnabled: v },
                  }))
                }
                label="Overall Stop Loss"
              />
              <div className="grid grid-cols-2 gap-2">
                <Select
                  value={config.overall.overallSlType ?? 'Premium'}
                  onChange={(v) =>
                    setConfig((c) => ({
                      ...c,
                      overall: { ...c.overall, overallSlType: v },
                    }))
                  }
                  options={UNITS}
                  disabled={!config.overall.overallSlEnabled}
                />
                <NumberInput
                  value={config.overall.overallSlValue}
                  onChange={(v) =>
                    setConfig((c) => ({
                      ...c,
                      overall: { ...c.overall, overallSlValue: v },
                    }))
                  }
                  disabled={!config.overall.overallSlEnabled}
                />
              </div>
            </div>
            <div className="space-y-2.5 rounded-xl border border-stone-200/80 bg-stone-50/50 p-3.5 dark:border-slate-800 dark:bg-[#171D29]/60">
              <Switch
                checked={config.overall.overallTargetEnabled}
                onChange={(v) =>
                  setConfig((c) => ({
                    ...c,
                    overall: { ...c.overall, overallTargetEnabled: v },
                  }))
                }
                label="Overall Target"
              />
              <div className="grid grid-cols-2 gap-2">
                <Select
                  value={config.overall.overallTargetType ?? 'Premium'}
                  onChange={(v) =>
                    setConfig((c) => ({
                      ...c,
                      overall: { ...c.overall, overallTargetType: v },
                    }))
                  }
                  options={UNITS}
                  disabled={!config.overall.overallTargetEnabled}
                />
                <NumberInput
                  value={config.overall.overallTargetValue}
                  onChange={(v) =>
                    setConfig((c) => ({
                      ...c,
                      overall: { ...c.overall, overallTargetValue: v },
                    }))
                  }
                  disabled={!config.overall.overallTargetEnabled}
                />
              </div>
            </div>
          </div>
        </Section>
      </div>
    </form>
  )
}

/** @deprecated Use StrategyCreator — kept for any leftover imports */
export const StrategyCreatorModal = StrategyCreator
