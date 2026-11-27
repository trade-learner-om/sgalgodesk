import type { StructureBias } from './algoStructureClassify'

export function BiasIcon({
  bias,
  className = 'h-5 w-5',
}: {
  bias: StructureBias
  className?: string
}) {
  if (bias === 'bull') {
    return (
      <svg
        viewBox="0 0 20 20"
        className={`${className} text-emerald-500`}
        fill="currentColor"
        aria-label="Bullish"
      >
        <path d="M10 3.5 16 11H12.5v5.5h-5V11H4L10 3.5Z" />
      </svg>
    )
  }
  if (bias === 'bear') {
    return (
      <svg
        viewBox="0 0 20 20"
        className={`${className} text-rose-500`}
        fill="currentColor"
        aria-label="Bearish"
      >
        <path d="M10 16.5 4 9h3.5V3.5h5V9H16L10 16.5Z" />
      </svg>
    )
  }
  return (
    <svg
      viewBox="0 0 20 20"
      className={`${className} text-slate-400`}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      aria-label="Neutral"
    >
      <circle cx="10" cy="10" r="6.5" />
      <path d="M6.5 10h7" strokeLinecap="round" />
    </svg>
  )
}

export function StrategyTitle({
  name,
  structureLabel,
  bias,
  size = 'md',
}: {
  name: string
  structureLabel: string
  bias: StructureBias
  size?: 'sm' | 'md'
}) {
  const nameCls =
    size === 'sm'
      ? 'text-sm font-semibold text-slate-900 dark:text-slate-100'
      : 'text-base font-semibold text-slate-900 dark:text-slate-100'
  const structCls =
    size === 'sm'
      ? 'text-sm font-semibold text-blue-600 dark:text-blue-400'
      : 'text-base font-semibold text-blue-600 dark:text-blue-400'
  return (
    <div className="flex min-w-0 items-center gap-2">
      <BiasIcon bias={bias} className={size === 'sm' ? 'h-4 w-4 shrink-0' : 'h-5 w-5 shrink-0'} />
      <h3 className={`min-w-0 truncate ${nameCls}`}>
        {name}
        <span className="mx-1.5 font-normal text-slate-400">–</span>
        <span className={structCls}>{structureLabel}</span>
      </h3>
    </div>
  )
}
