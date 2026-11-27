export type SpreadTemplateId =
  | 'bull_call'
  | 'bull_put'
  | 'bear_call'
  | 'bear_put'
  | 'long_straddle'
  | 'short_straddle'

export type SpreadTemplate = {
  id: SpreadTemplateId
  name: string
  preview: string
}

export const SPREAD_TEMPLATES: SpreadTemplate[] = [
  { id: 'bull_call', name: 'Bull Call', preview: 'Buy ATM CE · Sell ATM+2 CE' },
  { id: 'bull_put', name: 'Bull Put', preview: 'Buy ATM−2 PE · Sell ATM PE' },
  { id: 'bear_call', name: 'Bear Call', preview: 'Sell ATM CE · Buy ATM+2 CE' },
  { id: 'bear_put', name: 'Bear Put', preview: 'Sell ATM−2 PE · Buy ATM PE' },
  { id: 'long_straddle', name: 'Long Straddle', preview: 'Buy ATM CE · Buy ATM PE' },
  { id: 'short_straddle', name: 'Short Straddle', preview: 'Sell ATM CE · Sell ATM PE' },
]

/** Illustrative muted payoff polylines in a 120×56 viewBox (y grows downward). */
export const TEMPLATE_PREVIEW_PATHS: Record<
  SpreadTemplateId,
  { line: string; profit: string; loss: string }
> = {
  bull_call: {
    line: 'M 8 40 L 48 40 L 72 16 L 112 16',
    profit: 'M 48 40 L 72 16 L 112 16 L 112 28 L 72 28 L 56 40 Z',
    loss: 'M 8 40 L 48 40 L 48 48 L 8 48 Z',
  },
  bull_put: {
    line: 'M 8 40 L 40 40 L 64 16 L 112 16',
    profit: 'M 40 40 L 64 16 L 112 16 L 112 28 L 64 28 L 48 40 Z',
    loss: 'M 8 40 L 40 40 L 40 48 L 8 48 Z',
  },
  bear_call: {
    line: 'M 8 16 L 48 16 L 72 40 L 112 40',
    profit: 'M 8 16 L 48 16 L 48 28 L 8 28 Z',
    loss: 'M 48 16 L 72 40 L 112 40 L 112 48 L 72 48 L 56 28 Z',
  },
  bear_put: {
    line: 'M 8 16 L 40 16 L 64 40 L 112 40',
    profit: 'M 8 16 L 40 16 L 40 28 L 8 28 Z',
    loss: 'M 40 16 L 64 40 L 112 40 L 112 48 L 64 48 L 48 28 Z',
  },
  long_straddle: {
    line: 'M 8 44 L 60 12 L 112 44',
    profit: 'M 36 28 L 60 12 L 84 28 L 60 28 Z',
    loss: 'M 8 44 L 36 28 L 36 48 L 8 48 Z M 84 28 L 112 44 L 112 48 L 84 48 Z',
  },
  short_straddle: {
    line: 'M 8 12 L 60 44 L 112 12',
    profit: 'M 8 12 L 36 28 L 36 8 L 8 8 Z M 84 28 L 112 12 L 112 8 L 84 8 Z',
    loss: 'M 36 28 L 60 44 L 84 28 L 60 28 Z',
  },
}
