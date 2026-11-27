import type { AlgoLegConfig, AlgoStrategyTrade } from '../../api/client'
import {
  blackScholesDelta,
  blackScholesGamma,
  blackScholesTheta,
  blackScholesVega,
  impliedVolatility,
  type OptionKind,
} from '../../payoff/blackScholes'
import {
  normalizeExpiryIso,
  yearsToExpiry,
  type LegKind,
  type LegSide,
  type StrategyLeg,
} from '../../payoff/payoff'

export function underlyingKeyForIndex(index: string | null | undefined): string {
  const raw = (index ?? 'NIFTY').trim().toUpperCase()
  if (raw.includes('BANK')) return 'NSE:NIFTYBANK'
  if (raw.includes('SENSEX')) return 'BSE:SENSEX'
  if (raw.includes('FIN')) return 'NSE:FINNIFTY'
  return 'NSE:NIFTY50'
}

function resolveKind(trade: AlgoStrategyTrade): LegKind {
  const t = (trade.optionType ?? '').toUpperCase()
  if (t === 'CE' || t === 'PE' || t === 'FUT') return t
  const sym = (trade.tradingSymbol ?? '').toUpperCase()
  if (sym.endsWith('CE')) return 'CE'
  if (sym.endsWith('PE')) return 'PE'
  if (sym.includes('FUT')) return 'FUT'
  return 'CE'
}

export function tradeLivePnl(
  trade: AlgoStrategyTrade,
  prices: Record<string, number>,
): number {
  const ltp = prices[trade.instrumentKey]
  const mark =
    typeof ltp === 'number' && ltp > 0 ? ltp : trade.entryPrice
  const buy = trade.side.toUpperCase().startsWith('B')
  const diff = buy ? mark - trade.entryPrice : trade.entryPrice - mark
  return Math.round(diff * trade.quantity * 100) / 100
}

export function openTradesUnrealised(
  trades: AlgoStrategyTrade[] | null | undefined,
  prices: Record<string, number>,
): number | null {
  const open = (trades ?? []).filter((t) => t.status === 'OPEN')
  if (open.length === 0) return null
  let sum = 0
  for (const t of open) {
    sum += tradeLivePnl(t, prices)
  }
  return Math.round(sum * 100) / 100
}

/** Mirror AlgoSlMath.computeSlTrigger — BUY: fill − offset; SELL: fill + offset. */
export function computeSlTrigger(
  side: string,
  fill: number,
  unit: string | null | undefined,
  value: number | null | undefined,
): number | null {
  if (value == null || !(fill > 0)) return null
  const u = (unit ?? 'POINTS').trim().toUpperCase()
  const offset = u.startsWith('PERCENT')
    ? (fill * Math.abs(value)) / 100
    : Math.abs(value)
  if (!(offset > 0)) return null
  const sell = side.trim().toUpperCase().startsWith('S')
  const trigger = sell ? fill + offset : fill - offset
  if (!(trigger > 0)) return null
  return Math.round(trigger * 100) / 100
}

export function resolveLegSlTrigger(
  trade: AlgoStrategyTrade,
  legCfg: AlgoLegConfig | null | undefined,
): number | null {
  if (trade.slTriggerPrice != null && trade.slTriggerPrice > 0) {
    return trade.slTriggerPrice
  }
  if (!legCfg?.slEnabled || legCfg.slValue == null) return null
  return computeSlTrigger(trade.side, trade.entryPrice, legCfg.slUnit, legCfg.slValue)
}

/** Premium points remaining until SL: Sell → trigger − ltp; Buy → ltp − trigger. */
export function distanceToSl(
  side: string,
  ltp: number,
  trigger: number | null,
): number | null {
  if (trigger == null || !(trigger > 0) || !(ltp > 0)) return null
  const sell = side.trim().toUpperCase().startsWith('S')
  const d = sell ? trigger - ltp : ltp - trigger
  return Math.round(d * 100) / 100
}

/** Qty-independent premium-point PnL (AlgoOverallMath.premiumPointPnl). */
export function premiumPointPnl(side: string, entry: number, mark: number): number {
  if (!(entry > 0) || !(mark > 0)) return 0
  const buy = side.trim().toUpperCase().startsWith('B')
  const raw = buy ? mark - entry : entry - mark
  return Math.round(raw * 100) / 100
}

export function openPremiumPointPnl(
  trades: AlgoStrategyTrade[] | null | undefined,
  prices: Record<string, number>,
): number {
  const open = (trades ?? []).filter((t) => t.status === 'OPEN')
  let sum = 0
  for (const t of open) {
    const ltp = prices[t.instrumentKey]
    const mark = typeof ltp === 'number' && ltp > 0 ? ltp : t.entryPrice
    sum += premiumPointPnl(t.side, t.entryPrice, mark)
  }
  return Math.round(sum * 100) / 100
}

/** Points cushion until overall SL (engine trips at metric <= -abs(slValue)). */
export function overallSlDistancePoints(
  overallSlEnabled: boolean,
  overallSlType: string | null | undefined,
  overallSlValue: number | null | undefined,
  premiumPointPnlSum: number,
): number | null {
  if (!overallSlEnabled || overallSlValue == null) return null
  const t = (overallSlType ?? 'Premium').trim().toUpperCase()
  if (!(t.startsWith('POINT') || t.startsWith('PREMIUM') || t === '')) return null
  return Math.round((premiumPointPnlSum + Math.abs(overallSlValue)) * 100) / 100
}

export type SlDistanceTone = {
  /** Chip fill + text (Buy/Sell-style pill). */
  className: string
  breatheClass: string
}

/**
 * Urgency from remaining fraction of SL width (distance / |trigger−entry| or overall budget).
 * Safe ≥50% emerald; caution 25–50% violet pulse; warning 10–25% orange pulse;
 * critical <10% rose pulse; breached <=0 rose bold pulse.
 */
export function slDistanceTone(
  distance: number | null,
  width: number | null | undefined,
): SlDistanceTone {
  const chip =
    'rounded-md px-1.5 py-0.5 text-[10px] font-bold tabular-nums font-num'
  const neutral = {
    className: `${chip} bg-slate-500/10 text-slate-500 dark:text-slate-400`,
    breatheClass: '',
  }
  if (distance == null) return neutral
  if (!(width != null && width > 1e-9)) {
    if (distance <= 0) {
      return {
        className: `${chip} bg-rose-500/20 text-rose-700 dark:text-rose-300`,
        breatheClass: 'animate-pulse',
      }
    }
    return {
      className: `${chip} bg-emerald-500/15 text-emerald-700 dark:text-emerald-400`,
      breatheClass: '',
    }
  }
  const fraction = distance / Math.abs(width)
  if (distance <= 0 || fraction <= 0) {
    return {
      className: `${chip} bg-rose-500/20 text-rose-700 dark:text-rose-300`,
      breatheClass: 'animate-pulse',
    }
  }
  if (fraction < 0.1) {
    return {
      className: `${chip} bg-rose-500/15 text-rose-600 dark:text-rose-400`,
      breatheClass: 'animate-pulse',
    }
  }
  if (fraction < 0.25) {
    return {
      className: `${chip} bg-orange-500/15 text-orange-700 dark:text-orange-400`,
      breatheClass: 'animate-pulse',
    }
  }
  if (fraction < 0.5) {
    return {
      className: `${chip} bg-violet-500/15 text-violet-700 dark:text-violet-400`,
      breatheClass: 'animate-pulse',
    }
  }
  return {
    className: `${chip} bg-emerald-500/15 text-emerald-700 dark:text-emerald-400`,
    breatheClass: '',
  }
}

/** Leg SL width = |trigger − entry| for fraction tone. */
export function legSlWidth(
  entry: number,
  trigger: number | null,
): number | null {
  if (trigger == null || !(entry > 0) || !(trigger > 0)) return null
  const w = Math.abs(trigger - entry)
  return w > 1e-9 ? w : null
}

export function algoTradeToLeg(
  trade: AlgoStrategyTrade,
  spot: number | null,
  prices: Record<string, number>,
  underlyingKey: string,
  underlyingSymbol: string,
): StrategyLeg | null {
  if (trade.status !== 'OPEN' || trade.quantity === 0) return null
  const kind = resolveKind(trade)
  const lotSize = trade.lotSize && trade.lotSize > 0 ? trade.lotSize : 1
  const lots = Math.max(1, Math.round(Math.abs(trade.quantity) / lotSize))
  const side: LegSide = trade.side.toUpperCase().startsWith('B') ? 'BUY' : 'SELL'
  const ltp = prices[trade.instrumentKey]
  const premium =
    typeof ltp === 'number' && ltp > 0 ? ltp : trade.entryPrice

  let iv: number | null = null
  let delta: number | null = null
  let gamma: number | null = null
  let theta: number | null = null
  let vega: number | null = null
  const expiry =
    normalizeExpiryIso(trade.expiry) ??
    normalizeExpiryIso(trade.tradingSymbol) ??
    trade.expiry

  if (
    kind !== 'FUT' &&
    spot != null &&
    spot > 0 &&
    trade.strike != null &&
    trade.strike > 0
  ) {
    const tRaw = yearsToExpiry(expiry)
    const t = Number.isFinite(tRaw) && tRaw > 0 ? tRaw : 7 / 365
    iv =
      impliedVolatility(premium, spot, trade.strike, t, kind as OptionKind) ??
      0.15
    delta = blackScholesDelta(spot, trade.strike, t, iv, kind as OptionKind)
    gamma = blackScholesGamma(spot, trade.strike, t, iv)
    theta = blackScholesTheta(spot, trade.strike, t, iv, kind as OptionKind)
    vega = blackScholesVega(spot, trade.strike, t, iv)
    if (delta != null && !Number.isFinite(delta)) delta = null
    if (gamma != null && !Number.isFinite(gamma)) gamma = null
    if (theta != null && !Number.isFinite(theta)) theta = null
    if (vega != null && !Number.isFinite(vega)) vega = null
    if (iv != null && !Number.isFinite(iv)) iv = 0.15
  }

  return {
    id: trade.id,
    instrumentKey: trade.instrumentKey,
    tradingSymbol: trade.tradingSymbol ?? trade.instrumentKey,
    kind,
    side,
    lots,
    lotSize,
    entryPrice: trade.entryPrice,
    strike: trade.strike,
    expiry,
    underlyingKey,
    underlyingSymbol,
    assetClass: 'index',
    iv,
    delta,
    gamma,
    theta,
    vega,
  }
}
