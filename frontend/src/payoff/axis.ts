export const TICK_STEPS = [20, 50, 100, 200, 250, 500] as const

export function pickTickStep(viewSpan: number, targetTicks = 6): number {
  const span = Math.max(viewSpan, 1)
  let best: (typeof TICK_STEPS)[number] = TICK_STEPS[0]
  for (const step of TICK_STEPS) {
    best = step
    if (span / step <= targetTicks + 1) {
      break
    }
  }
  return best
}

export function buildTicks(viewMin: number, viewMax: number, step: number): number[] {
  if (step <= 0 || viewMax <= viewMin) {
    return []
  }
  const start = Math.ceil(viewMin / step) * step
  const ticks: number[] = []
  for (let value = start; value <= viewMax + step * 1e-9; value += step) {
    ticks.push(Number(value.toFixed(6)))
    if (ticks.length > 40) {
      break
    }
  }
  return ticks
}

export function pickYTicks(yMin: number, yMax: number, count = 5): number[] {
  const span = yMax - yMin
  if (!Number.isFinite(span) || span <= 0) {
    return [0]
  }
  const rough = span / Math.max(count - 1, 1)
  const magnitude = 10 ** Math.floor(Math.log10(Math.max(rough, 1)))
  const niceSteps = [1, 2, 2.5, 5, 10].map((n) => n * magnitude)
  let step = niceSteps[niceSteps.length - 1]
  for (const candidate of niceSteps) {
    if (span / candidate <= count + 1) {
      step = candidate
      break
    }
  }
  const start = Math.ceil(yMin / step) * step
  const ticks: number[] = []
  for (let value = start; value <= yMax + step * 1e-9; value += step) {
    ticks.push(value)
    if (ticks.length > 12) {
      break
    }
  }
  if (!ticks.some((t) => Math.abs(t) < step * 0.01)) {
    ticks.push(0)
    ticks.sort((a, b) => a - b)
  }
  return ticks
}

export function formatAxisPrice(value: number): string {
  return value.toLocaleString('en-IN', { maximumFractionDigits: 0 })
}

export function formatAxisPnl(value: number): string {
  const abs = Math.abs(value)
  if (abs >= 100_000) {
    return `${(value / 100_000).toFixed(1)}L`
  }
  if (abs >= 1_000) {
    return `${(value / 1_000).toFixed(abs >= 10_000 ? 0 : 1)}K`
  }
  return value.toLocaleString('en-IN', { maximumFractionDigits: 0, signDisplay: 'exceptZero' })
}
