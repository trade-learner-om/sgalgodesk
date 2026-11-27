import type { ReactNode } from 'react'

type BadgeTone = 'neutral' | 'accent' | 'emerald' | 'rose' | 'amber'

const BADGE_TONES: Record<BadgeTone, string> = {
  neutral: 'bg-stone-100 text-stone-700 dark:bg-slate-800/90 dark:text-slate-300',
  accent: 'bg-teal-600/10 text-teal-800 dark:bg-teal-400/15 dark:text-teal-300',
  emerald: 'bg-emerald-600/10 text-emerald-700 dark:bg-emerald-400/15 dark:text-emerald-300',
  rose: 'bg-rose-600/10 text-rose-700 dark:bg-rose-400/15 dark:text-rose-300',
  amber: 'bg-amber-600/12 text-amber-800 dark:bg-amber-400/15 dark:text-amber-300',
}

export function Badge({
  children,
  tone = 'neutral',
  className = '',
}: {
  children: ReactNode
  tone?: BadgeTone
  className?: string
}) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${BADGE_TONES[tone]} ${className}`}
    >
      {children}
    </span>
  )
}

export function StatTile({
  label,
  value,
  subValue,
  tone = 'neutral',
  className = '',
}: {
  label: string
  value: ReactNode
  subValue?: ReactNode
  tone?: 'neutral' | 'profit' | 'loss' | 'accent'
  className?: string
}) {
  const valueTone =
    tone === 'profit'
      ? 'text-emerald-700 dark:text-emerald-400'
      : tone === 'loss'
        ? 'text-rose-700 dark:text-rose-400'
        : tone === 'accent'
          ? 'text-teal-700 dark:text-teal-300'
          : 'text-stone-900 dark:text-slate-100'

  return (
    <div
      className={`animate-desk-in rounded-xl border border-stone-200/80 bg-white px-3.5 py-2.5 shadow-xs transition-all duration-200 hover:border-stone-300 dark:border-slate-800/90 dark:bg-[#121721] dark:hover:border-slate-700 ${className}`}
    >
      <div className="text-[10px] font-semibold uppercase tracking-wider text-stone-500 dark:text-slate-400">
        {label}
      </div>
      <div className={`font-num mt-0.5 text-sm font-bold tabular-nums ${valueTone}`}>{value}</div>
      {subValue && (
        <div className="mt-0.5 text-[11px] text-stone-500 dark:text-slate-400">{subValue}</div>
      )}
    </div>
  )
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="animate-desk-in flex flex-col items-center justify-center rounded-xl border border-dashed border-stone-300/90 bg-white/70 px-6 py-10 text-center dark:border-slate-800 dark:bg-[#121721]/60">
      <p className="text-sm font-semibold text-stone-800 dark:text-slate-200">{title}</p>
      {description && (
        <p className="mt-1 max-w-md text-xs leading-relaxed text-stone-500 dark:text-slate-400">
          {description}
        </p>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

export function WorkspaceLoader({ label = 'Loading workspace…' }: { label?: string }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 bg-[#F5F5F2] p-6 dark:bg-[#0B0E14]">
      <div className="h-6 w-6 animate-spin rounded-full border-2 border-stone-300 border-t-teal-700 dark:border-slate-700 dark:border-t-teal-400" />
      <p className="text-xs font-medium text-stone-500 dark:text-slate-400">{label}</p>
    </div>
  )
}
