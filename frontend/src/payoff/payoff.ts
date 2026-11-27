import { blackScholesPrice, type OptionKind } from './blackScholes'

export type LegSide = 'BUY' | 'SELL'
export type LegKind = 'FUT' | 'CE' | 'PE'

export interface StrategyLeg {
  id: string
  instrumentKey: string
  tradingSymbol: string
  kind: LegKind
  side: LegSide
  lots: number
  lotSize: number
  entryPrice: number
  strike: number | null
  expiry: string | null
  underlyingKey: string | null
  underlyingSymbol: string | null
  assetClass: string
  iv: number | null
  delta?: number | null
  theta?: number | null
  gamma?: number | null
  vega?: number | null
}

export interface PayoffPoint {
  spot: number
  expiryPnl: number
  t0Pnl: number
}

export interface PayoffSummary {
  points: PayoffPoint[]
  breakevens: number[]
  maxProfit: number | null
  maxLoss: number | null
  minSpot: number
  maxSpot: number
}

export type ScenarioOverrides = {
  /** Days remaining used for T0 pricing (expiry payoff ignores this). */
  asOfDays?: number | null
  /** Multiplier on each leg IV for T0 (1 = 100%). */
  ivScale?: number
}

function signedMultiplier(side: LegSide): number {
  return side === 'BUY' ? 1 : -1
}

function quantity(leg: StrategyLeg): number {
  return Math.max(leg.lots, 0) * Math.max(leg.lotSize, 1)
}

function intrinsic(kind: LegKind, spot: number, strike: number | null): number {
  if (kind === 'FUT' || strike == null) {
    return spot
  }
  if (kind === 'CE') {
    return Math.max(spot - strike, 0)
  }
  return Math.max(strike - spot, 0)
}

const MONTH_INDEX: Record<string, number> = {
  JAN: 0,
  FEB: 1,
  MAR: 2,
  APR: 3,
  MAY: 4,
  JUN: 5,
  JUL: 6,
  AUG: 7,
  SEP: 8,
  OCT: 9,
  NOV: 10,
  DEC: 11,
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/** Normalize expiry to ISO `YYYY-MM-DD`. Accepts ISO, `dd-MMM-yyyy`, `ddMMMyyyy`, and embedded forms. */
export function normalizeExpiryIso(raw: string | null | undefined): string | null {
  if (raw == null || !String(raw).trim()) {
    return null
  }
  const s = String(raw).trim()

  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) {
    const ms = new Date(`${s}T15:30:00+05:30`).getTime()
    return Number.isFinite(ms) ? s : null
  }

  const dashed = s.match(/^(\d{1,2})[-/\s]([A-Za-z]{3})[-/\s](\d{2}|\d{4})$/)
  if (dashed) {
    const day = Number(dashed[1])
    const mon = MONTH_INDEX[dashed[2]!.toUpperCase()]
    let year = Number(dashed[3])
    if (mon == null || !Number.isFinite(day) || !Number.isFinite(year)) {
      return null
    }
    if (year < 100) {
      year += 2000
    }
    const iso = `${year}-${pad2(mon + 1)}-${pad2(day)}`
    const ms = new Date(`${iso}T15:30:00+05:30`).getTime()
    return Number.isFinite(ms) ? iso : null
  }

  const compact = s.match(/(\d{1,2})([A-Za-z]{3})(\d{4})/)
  if (compact) {
    const day = Number(compact[1])
    const mon = MONTH_INDEX[compact[2]!.toUpperCase()]
    const year = Number(compact[3])
    if (mon == null || !Number.isFinite(day) || !Number.isFinite(year)) {
      return null
    }
    const iso = `${year}-${pad2(mon + 1)}-${pad2(day)}`
    const ms = new Date(`${iso}T15:30:00+05:30`).getTime()
    return Number.isFinite(ms) ? iso : null
  }

  return null
}

export function parseExpiryMs(raw: string | null | undefined): number | null {
  const iso = normalizeExpiryIso(raw)
  if (!iso) {
    return null
  }
  const ms = new Date(`${iso}T15:30:00+05:30`).getTime()
  return Number.isFinite(ms) ? ms : null
}

export function yearsToExpiry(expiry: string | null | undefined, now = new Date()): number {
  const end = parseExpiryMs(expiry)
  if (end == null) {
    return 0
  }
  const ms = end - now.getTime()
  return Math.max(ms / (365.25 * 24 * 60 * 60 * 1000), 0)
}

function yearsFromDays(days: number): number {
  return Math.max(days, 0) / 365.25
}

export function legExpiryPnl(leg: StrategyLeg, spot: number): number {
  const qty = quantity(leg)
  const sign = signedMultiplier(leg.side)
  if (leg.kind === 'FUT') {
    return sign * (spot - leg.entryPrice) * qty
  }
  const value = intrinsic(leg.kind, spot, leg.strike)
  return sign * (value - leg.entryPrice) * qty
}

export function legT0Pnl(
  leg: StrategyLeg,
  spot: number,
  now = new Date(),
  scenario?: ScenarioOverrides,
): number {
  const qty = quantity(leg)
  const sign = signedMultiplier(leg.side)
  if (leg.kind === 'FUT') {
    return sign * (spot - leg.entryPrice) * qty
  }
  const ivScale = scenario?.ivScale ?? 1
  const iv = leg.iv != null && leg.iv > 0 ? leg.iv * ivScale : null
  if (leg.strike == null || iv == null || iv <= 0) {
    return legExpiryPnl(leg, spot)
  }
  const t =
    scenario?.asOfDays != null
      ? yearsFromDays(scenario.asOfDays)
      : yearsToExpiry(leg.expiry, now)
  if (!Number.isFinite(t)) {
    return legExpiryPnl(leg, spot)
  }
  const theoretical = blackScholesPrice(spot, leg.strike, t, iv, leg.kind as OptionKind)
  if (!Number.isFinite(theoretical)) {
    return legExpiryPnl(leg, spot)
  }
  return sign * (theoretical - leg.entryPrice) * qty
}

export function strategyExpiryPnl(legs: StrategyLeg[], spot: number): number {
  return legs.reduce((sum, leg) => sum + legExpiryPnl(leg, spot), 0)
}

export function strategyT0Pnl(
  legs: StrategyLeg[],
  spot: number,
  now = new Date(),
  scenario?: ScenarioOverrides,
): number {
  return legs.reduce((sum, leg) => sum + legT0Pnl(leg, spot, now, scenario), 0)
}

function uniqueSorted(values: number[]): number[] {
  return [...new Set(values.map((v) => Number(v.toFixed(4))))].sort((a, b) => a - b)
}

export function findBreakevens(points: PayoffPoint[]): number[] {
  const result: number[] = []
  for (let i = 1; i < points.length; i += 1) {
    const prev = points[i - 1]
    const curr = points[i]
    if (prev.expiryPnl === 0) {
      result.push(prev.spot)
      continue
    }
    if (prev.expiryPnl * curr.expiryPnl < 0) {
      const ratio = Math.abs(prev.expiryPnl) / (Math.abs(prev.expiryPnl) + Math.abs(curr.expiryPnl))
      result.push(prev.spot + (curr.spot - prev.spot) * ratio)
    }
  }
  return uniqueSorted(result)
}

function samplePayoff(
  legs: StrategyLeg[],
  minSpot: number,
  maxSpot: number,
  samples: number,
  scenario?: ScenarioOverrides,
): PayoffPoint[] {
  const step = (maxSpot - minSpot) / (samples - 1)
  const points: PayoffPoint[] = []
  for (let i = 0; i < samples; i += 1) {
    const spot = minSpot + step * i
    points.push({
      spot,
      expiryPnl: strategyExpiryPnl(legs, spot),
      t0Pnl: strategyT0Pnl(legs, spot, new Date(), scenario),
    })
  }
  return points
}

/**
 * Soft window around the strategy so both plateaus stay visible.
 */
function fitSpotWindow(legs: StrategyLeg[], center: number, roughSpan: number, samples: number) {
  let minSpot = Math.max(center - roughSpan, 0.01)
  let maxSpot = center + roughSpan
  let points = samplePayoff(legs, minSpot, maxSpot, samples)
  const peak = Math.max(...points.map((p) => p.expiryPnl))
  const trough = Math.min(...points.map((p) => p.expiryPnl))

  // Soft clip only extreme wings (allow larger loss wings than before).
  if (peak > 0 && trough < 0) {
    const lossCap = -(peak * 1.25)
    while (points.length > 40 && points[0].expiryPnl < lossCap) {
      minSpot = points[1].spot
      points = points.slice(1)
    }
    while (points.length > 40 && points[points.length - 1].expiryPnl < lossCap) {
      maxSpot = points[points.length - 2].spot
      points = points.slice(0, -1)
    }
  } else if (trough < 0 && peak <= 0) {
    const target = Math.abs(trough) * 1.5
    let guard = 0
    while (guard < 8 && Math.max(points[0].expiryPnl, points[points.length - 1].expiryPnl) < target) {
      const widen = (maxSpot - minSpot) * 0.25
      minSpot = Math.max(minSpot - widen, 0.01)
      maxSpot += widen
      points = samplePayoff(legs, minSpot, maxSpot, samples)
      guard += 1
    }
  }

  const breakevens = findBreakevens(points)
  if (breakevens.length >= 1) {
    const lo = Math.min(...breakevens)
    const hi = Math.max(...breakevens)
    const pad = Math.max((hi - lo) * 0.55, center * 0.02, 100)
    minSpot = Math.min(minSpot, Math.max(lo - pad, 0.01))
    maxSpot = Math.max(maxSpot, hi + pad)
  }

  const strikes = legs.map((leg) => leg.strike).filter((s): s is number => s != null && s > 0)
  if (strikes.length > 0) {
    const strikeMin = Math.min(...strikes)
    const strikeMax = Math.max(...strikes)
    const wing = Math.max(strikeMax - strikeMin, 50)
    const strikePad = Math.max(wing * 2.5, center * 0.015, 150)
    minSpot = Math.min(minSpot, Math.max(strikeMin - strikePad, 0.01))
    maxSpot = Math.max(maxSpot, strikeMax + strikePad)
  }

  return { minSpot, maxSpot }
}

export function buildPayoff(
  legs: StrategyLeg[],
  spotHint: number | null,
  samples = 220,
  scenario?: ScenarioOverrides,
): PayoffSummary | null {
  if (legs.length === 0) {
    return null
  }

  const strikes = legs.map((leg) => leg.strike).filter((s): s is number => s != null && s > 0)
  const entries = legs.map((leg) => leg.entryPrice).filter((p) => p > 0)
  const center =
    spotHint && spotHint > 0
      ? spotHint
      : strikes.length > 0
        ? strikes.reduce((a, b) => a + b, 0) / strikes.length
        : entries.length > 0
          ? entries.reduce((a, b) => a + b, 0) / entries.length
          : 100

  const strikeMin = strikes.length > 0 ? Math.min(...strikes) : center
  const strikeMax = strikes.length > 0 ? Math.max(...strikes) : center
  const wing = Math.max(strikeMax - strikeMin, 50)
  // Prefer a tight window around strikes so the payoff uses full chart width.
  const strikeSpan = strikes.length > 0 ? Math.max(...strikes.map((s) => Math.abs(s - center))) : 0
  const premiumSpan =
    strikes.length > 0
      ? Math.max(
          ...legs.filter((l) => l.kind !== 'FUT').map((l) => Math.abs(l.entryPrice) * 3),
          wing * 2,
        )
      : center * 0.04
  const roughSpan = Math.max(premiumSpan * 1.4, strikeSpan * 1.4, wing * 3, center * 0.025, 100)

  const fitted = fitSpotWindow(legs, center, roughSpan, samples)
  const points = samplePayoff(legs, fitted.minSpot, fitted.maxSpot, samples, scenario)

  const pnls = points.map((p) => p.expiryPnl)
  const first = pnls[0]
  const last = pnls[pnls.length - 1]
  const peak = Math.max(...pnls)
  const trough = Math.min(...pnls)
  const n = pnls.length
  const eps = 1e-6
  // Outward-falling / rising wings → unbounded loss / profit (naked shorts / longs).
  const fallingLeft = n >= 2 && first < pnls[1]! - eps
  const fallingRight = n >= 2 && last < pnls[n - 2]! - eps
  const risingLeft = n >= 2 && first > pnls[1]! + eps
  const risingRight = n >= 2 && last > pnls[n - 2]! + eps
  const troughOnEdge =
    Math.abs(first - trough) <= eps || Math.abs(last - trough) <= eps
  const peakOnEdge = Math.abs(first - peak) <= eps || Math.abs(last - peak) <= eps

  const maxLoss =
    troughOnEdge && (fallingLeft || fallingRight) ? null : trough
  const maxProfit =
    peakOnEdge && (risingLeft || risingRight) ? null : peak

  return {
    points,
    breakevens: findBreakevens(points),
    maxProfit,
    maxLoss,
    minSpot: fitted.minSpot,
    maxSpot: fitted.maxSpot,
  }
}

export function pnlAtSpot(
  legs: StrategyLeg[],
  spot: number,
  scenario?: ScenarioOverrides,
): { expiryPnl: number; t0Pnl: number } {
  return {
    expiryPnl: strategyExpiryPnl(legs, spot),
    t0Pnl: strategyT0Pnl(legs, spot, new Date(), scenario),
  }
}

export function netPremium(legs: StrategyLeg[]): number {
  return legs.reduce((sum, leg) => {
    const qty = quantity(leg)
    const signed = signedMultiplier(leg.side) * -1
    return sum + signed * leg.entryPrice * qty
  }, 0)
}

export function daysToExpiry(legs: StrategyLeg[], now = new Date()): number | null {
  const expiries = legs
    .map((leg) => parseExpiryMs(leg.expiry))
    .filter((value): value is number => value != null && Number.isFinite(value))
  if (expiries.length === 0) {
    return null
  }
  const nearest = Math.min(...expiries)
  const days = Math.ceil((nearest - now.getTime()) / (24 * 60 * 60 * 1000))
  return Number.isFinite(days) ? Math.max(0, days) : null
}

export function blendedIv(legs: StrategyLeg[]): number | null {
  const ivs = legs
    .filter((leg) => leg.kind !== 'FUT' && leg.iv != null && leg.iv > 0)
    .map((leg) => leg.iv as number)
  if (ivs.length === 0) {
    return null
  }
  return ivs.reduce((a, b) => a + b, 0) / ivs.length
}

/** Approximate POP via Monte Carlo of lognormal terminal spot. */
export function estimatePop(
  legs: StrategyLeg[],
  spot: number | null,
  scenario?: ScenarioOverrides,
  samples = 800,
): number | null {
  if (legs.length === 0 || spot == null || spot <= 0) {
    return null
  }
  const iv = blendedIv(legs)
  if (iv == null) {
    return null
  }
  const scaledIv = iv * (scenario?.ivScale ?? 1)
  const calendarDte = daysToExpiry(legs)
  const days =
    scenario?.asOfDays != null
      ? scenario.asOfDays
      : calendarDte != null
        ? calendarDte
        : 7
  const t = Math.max(days, 0.25) / 365.25
  const drift = -0.5 * scaledIv * scaledIv * t
  const vol = scaledIv * Math.sqrt(t)
  let wins = 0
  for (let i = 0; i < samples; i += 1) {
    // Box-Muller
    const u1 = Math.max(Math.random(), 1e-12)
    const u2 = Math.random()
    const z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)
    const terminal = spot * Math.exp(drift + vol * z)
    if (strategyExpiryPnl(legs, terminal) > 0) {
      wins += 1
    }
  }
  return (wins / samples) * 100
}

/** Rough Indian F&O margin heuristic (not SPAN). */
export function estimateMargin(legs: StrategyLeg[], spot: number | null): number | null {
  if (legs.length === 0) {
    return null
  }
  const ref = spot && spot > 0 ? spot : null
  let margin = 0
  let hasShort = false
  for (const leg of legs) {
    const qty = quantity(leg)
    const premium = Math.abs(leg.entryPrice) * qty
    if (leg.side === 'BUY') {
      margin += premium
      continue
    }
    hasShort = true
    if (leg.kind === 'FUT') {
      const notional = (ref ?? leg.entryPrice) * qty
      margin += notional * 0.12
    } else {
      const underlier = ref ?? leg.strike ?? 0
      margin += premium + underlier * qty * 0.1
    }
  }
  if (!hasShort) {
    return Math.max(margin, 0)
  }
  return Math.max(margin, 0)
}

export function strategyGreeks(legs: StrategyLeg[]): {
  delta: number | null
  theta: number | null
  gamma: number | null
  vega: number | null
} {
  let hasAny = false
  let delta = 0
  let theta = 0
  let gamma = 0
  let vega = 0
  for (const leg of legs) {
    const sign = signedMultiplier(leg.side)
    const qty = quantity(leg)
    if (leg.delta != null && Number.isFinite(leg.delta)) {
      hasAny = true
      delta += sign * leg.delta * qty
    }
    if (leg.theta != null && Number.isFinite(leg.theta)) {
      hasAny = true
      theta += sign * leg.theta * qty
    }
    if (leg.gamma != null && Number.isFinite(leg.gamma)) {
      hasAny = true
      gamma += sign * leg.gamma * qty
    }
    if (leg.vega != null && Number.isFinite(leg.vega)) {
      hasAny = true
      vega += sign * leg.vega * qty
    }
  }
  if (!hasAny) {
    return { delta: null, theta: null, gamma: null, vega: null }
  }
  return { delta, theta, gamma, vega }
}
