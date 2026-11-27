import type { StrategyLeg } from './payoff'

export type PremiumKind = 'credit' | 'debit'

export type CombinedPremium = {
  kind: PremiumKind
  value: number
  credit: number
  debit: number
}

export type SpreadClassification = {
  label: string
  kind: PremiumKind
}

function quantity(leg: StrategyLeg): number {
  return Math.max(0, Math.abs(leg.lots) * Math.max(1, leg.lotSize))
}

/** Per-unit combined premium (credit − debit) / unit — not multiplied by lots or lotSize. */
export function combinedPremium(legs: StrategyLeg[]): CombinedPremium {
  let credit = 0
  let debit = 0
  for (const leg of legs) {
    const px = Math.abs(leg.entryPrice)
    if (leg.side === 'SELL') {
      credit += px
    } else {
      debit += px
    }
  }
  if (credit >= debit) {
    return { kind: 'credit', value: credit - debit, credit, debit }
  }
  return { kind: 'debit', value: debit - credit, credit, debit }
}

export function classifySpread(legs: StrategyLeg[]): SpreadClassification | null {
  const active = legs.filter((l) => quantity(l) > 0)
  if (active.length === 0) {
    return null
  }
  const premium = combinedPremium(active)
  if (active.length === 1) {
    const leg = active[0]!
    const dir = leg.side === 'BUY' ? 'Long' : 'Short'
    if (leg.kind === 'FUT') {
      return { label: `${dir} FUT`, kind: premium.kind }
    }
    return { label: `${dir} ${leg.kind}`, kind: premium.kind }
  }

  const calls = active.filter((l) => l.kind === 'CE')
  const puts = active.filter((l) => l.kind === 'PE')
  const sameExpiry =
    active.every((l) => l.expiry === active[0]!.expiry) || active.every((l) => !l.expiry)

  if (active.length === 2 && sameExpiry && calls.length === 1 && puts.length === 1) {
    const bothBuy = active.every((l) => l.side === 'BUY')
    const bothSell = active.every((l) => l.side === 'SELL')
    const c = calls[0]!
    const p = puts[0]!
    if (c.strike != null && p.strike != null && (bothBuy || bothSell)) {
      const dir = bothSell ? 'Short' : 'Long'
      const diff = c.strike - p.strike
      let label: string
      if (Math.abs(diff) < 1e-6) {
        label = `${dir} Straddle`
      } else if (diff > 0) {
        label = `${dir} Strangle`
      } else {
        label = `Inverted ${dir} Strangle`
      }
      return { label, kind: premium.kind }
    }
  }

  if (active.length === 2 && sameExpiry && calls.length === 2) {
    const sorted = [...calls].sort((a, b) => (a.strike ?? 0) - (b.strike ?? 0))
    const low = sorted[0]!
    const high = sorted[1]!
    if (low.side === 'BUY' && high.side === 'SELL') {
      return { label: 'Bull CALL Spread', kind: premium.kind }
    }
    if (low.side === 'SELL' && high.side === 'BUY') {
      return { label: 'Bear CALL Spread', kind: premium.kind }
    }
  }

  if (active.length === 2 && sameExpiry && puts.length === 2) {
    const sorted = [...puts].sort((a, b) => (a.strike ?? 0) - (b.strike ?? 0))
    const low = sorted[0]!
    const high = sorted[1]!
    if (low.side === 'BUY' && high.side === 'SELL') {
      return { label: 'Bull PUT Spread', kind: premium.kind }
    }
    if (low.side === 'SELL' && high.side === 'BUY') {
      return { label: 'Bear PUT Spread', kind: premium.kind }
    }
  }

  if (active.length >= 2) {
    return { label: 'Multi-leg', kind: premium.kind }
  }
  return null
}

export function groupTitle(underlyingSymbol: string | null | undefined, legs: StrategyLeg[]): string {
  const under = underlyingSymbol?.trim() || 'Group'
  const classified = classifySpread(legs)
  if (!classified) {
    return under
  }
  return `${under} - ${classified.label}`
}
