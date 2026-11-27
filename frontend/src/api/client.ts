export type PreferredUnderlying = {
  instrumentKey: string
  tradingSymbol: string
  assetClass: string | null
}

export type BrokerId = 'MSTOCK'

export type BrokerStatus = {
  id: BrokerId
  credentialsConfigured: boolean
  linked: boolean
  tokenValid: boolean
  userName: string | null
}

export type MeResponse = {
  email: string
  credentialsConfigured: boolean
  brokerLinked: boolean
  userName: string | null
  tokenValid: boolean
  preferredUnderlying: PreferredUnderlying | null
  activeBroker?: BrokerId
  brokers?: BrokerStatus[]
}

export type InstrumentSearchHit = {
  instrumentKey: string
  tradingSymbol: string
  name: string
  kind: string
  strike: number | null
  expiry: string | null
  lotSize: number | null
  tickSize: number | null
  underlyingKey: string | null
  underlyingSymbol: string | null
  assetClass: string
}

export type UnderlyingHit = {
  instrumentKey: string
  tradingSymbol: string
  name: string
  assetClass: string
  lotSize: number | null
}

export type TradableInstrument = {
  symbol: string
  lotSize: number | null
  underlyingKey: string
}

export type TradableInstrumentsResponse = {
  instruments: TradableInstrument[]
}

export type OptionSide = {
  instrumentKey: string
  tradingSymbol: string | null
  ltp: number | null
  closePrice: number | null
  ltpChangePct: number | null
  oi: number | null
  prevOi: number | null
  volume: number | null
  iv: number | null
  delta: number | null
  theta: number | null
  gamma: number | null
  vega: number | null
  pop: number | null
  bid: number | null
  ask: number | null
  lotSize: number | null
}

export type OptionStrikeRow = {
  strike: number
  call: OptionSide | null
  put: OptionSide | null
  pcr: number | null
}

export type OptionChainResponse = {
  underlyingKey: string
  underlyingSymbol: string | null
  underlyingSpot: number | null
  expiryDate: string
  pcr: number | null
  maxPain: number | null
  lotSize: number | null
  futuresKey: string | null
  futuresSymbol: string | null
  strikes: OptionStrikeRow[]
}

export type LtpResponse = {
  prices: Record<string, number>
}

export type GreeksResponse = {
  instrumentKey: string
  iv: number | null
  delta: number | null
  gamma: number | null
  theta: number | null
  vega: number | null
}

export type PositionItem = {
  instrumentKey: string
  tradingSymbol: string | null
  exchange: string | null
  product: string | null
  quantity: number
  averagePrice: number
  lastPrice: number
  pnl: number
  realised: number
  unrealised: number
  underlyingKey: string | null
  underlyingSymbol: string | null
  instrumentType: string | null
  strike: number | null
  expiry: string | null
  lotSize: number | null
  open: boolean
  /** ISO timestamp of earliest fill when known from trade book. */
  entryAt?: string | null
}

export type PositionGroup = {
  underlyingKey: string
  underlyingSymbol: string | null
  positions: PositionItem[]
}

export type PositionsResponse = {
  openGroups: PositionGroup[]
  closedGroups: PositionGroup[]
  brokerRowCount: number
  skippedRowCount: number
  brokerUserId: string | null
  brokerUserName: string | null
  todayOrderCount: number
  todayNfoOrderCount: number
  source: string
}

export type PlaceOrderBody = {
  instrumentKey: string
  transactionType: 'BUY' | 'SELL'
  quantity: number
  product?: string
  orderType?: string
  validity?: string
  price?: number
  tag?: string
  slice?: boolean
}

export type PlaceOrderResult = {
  instrumentKey: string
  status: string
  orderIds: string[]
  raw: string | null
}

export type PlaceMultiResult = {
  results: PlaceOrderResult[]
}

export type Bias = 'LONG' | 'SHORT' | 'NONE'

export type SignalResponse = {
  instrumentKey: string
  timestamp: string
  close: number
  ema9: number
  supertrend: number
  supertrendDirection: string
  bias: Bias
  reason: string
}

export type SuggestedLeg = {
  instrumentKey: string
  tradingSymbol: string | null
  kind: string
  side: string
  strike: number
  expiry: string
  lots: number
  lotSize: number | null
  premium: number
}

export type WingTrial = {
  otmSteps: number
  sellStrike: number
  buyStrike: number
  creditPerLot: number
  maxProfitPerLot: number
  maxLossPerLot: number
  riskReward: number
  legs: SuggestedLeg[]
}

export type ExpiryScenario = {
  expiry: string
  daysToExpiry: number
  preferred: boolean
  bestWing: WingTrial | null
  allWings: WingTrial[]
}

export type SuggestResponse = {
  instrumentKey: string
  underlyingSymbol: string | null
  spot: number
  bias: Bias
  debit: boolean
  spreadType: string | null
  signal: SignalResponse
  scenarios: ExpiryScenario[]
}

export type SpreadHistoryLeg = {
  instrumentKey: string
  side: string
  quantity: number
  lotSize: number
  lots: number
  entryPrice: number
}

export type SpreadHistoryRequest = {
  legs: SpreadHistoryLeg[]
  openDay?: string
  /** ISO instant when the spread was established — history starts at this bar. */
  entryAt?: string | null
}

export type SpreadHistoryPoint = {
  ts: number
  runningPnl1Lot: number
  runningPnl: number
  spreadMark: number
  combinedPremium: number
  lots: number
  kind: string
  open: number
  high: number
  low: number
  close: number
}

export type SpreadHistoryResponse = {
  points: SpreadHistoryPoint[]
  lots: number
  kind: string
  incomplete?: boolean
  /** Human-readable empty/closed reason from backend (no poll when incomplete is false). */
  message?: string | null
}

export type AlgoExecutionMode = 'PAPER' | 'LIVE'

export type AlgoStrategyConfig = {
  instrument: {
    index: string
    underlyingFrom: string
  }
  entry: {
    strategyType: string
    entryTime: string
    exitTime: string
    noReentryAfterEnabled: boolean
    noReentryAfterTime: string | null
    stopMonitoringAfterEnabled: boolean
    stopMonitoringAfterTime: string | null
  }
  legwise: {
    squareOff: string
    trailSlToBreakeven: boolean
    trailSlBreakevenScope: string | null
  }
  legs: AlgoLegConfig[]
  overall: {
    overallSlEnabled: boolean
    overallSlType: string | null
    overallSlValue: number | null
    overallTargetEnabled: boolean
    overallTargetType: string | null
    overallTargetValue: number | null
    overallMomentumEnabled: boolean
    overallMomentumType: string | null
    overallMomentumValue: number | null
    reentryOnSlEnabled: boolean
    reentryOnSlMode: string | null
    reentryOnSlCount: number | null
    reentryOnTgtEnabled: boolean
    reentryOnTgtMode: string | null
    reentryOnTgtCount: number | null
    trailingEnabled: boolean
    trailingMode: string | null
    profitReaches: number | null
    lockProfit: number | null
    trailStepProfit: number | null
    trailStepLock: number | null
  }
}

export type AlgoLegConfig = {
  segment: string | null
  lots: number
  position: string | null
  optionType: string | null
  expiry: string | null
  strikeCriteria: string | null
  strikeType: string | null
  strikeParam1: number | null
  strikeParam2: number | null
  targetEnabled: boolean
  targetUnit: string | null
  targetValue: number | null
  slEnabled: boolean
  slUnit: string | null
  slValue: number | null
  trailSlEnabled: boolean
  trailSlUnit: string | null
  trailSlTrigger: number | null
  trailSlMove: number | null
  reentryTgtEnabled: boolean
  reentryTgtMode: string | null
  reentryTgtCount: number | null
  reentrySlEnabled: boolean
  reentrySlMode: string | null
  reentrySlCount: number | null
  rangeBreakoutEnabled: boolean
  rangeBreakoutTime: string | null
  rangeBreakoutDirection: string | null
  rangeBreakoutOn: string | null
  simpleMomentumEnabled: boolean
  simpleMomentumUnit: string | null
  simpleMomentumValue: number | null
}

export type AlgoStrategyRun = {
  id: string
  strategyId: string
  runDate: string
  executionMode: AlgoExecutionMode | string
  lifecycleState: string
  realisedPnl: number
  unrealisedPnl: number
  reentryCount: number
  trailFloorPnl: number | null
  peakPnl: number
  startedAt: string
  endedAt: string | null
}

export type AlgoStrategyTrade = {
  id: string
  strategyId: string
  runId: string
  legIndex: number
  instrumentKey: string
  tradingSymbol: string | null
  side: string
  quantity: number
  executionMode: string
  brokerOrderId: string | null
  slOrderId: string | null
  slTriggerPrice: number | null
  entryPrice: number
  exitPrice: number | null
  pnl: number
  triggerReason: string
  status: string
  executedAt: string
  closedAt: string | null
  lotSize: number | null
  strike: number | null
  expiry: string | null
  optionType: string | null
}

export type AlgoStrategyEvent = {
  id: string
  strategyId: string
  runId: string | null
  eventType: string
  message: string
  createdAt: string
}

export type AlgoStrategySummary = {
  id: string
  name: string
  status: string
  executionMode: AlgoExecutionMode | string
  config: AlgoStrategyConfig
  activeRun: AlgoStrategyRun | null
  activeTrades: AlgoStrategyTrade[]
  totalTrades: number
  tradesToday: number
  totalPnl: number
  todayPnl: number
  createdAt: string
  updatedAt: string
}

export type AlgoStrategyLifecycle = {
  strategy: AlgoStrategySummary
  runs: AlgoStrategyRun[]
  trades: AlgoStrategyTrade[]
  events: AlgoStrategyEvent[]
}

export class ApiError extends Error {
  status: number
  body: { error?: string; status?: number }

  constructor(status: number, body: { error?: string; status?: number }) {
    super(body.error ?? `Request failed (${status})`)
    this.status = status
    this.body = body
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
    ...init,
  })

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  const body = text ? JSON.parse(text) : {}
  if (!response.ok) {
    throw new ApiError(response.status, body)
  }
  return body as T
}

export const api = {
  me: () => request<MeResponse>('/api/account/me'),
  signup: (email: string, password: string) =>
    request<MeResponse>('/api/account/signup', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),
  login: (email: string, password: string) =>
    request<MeResponse>('/api/account/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),
  logout: () => request<void>('/api/account/logout', { method: 'POST' }),
  saveSettings: (instrumentKey: string, tradingSymbol: string, assetClass: string | null) =>
    request<PreferredUnderlying>('/api/account/settings', {
      method: 'PUT',
      body: JSON.stringify({ instrumentKey, tradingSymbol, assetClass }),
    }),
  setActiveBroker: (activeBroker: BrokerId) =>
    request<PreferredUnderlying>('/api/account/settings', {
      method: 'PUT',
      body: JSON.stringify({ activeBroker }),
    }),
  saveMstockApiKey: (apiKey: string) =>
    request<void>('/api/brokers/mstock/credentials', {
      method: 'PUT',
      body: JSON.stringify({ apiKey }),
    }),
  clearMstockCredentials: () =>
    request<void>('/api/brokers/mstock/credentials', { method: 'DELETE' }),
  mstockPrimaryIp: () => request<{ ip: string }>('/api/brokers/mstock/primary-ip'),
  mstockLogin: (username: string, password: string) =>
    request<{ status: string; message: string | null }>('/api/brokers/mstock/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  mstockSessionOtp: (otp: string) =>
    request<void>('/api/brokers/mstock/session', {
      method: 'POST',
      body: JSON.stringify({ otp }),
    }),
  mstockSessionTotp: (totp: string) =>
    request<void>('/api/brokers/mstock/session/totp', {
      method: 'POST',
      body: JSON.stringify({ totp }),
    }),
  searchInstruments: (q: string, asset: string) =>
    request<InstrumentSearchHit[]>(
      `/api/instruments/search?q=${encodeURIComponent(q)}&asset=${encodeURIComponent(asset)}`,
    ),
  searchUnderlyings: (q: string, asset: string) =>
    request<UnderlyingHit[]>(
      `/api/instruments/underlyings?q=${encodeURIComponent(q)}&asset=${encodeURIComponent(asset)}`,
    ),
  benchmarks: () => request<UnderlyingHit[]>('/api/instruments/benchmarks'),
  tradableInstruments: () =>
    request<TradableInstrumentsResponse>('/api/instruments/tradable'),
  expiries: (underlyingKey: string) =>
    request<string[]>(
      `/api/instruments/expiries?underlyingKey=${encodeURIComponent(underlyingKey)}`,
    ),

  optionChain: (instrumentKey: string, expiryDate: string) =>
    request<OptionChainResponse>(
      `/api/option-chain?instrumentKey=${encodeURIComponent(instrumentKey)}&expiryDate=${encodeURIComponent(expiryDate)}`,
    ),
  ltp: (instrumentKeys: string[]) =>
    request<LtpResponse>('/api/quotes/ltp', {
      method: 'POST',
      body: JSON.stringify({ instrumentKeys }),
    }),
  greeks: (instrumentKey: string) =>
    request<GreeksResponse>(
      `/api/quotes/greeks?instrumentKey=${encodeURIComponent(instrumentKey)}`,
    ),
  positions: () => request<PositionsResponse>('/api/positions'),
  exitPositions: (instrumentKeys: string[]) =>
    request<PlaceMultiResult>('/api/positions/exit', {
      method: 'POST',
      body: JSON.stringify({ instrumentKeys }),
    }),
  placeOrder: (body: PlaceOrderBody) =>
    request<PlaceOrderResult>('/api/orders/place', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  placeMulti: (orders: PlaceOrderBody[]) =>
    request<PlaceMultiResult>('/api/orders/place-multi', {
      method: 'POST',
      body: JSON.stringify({ orders }),
    }),
  signal: (instrumentKey: string) =>
    request<SignalResponse>(
      `/api/indicators/signal?instrumentKey=${encodeURIComponent(instrumentKey)}`,
    ),
  suggestCreditSpread: (instrumentKey: string, debitOverride = false) =>
    request<SuggestResponse>('/api/strategies/suggest-credit-spread', {
      method: 'POST',
      body: JSON.stringify({ instrumentKey, debitOverride }),
    }),
  spreadHistory: (body: SpreadHistoryRequest) =>
    request<SpreadHistoryResponse>('/api/spread/history', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  algoStrategies: {
    list: () => request<AlgoStrategySummary[]>('/api/algo-strategies'),
    create: (body: {
      name: string
      executionMode?: AlgoExecutionMode | string
      config: AlgoStrategyConfig
    }) =>
      request<AlgoStrategySummary>('/api/algo-strategies', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    update: (
      id: string,
      body: {
        name: string
        executionMode?: AlgoExecutionMode | string
        config: AlgoStrategyConfig
      },
    ) =>
      request<AlgoStrategySummary>(`/api/algo-strategies/${id}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    delete: (id: string) =>
      request<void>(`/api/algo-strategies/${id}`, { method: 'DELETE' }),
    run: (id: string, executionMode: AlgoExecutionMode | string) =>
      request<AlgoStrategySummary>(`/api/algo-strategies/${id}/run`, {
        method: 'POST',
        body: JSON.stringify({ executionMode }),
      }),
    stop: (id: string) =>
      request<AlgoStrategySummary>(`/api/algo-strategies/${id}/stop`, {
        method: 'POST',
      }),
    lifecycle: (id: string) =>
      request<AlgoStrategyLifecycle>(`/api/algo-strategies/${id}/lifecycle`),
  },
}
