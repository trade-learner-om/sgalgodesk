import type { AlgoLegConfig } from '../../api/client'

export type StructureBias = 'bull' | 'bear' | 'neutral'

export type AlgoStructure = {
  label: string
  bias: StructureBias
}

function isCall(optionType: string | null | undefined): boolean {
  const t = (optionType ?? 'CE').trim().toUpperCase()
  return t.startsWith('C')
}

function isSell(position: string | null | undefined): boolean {
  return (position ?? 'Sell').trim().toUpperCase().startsWith('S')
}

/** Relative strike rank: higher = higher underlying strike for CE; for PE ITM is higher strike. */
export function ladderRank(criteria: string | null | undefined, call: boolean): number {
  const c = (criteria ?? 'ATM').trim().toUpperCase()
  if (c === 'ATM') return 0
  const itm = /^ITM(\d+)$/.exec(c)
  if (itm) {
    const n = Number(itm[1])
    return call ? -n : n
  }
  const otm = /^OTM(\d+)$/.exec(c)
  if (otm) {
    const n = Number(otm[1])
    return call ? n : -n
  }
  return 0
}

export function classifyAlgoStructure(legs: AlgoLegConfig[]): AlgoStructure {
  const active = legs.filter(Boolean)
  if (active.length === 0) {
    return { label: 'Custom', bias: 'neutral' }
  }

  if (active.length === 1) {
    const leg = active[0]!
    const sell = isSell(leg.position)
    const call = isCall(leg.optionType)
    const dir = sell ? 'Short' : 'Long'
    const kind = call ? 'CE' : 'PE'
    let bias: StructureBias = 'neutral'
    if (!sell && call) bias = 'bull'
    if (!sell && !call) bias = 'bear'
    if (sell && call) bias = 'bear'
    if (sell && !call) bias = 'bull'
    return { label: `${dir} ${kind}`, bias }
  }

  const calls = active.filter((l) => isCall(l.optionType))
  const puts = active.filter((l) => !isCall(l.optionType))
  const bothSell = active.every((l) => isSell(l.position))
  const bothBuy = active.every((l) => !isSell(l.position))

  if (active.length === 2 && calls.length === 1 && puts.length === 1) {
    const c = calls[0]!
    const p = puts[0]!
    // Same ladderRank ⇒ same strike (straddle). CE above PE ⇒ OTM strangle;
    // CE below PE ⇒ inverted ITM strangle (e.g. both ITM1).
    const rankC = ladderRank(c.strikeCriteria, true)
    const rankP = ladderRank(p.strikeCriteria, false)
    const comboLabel = (dir: 'Short' | 'Long') => {
      if (rankC === rankP) return `${dir} Straddle`
      if (rankC > rankP) return `${dir} Strangle`
      return `Inverted ${dir} Strangle`
    }
    if (bothSell) {
      return { label: comboLabel('Short'), bias: 'neutral' }
    }
    if (bothBuy) {
      return { label: comboLabel('Long'), bias: 'neutral' }
    }
  }

  if (active.length === 2 && calls.length === 2) {
    const sorted = [...calls].sort(
      (a, b) => ladderRank(a.strikeCriteria, true) - ladderRank(b.strikeCriteria, true),
    )
    const low = sorted[0]!
    const high = sorted[1]!
    if (!isSell(low.position) && isSell(high.position)) {
      return { label: 'Bull Call Spread', bias: 'bull' }
    }
    if (isSell(low.position) && !isSell(high.position)) {
      return { label: 'Bear Call Spread', bias: 'bear' }
    }
  }

  if (active.length === 2 && puts.length === 2) {
    const sorted = [...puts].sort(
      (a, b) => ladderRank(a.strikeCriteria, false) - ladderRank(b.strikeCriteria, false),
    )
    const low = sorted[0]!
    const high = sorted[1]!
    if (!isSell(low.position) && isSell(high.position)) {
      return { label: 'Bull Put Spread', bias: 'bull' }
    }
    if (isSell(low.position) && !isSell(high.position)) {
      return { label: 'Bear Put Spread', bias: 'bear' }
    }
  }

  return { label: 'Multi-leg', bias: 'neutral' }
}
