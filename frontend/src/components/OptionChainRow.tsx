import { memo, type Ref } from 'react'
import type { OptionSide, OptionStrikeRow } from '../api/client'

type Props = {
  row: OptionStrikeRow
  isAtm: boolean
  spot: number | null
  callItm: boolean
  putItm: boolean
  callLive: number | undefined
  putLive: number | undefined
  callChange: number | null | undefined
  putChange: number | null | undefined
  maxOi: number
  atmRef?: Ref<HTMLTableRowElement>
  onBuyCall: (side: OptionSide, strike: number) => void
  onSellCall: (side: OptionSide, strike: number) => void
  onBuyPut: (side: OptionSide, strike: number) => void
  onSellPut: (side: OptionSide, strike: number) => void
}

function formatPrice(value: number | null | undefined, digits = 2): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  return value.toLocaleString('en-IN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

function formatOi(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  if (Math.abs(value) >= 100_000) {
    return `${(value / 100_000).toFixed(2)}L`
  }
  if (Math.abs(value) >= 1_000) {
    return `${(value / 1_000).toFixed(1)}K`
  }
  return value.toLocaleString('en-IN', { maximumFractionDigits: 0 })
}

function formatPct(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

function SideLtp({
  side,
  live,
  changePct,
  onBuy,
  onSell,
  align,
}: {
  side: OptionSide | null
  live: number | undefined
  changePct?: number | null
  onBuy: () => void
  onSell: () => void
  align: 'left' | 'right'
}) {
  if (!side) {
    return <span className="text-slate-400">—</span>
  }
  const ltp = live ?? side.ltp
  const buyBtn =
    'rounded border border-emerald-500/30 bg-emerald-500/10 px-1.5 py-0.5 text-[9px] font-bold uppercase text-emerald-600 transition hover:bg-emerald-600 hover:text-white dark:text-emerald-400 sm:opacity-30 sm:group-hover:opacity-100'
  const sellBtn =
    'rounded border border-rose-500/30 bg-rose-500/10 px-1.5 py-0.5 text-[9px] font-bold uppercase text-rose-600 transition hover:bg-rose-600 hover:text-white dark:text-rose-400 sm:opacity-30 sm:group-hover:opacity-100'
  const pctClass =
    changePct == null
      ? 'text-slate-400'
      : changePct >= 0
        ? 'text-emerald-600 dark:text-emerald-400'
        : 'text-rose-600 dark:text-rose-400'

  const priceBlock = (
    <div className={align === 'right' ? 'text-right' : 'text-left'}>
      <div className="font-num text-[11px] font-semibold text-slate-900 dark:text-slate-100">
        {formatPrice(ltp)}
      </div>
      <div className={`font-num text-[9px] leading-none ${pctClass}`}>{formatPct(changePct)}</div>
    </div>
  )

  return (
    <div className={`flex items-center gap-1 ${align === 'right' ? 'justify-end' : 'justify-start'}`}>
      {align === 'right' ? (
        <>
          <button type="button" onClick={onSell} title="Sell PE" className={sellBtn}>
            S
          </button>
          <button type="button" onClick={onBuy} title="Buy PE" className={buyBtn}>
            B
          </button>
          {priceBlock}
        </>
      ) : (
        <>
          {priceBlock}
          <button type="button" onClick={onBuy} title="Buy CE" className={buyBtn}>
            B
          </button>
          <button type="button" onClick={onSell} title="Sell CE" className={sellBtn}>
            S
          </button>
        </>
      )}
    </div>
  )
}

export const OptionChainRow = memo(function OptionChainRow({
  row,
  isAtm,
  spot,
  callItm,
  putItm,
  callLive,
  putLive,
  callChange,
  putChange,
  maxOi,
  atmRef,
  onBuyCall,
  onSellCall,
  onBuyPut,
  onSellPut,
}: Props) {
  const callOiPct = maxOi > 0 && row.call?.oi ? Math.min(100, (row.call.oi / maxOi) * 100) : 0
  const putOiPct = maxOi > 0 && row.put?.oi ? Math.min(100, (row.put.oi / maxOi) * 100) : 0

  const callCell = callItm
    ? 'bg-amber-50/60 dark:bg-amber-950/15'
    : 'bg-white dark:bg-[#0D1119]'
  const putCell = putItm
    ? 'bg-amber-50/60 dark:bg-amber-950/15'
    : 'bg-white dark:bg-[#0D1119]'

  return (
    <tr
      ref={atmRef}
      className={`group border-t border-slate-100 transition-colors hover:bg-slate-50/80 dark:border-slate-800/70 dark:hover:bg-slate-800/30 ${
        isAtm ? 'border-y border-blue-300 bg-blue-50/70 dark:border-blue-800 dark:bg-blue-950/30' : ''
      }`}
    >
      <td className={`relative px-2 py-1.5 font-num text-[10px] text-slate-600 dark:text-slate-300 ${callCell}`}>
        {callOiPct > 0 && (
          <div
            className="pointer-events-none absolute inset-y-0.5 left-0 rounded-r bg-emerald-500/12 dark:bg-emerald-500/15"
            style={{ width: `${callOiPct}%` }}
          />
        )}
        <span className="relative z-10">{formatOi(row.call?.oi)}</span>
      </td>
      <td className={`px-1.5 py-1.5 ${callCell}`}>
        <SideLtp
          side={row.call}
          live={callLive}
          changePct={callChange}
          align="left"
          onBuy={() => row.call && onBuyCall(row.call, row.strike)}
          onSell={() => row.call && onSellCall(row.call, row.strike)}
        />
      </td>
      <td
        className={`px-2 py-1.5 text-center font-num text-[11px] font-bold text-slate-900 dark:text-slate-100 ${
          isAtm
            ? 'bg-blue-100/70 text-blue-900 dark:bg-blue-900/40 dark:text-blue-200'
            : 'bg-slate-50/90 dark:bg-[#131924]'
        }`}
      >
        <div>{formatPrice(row.strike, 0)}</div>
        {isAtm && (
          <div className="text-[8px] font-semibold uppercase tracking-wider text-blue-600 dark:text-blue-400">
            ATM · {formatPrice(spot)}
          </div>
        )}
      </td>
      <td className={`px-1.5 py-1.5 ${putCell}`}>
        <SideLtp
          side={row.put}
          live={putLive}
          changePct={putChange}
          align="right"
          onBuy={() => row.put && onBuyPut(row.put, row.strike)}
          onSell={() => row.put && onSellPut(row.put, row.strike)}
        />
      </td>
      <td className={`relative px-2 py-1.5 text-right font-num text-[10px] text-slate-600 dark:text-slate-300 ${putCell}`}>
        {putOiPct > 0 && (
          <div
            className="pointer-events-none absolute inset-y-0.5 right-0 rounded-l bg-rose-500/12 dark:bg-rose-500/15"
            style={{ width: `${putOiPct}%` }}
          />
        )}
        <span className="relative z-10">{formatOi(row.put?.oi)}</span>
      </td>
    </tr>
  )
})
