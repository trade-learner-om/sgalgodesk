const SQRT_2PI = Math.sqrt(2 * Math.PI)

function normPdf(x: number): number {
  return Math.exp(-0.5 * x * x) / SQRT_2PI
}

function normCdf(x: number): number {
  const t = 1 / (1 + 0.2316419 * Math.abs(x))
  const d = 0.3989423 * Math.exp((-x * x) / 2)
  let probability =
    d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
  if (x > 0) {
    probability = 1 - probability
  }
  return probability
}

export type OptionKind = 'CE' | 'PE'

function d1d2(
  spot: number,
  strike: number,
  timeYears: number,
  volatility: number,
  rate: number,
): { d1: number; d2: number; sqrtT: number } | null {
  if (timeYears <= 0 || volatility <= 0 || spot <= 0 || strike <= 0) {
    return null
  }
  const sqrtT = Math.sqrt(timeYears)
  const d1 =
    (Math.log(spot / strike) + (rate + 0.5 * volatility * volatility) * timeYears) /
    (volatility * sqrtT)
  return { d1, d2: d1 - volatility * sqrtT, sqrtT }
}

export function blackScholesPrice(
  spot: number,
  strike: number,
  timeYears: number,
  volatility: number,
  kind: OptionKind,
  rate = 0,
): number {
  const parts = d1d2(spot, strike, timeYears, volatility, rate)
  if (!parts) {
    const intrinsic = kind === 'CE' ? Math.max(spot - strike, 0) : Math.max(strike - spot, 0)
    return intrinsic
  }
  const discount = Math.exp(-rate * timeYears)
  if (kind === 'CE') {
    return spot * normCdf(parts.d1) - strike * discount * normCdf(parts.d2)
  }
  return strike * discount * normCdf(-parts.d2) - spot * normCdf(-parts.d1)
}

export function blackScholesDelta(
  spot: number,
  strike: number,
  timeYears: number,
  volatility: number,
  kind: OptionKind,
  rate = 0,
): number {
  const parts = d1d2(spot, strike, timeYears, volatility, rate)
  if (!parts) {
    if (kind === 'CE') {
      return spot > strike ? 1 : 0
    }
    return spot < strike ? -1 : 0
  }
  return kind === 'CE' ? normCdf(parts.d1) : normCdf(parts.d1) - 1
}

export function blackScholesVega(
  spot: number,
  strike: number,
  timeYears: number,
  volatility: number,
  rate = 0,
): number {
  const parts = d1d2(spot, strike, timeYears, volatility, rate)
  if (!parts) {
    return 0
  }
  // Per 1% vol move (standard display convention)
  return (spot * normPdf(parts.d1) * parts.sqrtT) / 100
}

export function blackScholesGamma(
  spot: number,
  strike: number,
  timeYears: number,
  volatility: number,
  rate = 0,
): number {
  const parts = d1d2(spot, strike, timeYears, volatility, rate)
  if (!parts) {
    return 0
  }
  return normPdf(parts.d1) / (spot * volatility * parts.sqrtT)
}

export function blackScholesTheta(
  spot: number,
  strike: number,
  timeYears: number,
  volatility: number,
  kind: OptionKind,
  rate = 0,
): number {
  const parts = d1d2(spot, strike, timeYears, volatility, rate)
  if (!parts) {
    return 0
  }
  const discount = Math.exp(-rate * timeYears)
  const first = (-spot * normPdf(parts.d1) * volatility) / (2 * parts.sqrtT)
  if (kind === 'CE') {
    const second = -rate * strike * discount * normCdf(parts.d2)
    return (first + second) / 365
  }
  const second = rate * strike * discount * normCdf(-parts.d2)
  return (first + second) / 365
}

/** Binary-search implied vol from market premium (fractional, e.g. 0.15 = 15%). */
export function impliedVolatility(
  marketPrice: number,
  spot: number,
  strike: number,
  timeYears: number,
  kind: OptionKind,
  rate = 0,
): number | null {
  if (
    marketPrice <= 0 ||
    spot <= 0 ||
    strike <= 0 ||
    timeYears <= 0 ||
    !Number.isFinite(marketPrice)
  ) {
    return null
  }
  let lo = 0.01
  let hi = 3
  const intrinsic = kind === 'CE' ? Math.max(spot - strike, 0) : Math.max(strike - spot, 0)
  if (marketPrice < intrinsic * 0.98) {
    return null
  }
  for (let i = 0; i < 40; i++) {
    const mid = (lo + hi) / 2
    const price = blackScholesPrice(spot, strike, timeYears, mid, kind, rate)
    if (price > marketPrice) {
      hi = mid
    } else {
      lo = mid
    }
  }
  const iv = (lo + hi) / 2
  return Number.isFinite(iv) && iv > 0 ? iv : null
}
