import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import {
  ApiError,
  api,
  type InstrumentSearchHit,
  type MeResponse,
  type OptionChainResponse,
  type OptionSide,
  type OptionStrikeRow,
  type UnderlyingHit,
} from '../api/client'
import { useMarketFeed } from '../hooks/useMarketFeed'
import type { LegKind, LegSide, StrategyLeg } from '../payoff/payoff'
import type { SpreadTemplateId } from '../payoff/templatePreviews'
import { OptionChainRow } from './OptionChainRow'
import { PayoffChart } from './PayoffChart'
import { ThemeToggle } from './ThemeToggle'

type Props = {
  me: MeResponse
  onLogout: () => void
  onTokenExpired: () => void
  hideChrome?: boolean
  seedUnderlyingKey?: string | null
}

const ASSETS = [
  { id: 'all', label: 'All' },
  { id: 'index', label: 'Indices' },
  { id: 'stock', label: 'Stocks' },
  { id: 'commodity', label: 'Commodities' },
] as const

const FALLBACK_BENCHMARKS: UnderlyingHit[] = [
  {
    instrumentKey: 'NSE:NIFTY50',
    tradingSymbol: 'NIFTY',
    name: 'Nifty 50',
    assetClass: 'index',
    lotSize: null,
  },
  {
    instrumentKey: 'NSE:NIFTYBANK',
    tradingSymbol: 'BANKNIFTY',
    name: 'Nifty Bank',
    assetClass: 'index',
    lotSize: null,
  },
  {
    instrumentKey: 'BSE:SENSEX',
    tradingSymbol: 'SENSEX',
    name: 'SENSEX',
    assetClass: 'index',
    lotSize: null,
  },
]

function roundPrice(value: number): number {
  return Math.round(value * 100) / 100
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

function formatPct(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

function normalizeIv(iv: number | null | undefined): number | null {
  if (iv == null) {
    return null
  }
  return iv > 1 ? iv / 100 : iv
}

function changePct(ltp: number | null | undefined, close: number | null | undefined): number | null {
  if (ltp == null || close == null || close === 0) {
    return null
  }
  return ((ltp - close) / close) * 100
}

function orderedStrikes(rows: OptionStrikeRow[]): number[] {
  return [...new Set(rows.map((row) => row.strike))].sort((a, b) => a - b)
}

function nearestStrike(strikes: number[], target: number): number | null {
  if (strikes.length === 0) {
    return null
  }
  return strikes.reduce((best, strike) =>
    Math.abs(strike - target) < Math.abs(best - target) ? strike : best,
  )
}

function strikeAtOffset(strikes: number[], atm: number, offsetSteps: number): number | null {
  const atmIndex = strikes.indexOf(atm)
  if (atmIndex < 0) {
    return null
  }
  const index = Math.min(strikes.length - 1, Math.max(0, atmIndex + offsetSteps))
  return strikes[index] ?? null
}

function resolveOptionSide(
  rows: OptionStrikeRow[],
  strike: number,
  kind: 'CE' | 'PE',
): OptionSide | null {
  const row = rows.find((item) => item.strike === strike)
  if (!row) {
    return null
  }
  return kind === 'CE' ? row.call : row.put
}

export function StrategyWorkspace({
  me,
  onLogout,
  onTokenExpired,
  hideChrome = false,
  seedUnderlyingKey = null,
}: Props) {
  const [asset, setAsset] = useState<string>('all')
  const [query, setQuery] = useState('')
  const [suggestions, setSuggestions] = useState<UnderlyingHit[]>([])
  const [openSuggestions, setOpenSuggestions] = useState(false)
  const [activeIndex, setActiveIndex] = useState(0)
  const [underlying, setUnderlying] = useState<UnderlyingHit | null>(null)
  const [expiries, setExpiries] = useState<string[]>([])
  const [selectedExpiry, setSelectedExpiry] = useState<string | null>(null)
  const [chain, setChain] = useState<OptionChainResponse | null>(null)
  const [chainLoading, setChainLoading] = useState(false)
  const [legs, setLegs] = useState<StrategyLeg[]>([])
  const [prices, setPrices] = useState<Record<string, number>>({})
  const [closes, setCloses] = useState<Record<string, number>>({})
  const [benchmarks, setBenchmarks] = useState<UnderlyingHit[]>([])
  const [error, setError] = useState<string | null>(null)

  const [contractQuery, setContractQuery] = useState('')
  const [contractHits, setContractHits] = useState<InstrumentSearchHit[]>([])
  const [openContracts, setOpenContracts] = useState(false)
  const [contractActive, setContractActive] = useState(0)

  const suggestionRefs = useRef<Array<HTMLDivElement | null>>([])
  const contractRefs = useRef<Array<HTMLDivElement | null>>([])
  const searchBoxRef = useRef<HTMLDivElement | null>(null)
  const searchInputRef = useRef<HTMLInputElement | null>(null)
  const contractBoxRef = useRef<HTMLDivElement | null>(null)
  const atmRowRef = useRef<HTMLTableRowElement | null>(null)
  const chainScrollRef = useRef<HTMLDivElement | null>(null)

  const hasChain =
    !!underlying && expiries.length > 0 && !!selectedExpiry && (chain?.strikes.length ?? 0) > 0
  const noChainAvailable =
    !!underlying &&
    !chainLoading &&
    (expiries.length === 0 ||
      (selectedExpiry != null && (chain?.strikes.length ?? 0) === 0 && !error?.includes('Failed')))

  const chainKeys = useMemo(() => {
    if (!chain) {
      return [] as string[]
    }
    const keys: string[] = []
    for (const row of chain.strikes) {
      if (row.call?.instrumentKey) {
        keys.push(row.call.instrumentKey)
      }
      if (row.put?.instrumentKey) {
        keys.push(row.put.instrumentKey)
      }
    }
    return keys
  }, [chain])

  const subscribeKeys = useMemo(() => {
    const keys = new Set<string>()
    for (const bench of benchmarks) {
      keys.add(bench.instrumentKey)
    }
    if (underlying?.instrumentKey) {
      keys.add(underlying.instrumentKey)
    }
    if (chain?.underlyingKey) {
      keys.add(chain.underlyingKey)
    }
    if (chain?.futuresKey) {
      keys.add(chain.futuresKey)
    }
    for (const leg of legs) {
      keys.add(leg.instrumentKey)
      if (leg.underlyingKey) {
        keys.add(leg.underlyingKey)
      }
    }
    for (const key of chainKeys) {
      keys.add(key)
    }
    return [...keys]
  }, [underlying, legs, chainKeys, chain?.futuresKey, chain?.underlyingKey, benchmarks])

  const spotKey =
    chain?.underlyingKey ??
    chain?.futuresKey ??
    underlying?.instrumentKey ??
    legs[0]?.underlyingKey ??
    null
  const spot =
    (spotKey ? prices[spotKey] : null) ??
    (chain?.futuresKey && prices[chain.futuresKey] != null ? prices[chain.futuresKey] : null) ??
    (underlying?.instrumentKey && prices[underlying.instrumentKey] != null
      ? prices[underlying.instrumentKey]
      : null) ??
    (chain?.underlyingSpot != null ? roundPrice(chain.underlyingSpot) : null)
  const futuresKey = chain?.futuresKey ?? chain?.underlyingKey ?? underlying?.instrumentKey ?? null
  const futuresLtp = futuresKey ? prices[futuresKey] ?? null : null

  const chartTitle =
    underlying?.tradingSymbol ??
    chain?.underlyingSymbol ??
    legs[0]?.underlyingSymbol ??
    legs[0]?.tradingSymbol ??
    null

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const list = await api.benchmarks()
        if (!cancelled) {
          setBenchmarks(list.length > 0 ? list : FALLBACK_BENCHMARKS)
        }
      } catch {
        if (!cancelled) {
          setBenchmarks(FALLBACK_BENCHMARKS)
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        let hit: UnderlyingHit | null = null
        const preferred = me.preferredUnderlying
        if (preferred?.instrumentKey) {
          const assetFilter = preferred.assetClass || 'index'
          const hits = await api.searchUnderlyings(
            preferred.tradingSymbol || 'NIFTY',
            assetFilter,
          )
          hit =
            hits.find((item) => item.instrumentKey === preferred.instrumentKey) ??
            hits[0] ??
            null
        }
        if (!hit) {
          try {
            const list = await api.benchmarks()
            hit = list.find((item) => item.tradingSymbol.toUpperCase() === 'NIFTY') ?? null
          } catch {
            hit = null
          }
        }
        if (!hit) {
          try {
            const hits = await api.searchUnderlyings('NIFTY', 'index')
            hit = hits[0] ?? null
          } catch {
            hit = null
          }
        }
        if (!hit) {
          hit = FALLBACK_BENCHMARKS[0]
        }
        if (!cancelled && hit) {
          await selectUnderlying(hit)
        }
      } catch (err) {
        if (cancelled) {
          return
        }
        if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
          onTokenExpired()
          return
        }
        if (!cancelled) {
          await selectUnderlying(FALLBACK_BENCHMARKS[0])
        }
      }
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    function onDocMouseDown(event: MouseEvent) {
      const target = event.target as Node
      if (searchBoxRef.current && !searchBoxRef.current.contains(target)) {
        setOpenSuggestions(false)
      }
      if (contractBoxRef.current && !contractBoxRef.current.contains(target)) {
        setOpenContracts(false)
      }
    }
    document.addEventListener('mousedown', onDocMouseDown)
    return () => document.removeEventListener('mousedown', onDocMouseDown)
  }, [])

  useEffect(() => {
    if (query.trim().length < 1) {
      setSuggestions([])
      setOpenSuggestions(false)
      setActiveIndex(0)
      return
    }
    const handle = window.setTimeout(async () => {
      try {
        const hits = await api.searchUnderlyings(query.trim(), asset)
        setSuggestions(hits)
        setActiveIndex(0)
        setError(null)
        // Only expand when the user is actively in the search box (avoid open-on-load).
        setOpenSuggestions(
          hits.length > 0 && document.activeElement === searchInputRef.current,
        )
      } catch (err) {
        if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
          onTokenExpired()
          return
        }
        setError(err instanceof Error ? err.message : 'Search failed')
      }
    }, 220)
    return () => window.clearTimeout(handle)
  }, [query, asset, onTokenExpired])

  useEffect(() => {
    suggestionRefs.current[activeIndex]?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex, suggestions])

  useEffect(() => {
    contractRefs.current[contractActive]?.scrollIntoView({ block: 'nearest' })
  }, [contractActive, contractHits])

  useEffect(() => {
    if (!underlying) {
      setExpiries([])
      setSelectedExpiry(null)
      setChain(null)
      setContractQuery('')
      setContractHits([])
      setOpenContracts(false)
      return
    }
    let cancelled = false
    ;(async () => {
      try {
        const list = await api.expiries(underlying.instrumentKey)
        if (cancelled) {
          return
        }
        setExpiries(list)
        setSelectedExpiry((current) =>
          current && list.includes(current) ? current : list[0] ?? null,
        )
        setError(null)
      } catch (err) {
        if (cancelled) {
          return
        }
        if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
          onTokenExpired()
          return
        }
        setExpiries([])
        setSelectedExpiry(null)
        setError(err instanceof Error ? err.message : 'Failed to load expiries')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [underlying, onTokenExpired])

  useEffect(() => {
    if (!underlying || !selectedExpiry) {
      setChain(null)
      return
    }
    let cancelled = false
    setChainLoading(true)
    ;(async () => {
      try {
        const response = await api.optionChain(underlying.instrumentKey, selectedExpiry)
        if (cancelled) {
          return
        }
        setChain(response)
        setPrices((prev) => {
          const next = { ...prev }
          const spotSeed = response.underlyingSpot != null ? roundPrice(response.underlyingSpot) : null
          if (spotSeed != null) {
            next[underlying.instrumentKey] = spotSeed
            if (response.underlyingKey) {
              next[response.underlyingKey] = spotSeed
            }
            if (response.futuresKey) {
              next[response.futuresKey] = next[response.futuresKey] ?? spotSeed
            }
          }
          for (const row of response.strikes) {
            if (row.call?.ltp != null) {
              next[row.call.instrumentKey] = roundPrice(row.call.ltp)
            }
            if (row.put?.ltp != null) {
              next[row.put.instrumentKey] = roundPrice(row.put.ltp)
            }
          }
          return next
        })
        setCloses((prev) => {
          const next = { ...prev }
          for (const row of response.strikes) {
            if (row.call?.closePrice != null) {
              next[row.call.instrumentKey] = roundPrice(row.call.closePrice)
            }
            if (row.put?.closePrice != null) {
              next[row.put.instrumentKey] = roundPrice(row.put.closePrice)
            }
          }
          return next
        })
        setError(null)
      } catch (err) {
        if (cancelled) {
          return
        }
        if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
          onTokenExpired()
          return
        }
        setChain(null)
        setError(err instanceof Error ? err.message : 'Failed to load option chain')
      } finally {
        if (!cancelled) {
          setChainLoading(false)
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [underlying, selectedExpiry, onTokenExpired])

  useEffect(() => {
    if (!noChainAvailable || !underlying || contractQuery.trim().length < 1) {
      if (contractQuery.trim().length < 1) {
        setContractHits([])
        setOpenContracts(false)
      }
      return
    }
    const handle = window.setTimeout(async () => {
      try {
        const hits = await api.searchInstruments(contractQuery.trim(), underlying.assetClass || asset)
        const filtered = hits.filter(
          (hit) =>
            hit.underlyingKey === underlying.instrumentKey ||
            hit.underlyingSymbol?.toUpperCase() === underlying.tradingSymbol.toUpperCase() ||
            hit.tradingSymbol.toUpperCase().includes(underlying.tradingSymbol.toUpperCase()),
        )
        const list = filtered.length > 0 ? filtered : hits
        setContractHits(list)
        setContractActive(0)
        setOpenContracts(list.length > 0)
      } catch (err) {
        if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
          onTokenExpired()
        }
      }
    }, 220)
    return () => window.clearTimeout(handle)
  }, [contractQuery, noChainAvailable, underlying, asset, onTokenExpired])

  useMarketFeed({
    instrumentKeys: subscribeKeys,
    enabled: subscribeKeys.length > 0,
    batchMs: 60,
    onBatch: (ticks) => {
      setPrices((prev) => {
        let changed = false
        const next = { ...prev }
        for (const tick of ticks) {
          const ltp = roundPrice(tick.ltp)
          if (next[tick.instrumentKey] !== ltp) {
            next[tick.instrumentKey] = ltp
            changed = true
          }
        }
        return changed ? next : prev
      })
      setCloses((prev) => {
        let changed = false
        const next = { ...prev }
        for (const tick of ticks) {
          if (tick.close != null && !Number.isNaN(tick.close)) {
            const close = roundPrice(tick.close)
            if (next[tick.instrumentKey] !== close) {
              next[tick.instrumentKey] = close
              changed = true
            }
          }
        }
        return changed ? next : prev
      })
    },
    onAuthError: onTokenExpired,
  })

  const visibleStrikes = useMemo(() => {
    if (!chain) {
      return []
    }
    const rows = chain.strikes
    if (spot == null || rows.length <= 50) {
      return rows
    }
    let closest = 0
    let best = Number.POSITIVE_INFINITY
    rows.forEach((row, index) => {
      const distance = Math.abs(row.strike - spot)
      if (distance < best) {
        best = distance
        closest = index
      }
    })
    const start = Math.max(0, closest - 25)
    const end = Math.min(rows.length, start + 50)
    return rows.slice(start, end)
  }, [chain, spot])

  const maxOi = useMemo(() => {
    let max = 0
    for (const row of visibleStrikes) {
      if (row.call?.oi && row.call.oi > max) {
        max = row.call.oi
      }
      if (row.put?.oi && row.put.oi > max) {
        max = row.put.oi
      }
    }
    return max
  }, [visibleStrikes])

  const atmStrike = useMemo(() => {
    if (spot == null || visibleStrikes.length === 0) {
      return null
    }
    return visibleStrikes.reduce(
      (best, row) => (Math.abs(row.strike - spot) < Math.abs(best - spot) ? row.strike : best),
      visibleStrikes[0].strike,
    )
  }, [visibleStrikes, spot])

  const jumpToAtm = useCallback(() => {
    atmRowRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [])

  useEffect(() => {
    if (!atmStrike || !atmRowRef.current) {
      return
    }
    atmRowRef.current.scrollIntoView({ block: 'center', behavior: 'instant' as ScrollBehavior })
  }, [atmStrike, selectedExpiry, chain?.expiryDate])

  async function selectUnderlying(hit: UnderlyingHit) {
    setUnderlying(hit)
    setQuery(hit.tradingSymbol)
    setSuggestions([])
    setOpenSuggestions(false)
    setActiveIndex(0)
    setError(null)
    setLegs([])
    setContractQuery('')
    setContractHits([])
    setOpenContracts(false)
    void api
      .saveSettings(hit.instrumentKey, hit.tradingSymbol, hit.assetClass)
      .catch(() => undefined)
  }

  async function fetchLegQuotes(
    instrumentKey: string,
    kind: LegKind,
    entryHint: number,
    ivHint: number | null,
  ): Promise<{
    entry: number
    iv: number | null
    delta: number | null
    theta: number | null
    gamma: number | null
    vega: number | null
  }> {
    let entry = roundPrice(entryHint || prices[instrumentKey] || 0)
    let iv = ivHint
    let delta: number | null = null
    let theta: number | null = null
    let gamma: number | null = null
    let vega: number | null = null

    // Entry comes from live-price WS channel (prices map) or chain hint — never REST LTP.

    if (kind === 'CE' || kind === 'PE') {
      try {
        const greeks = await api.greeks(instrumentKey)
        iv = normalizeIv(greeks.iv) ?? iv
        delta = greeks.delta
        theta = greeks.theta
        gamma = greeks.gamma
        vega = greeks.vega
      } catch (err) {
        if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
          onTokenExpired()
        }
      }
    }

    return { entry, iv, delta, theta, gamma, vega }
  }

  const applyLegAction = useCallback(
    async (params: {
      instrumentKey: string
      tradingSymbol: string
      kind: LegKind
      strike: number | null
      expiry: string | null
      lotSize: number
      iv: number | null
      delta?: number | null
      theta?: number | null
      gamma?: number | null
      vega?: number | null
      entryHint: number
      legSide: LegSide
      forceNew?: boolean
    }) => {
      if (!underlying) {
        return
      }
      if (legs.length > 0) {
        const existingUnderlying = legs[0].underlyingKey
        if (existingUnderlying && existingUnderlying !== underlying.instrumentKey) {
          setError('Keep legs on the same underlying for one payoff chart.')
          return
        }
      }

      if (!params.forceNew) {
        const existing = legs.find((leg) => leg.instrumentKey === params.instrumentKey)
        if (existing) {
          if (existing.side === params.legSide) {
            setLegs((prev) =>
              prev.map((leg) =>
                leg.id === existing.id ? { ...leg, lots: leg.lots + 1 } : leg,
              ),
            )
            setError(null)
            return
          }
          if (existing.lots <= 1) {
            setLegs((prev) => prev.filter((leg) => leg.id !== existing.id))
          } else {
            setLegs((prev) =>
              prev.map((leg) =>
                leg.id === existing.id ? { ...leg, lots: leg.lots - 1 } : leg,
              ),
            )
          }
          setError(null)
          return
        }
      }

      const quotes = await fetchLegQuotes(
        params.instrumentKey,
        params.kind,
        params.entryHint,
        params.iv,
      )

      const leg: StrategyLeg = {
        id: crypto.randomUUID(),
        instrumentKey: params.instrumentKey,
        tradingSymbol: params.tradingSymbol,
        kind: params.kind,
        side: params.legSide,
        lots: 1,
        lotSize: params.lotSize,
        entryPrice: quotes.entry,
        strike: params.strike,
        expiry: params.expiry,
        underlyingKey: underlying.instrumentKey,
        underlyingSymbol: underlying.tradingSymbol,
        assetClass: underlying.assetClass,
        iv: quotes.iv,
        delta: params.delta ?? quotes.delta,
        theta: params.theta ?? quotes.theta,
        gamma: params.gamma ?? quotes.gamma,
        vega: params.vega ?? quotes.vega,
      }
      setLegs((prev) => [...prev, leg])
      setError(null)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [underlying, legs, prices],
  )

  async function loadChainForExpiry(expiry: string): Promise<OptionChainResponse | null> {
    if (!underlying) {
      return null
    }
    if (chain && chain.expiryDate === expiry) {
      return chain
    }
    try {
      return await api.optionChain(underlying.instrumentKey, expiry)
    } catch (err) {
      if (err instanceof ApiError && err.message.includes('broker_token_expired')) {
        onTokenExpired()
      } else {
        setError(err instanceof Error ? err.message : 'Failed to load option chain')
      }
      return null
    }
  }

  async function remapLegContract(
    leg: StrategyLeg,
    nextStrike: number | null,
    nextExpiry: string | null,
    nextKind: LegKind = leg.kind,
  ) {
    if (!underlying) {
      return
    }
    if (nextKind === 'FUT') {
      setError('Change futures legs via contract search.')
      return
    }
    if (nextKind !== 'CE' && nextKind !== 'PE') {
      return
    }
    const expiry = nextExpiry ?? leg.expiry ?? selectedExpiry
    if (!expiry || nextStrike == null) {
      return
    }
    const nextChain = await loadChainForExpiry(expiry)
    if (!nextChain) {
      return
    }
    const strikes = orderedStrikes(nextChain.strikes)
    const strike = nearestStrike(strikes, nextStrike)
    if (strike == null) {
      setError('No strikes available for that expiry.')
      return
    }
    const side = resolveOptionSide(nextChain.strikes, strike, nextKind)
    if (!side) {
      setError(`No ${nextKind} contract at ${strike}.`)
      return
    }
    const quotes = await fetchLegQuotes(
      side.instrumentKey,
      nextKind,
      prices[side.instrumentKey] ?? side.ltp ?? 0,
      normalizeIv(side.iv),
    )
    updateLeg(leg.id, {
      instrumentKey: side.instrumentKey,
      tradingSymbol:
        side.tradingSymbol || `${underlying.tradingSymbol} ${strike} ${nextKind}`,
      kind: nextKind,
      strike,
      expiry,
      lotSize: side.lotSize ?? nextChain.lotSize ?? leg.lotSize,
      entryPrice: quotes.entry || leg.entryPrice,
      iv: quotes.iv,
      delta: side.delta ?? quotes.delta,
      theta: side.theta ?? quotes.theta,
      gamma: side.gamma ?? quotes.gamma,
      vega: side.vega ?? quotes.vega,
    })
    setError(null)
  }

  async function changeLegStrike(leg: StrategyLeg, direction: -1 | 1) {
    if (leg.kind !== 'CE' && leg.kind !== 'PE') {
      return
    }
    const expiry = leg.expiry ?? selectedExpiry
    if (!expiry || leg.strike == null) {
      return
    }
    const nextChain = await loadChainForExpiry(expiry)
    if (!nextChain) {
      return
    }
    const strikes = orderedStrikes(nextChain.strikes)
    const index = strikes.indexOf(leg.strike)
    const fallbackIndex =
      index >= 0 ? index : strikes.findIndex((value) => value >= leg.strike!)
    const currentIndex = fallbackIndex >= 0 ? fallbackIndex : nearestStrike(strikes, leg.strike)
      ? strikes.indexOf(nearestStrike(strikes, leg.strike)!)
      : -1
    if (currentIndex < 0) {
      return
    }
    const nextIndex = currentIndex + direction
    if (nextIndex < 0 || nextIndex >= strikes.length) {
      return
    }
    await remapLegContract(leg, strikes[nextIndex], expiry, leg.kind)
  }

  async function changeLegExpiry(leg: StrategyLeg, nextExpiry: string) {
    if (leg.expiry === nextExpiry) {
      return
    }
    await remapLegContract(leg, leg.strike, nextExpiry, leg.kind)
  }

  const applySpreadTemplate = useCallback(
    async (templateId: SpreadTemplateId) => {
      if (!underlying || !selectedExpiry || !chain || chain.strikes.length === 0) {
        setError('Load an option chain before applying a template.')
        return
      }
      const spotForAtm =
        spot ??
        (chain.underlyingSpot != null ? roundPrice(chain.underlyingSpot) : null)
      const strikes = orderedStrikes(chain.strikes)
      if (strikes.length === 0 || spotForAtm == null) {
        setError('Need spot and strikes to build a template.')
        return
      }
      const atm = nearestStrike(strikes, spotForAtm)
      if (atm == null) {
        return
      }

      type Spec = { kind: 'CE' | 'PE'; offset: number; side: LegSide }
      const specs: Spec[] =
        templateId === 'bull_call'
          ? [
              { kind: 'CE', offset: 0, side: 'BUY' },
              { kind: 'CE', offset: 2, side: 'SELL' },
            ]
          : templateId === 'bull_put'
            ? [
                { kind: 'PE', offset: -2, side: 'BUY' },
                { kind: 'PE', offset: 0, side: 'SELL' },
              ]
            : templateId === 'bear_call'
              ? [
                  { kind: 'CE', offset: 0, side: 'SELL' },
                  { kind: 'CE', offset: 2, side: 'BUY' },
                ]
              : templateId === 'bear_put'
                ? [
                    { kind: 'PE', offset: -2, side: 'SELL' },
                    { kind: 'PE', offset: 0, side: 'BUY' },
                  ]
                : templateId === 'long_straddle'
                  ? [
                      { kind: 'CE', offset: 0, side: 'BUY' },
                      { kind: 'PE', offset: 0, side: 'BUY' },
                    ]
                  : [
                      { kind: 'CE', offset: 0, side: 'SELL' },
                      { kind: 'PE', offset: 0, side: 'SELL' },
                    ]

      const created: StrategyLeg[] = []
      for (const spec of specs) {
        const strike = strikeAtOffset(strikes, atm, spec.offset)
        if (strike == null) {
          continue
        }
        const side = resolveOptionSide(chain.strikes, strike, spec.kind)
        if (!side) {
          continue
        }
        const quotes = await fetchLegQuotes(
          side.instrumentKey,
          spec.kind,
          prices[side.instrumentKey] ?? side.ltp ?? 0,
          normalizeIv(side.iv),
        )
        created.push({
          id: crypto.randomUUID(),
          instrumentKey: side.instrumentKey,
          tradingSymbol:
            side.tradingSymbol || `${underlying.tradingSymbol} ${strike} ${spec.kind}`,
          kind: spec.kind,
          side: spec.side,
          lots: 1,
          lotSize: side.lotSize ?? chain.lotSize ?? underlying.lotSize ?? 1,
          entryPrice: quotes.entry,
          strike,
          expiry: selectedExpiry,
          underlyingKey: underlying.instrumentKey,
          underlyingSymbol: underlying.tradingSymbol,
          assetClass: underlying.assetClass,
          iv: quotes.iv,
          delta: side.delta ?? quotes.delta,
          theta: side.theta ?? quotes.theta,
          gamma: side.gamma ?? quotes.gamma,
          vega: side.vega ?? quotes.vega,
        })
      }
      if (created.length === 0) {
        setError('Could not resolve template contracts on this chain.')
        return
      }
      setLegs(created)
      setError(null)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [underlying, selectedExpiry, chain, spot, prices],
  )

  const addFromSide = useCallback(
    async (side: OptionSide, kind: 'CE' | 'PE', strike: number, legSide: LegSide) => {
      if (!underlying) {
        return
      }
      await applyLegAction({
        instrumentKey: side.instrumentKey,
        tradingSymbol: side.tradingSymbol || `${underlying.tradingSymbol} ${strike} ${kind}`,
        kind,
        strike,
        expiry: selectedExpiry,
        lotSize: side.lotSize ?? chain?.lotSize ?? underlying.lotSize ?? 1,
        iv: normalizeIv(side.iv),
        delta: side.delta,
        theta: side.theta,
        gamma: side.gamma,
        vega: side.vega,
        entryHint: prices[side.instrumentKey] ?? side.ltp ?? 0,
        legSide,
      })
    },
    [underlying, applyLegAction, selectedExpiry, chain?.lotSize, prices],
  )

  const handleBuyCall = useCallback(
    (side: OptionSide, strike: number) => void addFromSide(side, 'CE', strike, 'BUY'),
    [addFromSide],
  )
  const handleSellCall = useCallback(
    (side: OptionSide, strike: number) => void addFromSide(side, 'CE', strike, 'SELL'),
    [addFromSide],
  )
  const handleBuyPut = useCallback(
    (side: OptionSide, strike: number) => void addFromSide(side, 'PE', strike, 'BUY'),
    [addFromSide],
  )
  const handleSellPut = useCallback(
    (side: OptionSide, strike: number) => void addFromSide(side, 'PE', strike, 'SELL'),
    [addFromSide],
  )

  async function addFromContract(hit: InstrumentSearchHit, legSide: LegSide) {
    if (!underlying) {
      return
    }
    const kind = (hit.kind === 'CE' || hit.kind === 'PE' || hit.kind === 'FUT' ? hit.kind : 'FUT') as LegKind
    await applyLegAction({
      instrumentKey: hit.instrumentKey,
      tradingSymbol: hit.tradingSymbol,
      kind,
      strike: hit.strike,
      expiry: hit.expiry,
      lotSize: hit.lotSize ?? underlying.lotSize ?? 1,
      iv: null,
      entryHint: prices[hit.instrumentKey] ?? 0,
      legSide,
    })
    setOpenContracts(false)
  }

  function updateLeg(id: string, patch: Partial<StrategyLeg>) {
    setLegs((prev) => prev.map((leg) => (leg.id === id ? { ...leg, ...patch } : leg)))
  }

  function onSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (!openSuggestions || suggestions.length === 0) {
      if (event.key === 'Escape') {
        setOpenSuggestions(false)
      }
      return
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setActiveIndex((index) => (index + 1) % suggestions.length)
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActiveIndex((index) => (index - 1 + suggestions.length) % suggestions.length)
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      const hit = suggestions[activeIndex]
      if (hit) {
        void selectUnderlying(hit)
      }
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      setOpenSuggestions(false)
    }
  }

  function onContractKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (!openContracts || contractHits.length === 0) {
      if (event.key === 'Escape') {
        setOpenContracts(false)
      }
      return
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setContractActive((index) => (index + 1) % contractHits.length)
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      setContractActive((index) => (index - 1 + contractHits.length) % contractHits.length)
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      const hit = contractHits[contractActive]
      if (hit) {
        void addFromContract(hit, 'BUY')
      }
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      setOpenContracts(false)
    }
  }

  useEffect(() => {
    if (!seedUnderlyingKey) {
      return
    }
    let cancelled = false
    ;(async () => {
      try {
        const hits = await api.searchUnderlyings(seedUnderlyingKey, 'index')
        const match =
          hits.find((h) => h.instrumentKey === seedUnderlyingKey) ??
          hits.find((h) => h.tradingSymbol.toUpperCase() === seedUnderlyingKey.toUpperCase()) ??
          hits[0]
        if (!cancelled && match) {
          setQuery(match.tradingSymbol)
          setUnderlying(match)
        }
      } catch {
        // ignore seed failure
      }
    })()
    return () => {
      cancelled = true
    }
  }, [seedUnderlyingKey])

  return (
    <div
      className={`flex w-full flex-col overflow-hidden bg-[#F6F8FB] dark:bg-[#070A0F] ${
        hideChrome ? 'h-full min-h-0 flex-1' : 'h-screen'
      }`}
    >
      {!hideChrome && (
        <header className="shrink-0 border-b border-slate-200/80 bg-white/95 backdrop-blur-md dark:border-slate-800/80 dark:bg-[#0D1119]/95">
          <div className="flex w-full items-center justify-between gap-4 px-3 py-2 lg:px-4">
            <div className="min-w-0">
              <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                SG Algo Desk
              </p>
              <h1 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                Strategy Builder
              </h1>
            </div>
            <div className="flex flex-wrap items-center justify-end gap-2 sm:gap-3">
              <div className="flex flex-wrap items-center gap-2 text-xs">
                {benchmarks.map((bench) => {
                  const ltp = prices[bench.instrumentKey]
                  const close = closes[bench.instrumentKey]
                  const pct = changePct(ltp, close)
                  return (
                    <div
                      key={bench.instrumentKey}
                      className="rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1 dark:border-slate-800 dark:bg-[#131924]"
                    >
                      <span className="font-semibold text-slate-700 dark:text-slate-200">
                        {bench.tradingSymbol}
                      </span>{' '}
                      <span className="font-num text-slate-900 dark:text-slate-100">
                        {formatPrice(ltp)}
                      </span>
                      <span
                        className={`font-num ml-1 ${
                          pct == null
                            ? 'text-slate-400'
                            : pct >= 0
                              ? 'text-emerald-600 dark:text-emerald-400'
                              : 'text-rose-600 dark:text-rose-400'
                        }`}
                      >
                        ({formatPct(pct)})
                      </span>
                    </div>
                  )
                })}
              </div>
              <ThemeToggle />
              <div className="rounded-lg bg-blue-50 px-2.5 py-1 text-xs font-medium text-blue-700 dark:bg-blue-950/40 dark:text-blue-300">
                {me.userName}
              </div>
              <button
                type="button"
                onClick={onLogout}
                className="rounded-lg px-2.5 py-1.5 text-xs text-slate-500 transition hover:bg-slate-100 hover:text-slate-900 dark:hover:bg-slate-800 dark:hover:text-slate-100"
              >
                Log out
              </button>
            </div>
          </div>
        </header>
      )}

      {/* Compact Control & Metrics Toolbar */}
      <div className="flex w-full shrink-0 flex-wrap items-center justify-between gap-2 border-b border-slate-200/80 bg-white px-3 py-2 dark:border-slate-800/80 dark:bg-[#0D1119] lg:px-4">
        <div className="flex min-w-0 flex-wrap items-center gap-2.5">
          <div className="flex rounded-xl bg-slate-100 p-0.5 dark:bg-[#070A0F]">
            {ASSETS.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => setAsset(item.id)}
                className={`rounded-lg px-2.5 py-1 text-xs font-medium transition ${
                  asset === item.id
                    ? 'bg-white text-slate-900 shadow-sm dark:bg-[#131924] dark:text-slate-100'
                    : 'text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200'
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>

          <div className="relative" ref={searchBoxRef}>
            <input
              ref={searchInputRef}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onFocus={() => setOpenSuggestions(suggestions.length > 0)}
              onKeyDown={onSearchKeyDown}
              placeholder="Search underlying…"
              role="combobox"
              aria-expanded={openSuggestions}
              aria-controls="underlying-suggestions"
              aria-activedescendant={
                openSuggestions && suggestions[activeIndex]
                  ? `underlying-option-${suggestions[activeIndex].instrumentKey}`
                  : undefined
              }
              className="h-8 w-44 rounded-lg border border-slate-200 bg-slate-50/70 px-3 text-xs font-medium text-slate-900 outline-none transition focus:border-blue-500 focus:bg-white focus:ring-2 focus:ring-blue-500/20 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100 dark:focus:bg-[#0B0F17]"
            />
            {openSuggestions && suggestions.length > 0 && (
              <div
                id="underlying-suggestions"
                role="listbox"
                className="desk-scroll absolute z-30 mt-1.5 max-h-72 w-72 overflow-auto rounded-xl border border-slate-200 bg-white shadow-xl dark:border-slate-800 dark:bg-[#0D1119]"
              >
                {suggestions.map((hit, index) => {
                  const active = index === activeIndex
                  return (
                    <div
                      key={hit.instrumentKey}
                      id={`underlying-option-${hit.instrumentKey}`}
                      role="option"
                      aria-selected={active}
                      ref={(node) => {
                        suggestionRefs.current[index] = node
                      }}
                      onMouseEnter={() => setActiveIndex(index)}
                      onMouseDown={(event) => {
                        event.preventDefault()
                        void selectUnderlying(hit)
                      }}
                      className={`cursor-pointer border-b border-slate-100 px-3 py-2 last:border-b-0 dark:border-slate-800/70 ${
                        active
                          ? 'bg-blue-50 dark:bg-blue-950/40'
                          : 'bg-white hover:bg-slate-50 dark:bg-[#0D1119] dark:hover:bg-slate-800/40'
                      }`}
                    >
                      <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                        {hit.tradingSymbol}
                      </div>
                      <div className="text-[11px] text-slate-500 dark:text-slate-400">
                        {hit.name} · {hit.assetClass}
                        {hit.lotSize ? ` · lot ${hit.lotSize}` : ''}
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
          {error && <p className="text-xs font-medium text-rose-600 dark:text-rose-400">{error}</p>}
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          <Chip label="Spot" value={formatPrice(spot)} />
          <Chip label="Max pain" value={formatPrice(chain?.maxPain)} />
          <Chip label="PCR" value={chain?.pcr == null ? '—' : chain.pcr.toFixed(3)} />
          <Chip
            label="Futures"
            value={formatPrice(futuresLtp)}
            hint={chain?.futuresSymbol ?? undefined}
          />
          <Chip
            label="Lot"
            value={String(chain?.lotSize ?? underlying?.lotSize ?? '—')}
          />
        </div>
      </div>

      <main className="grid min-h-0 flex-1 grid-cols-1 gap-3 p-3 lg:grid-cols-12">
        {/* Option Chain Column */}
        <section className="flex min-h-0 flex-col lg:col-span-4">
          <div className="flex min-h-0 flex-1 flex-col rounded-xl border border-slate-200/90 bg-white shadow-sm dark:border-slate-800/90 dark:bg-[#0D1119]">
            <div className="flex shrink-0 items-center justify-between gap-2 border-b border-slate-200/80 px-3 py-2 dark:border-slate-800/80">
              <div className="flex items-center gap-2">
                <h2 className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                  {underlying ? `Option Chain · ${underlying.tradingSymbol}` : 'Option Chain'}
                </h2>
                {chainLoading && (
                  <span className="inline-flex items-center gap-1 text-[10px] font-medium text-blue-600 dark:text-blue-400">
                    <span className="h-1.5 w-1.5 animate-ping rounded-full bg-blue-500" />
                    Loading
                  </span>
                )}
              </div>
              {hasChain && atmStrike != null && (
                <button
                  type="button"
                  onClick={jumpToAtm}
                  className="rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 text-[10px] font-semibold text-slate-600 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-[#131924] dark:text-slate-300 dark:hover:bg-slate-800"
                >
                  ATM
                </button>
              )}
            </div>

            {underlying && expiries.length > 0 && (
              <div className="no-scrollbar flex shrink-0 items-center gap-1 overflow-x-auto whitespace-nowrap border-b border-slate-200/80 px-2.5 py-1.5 dark:border-slate-800/80">
                {expiries.map((expiry) => {
                  const selected = expiry === selectedExpiry
                  return (
                    <button
                      key={expiry}
                      type="button"
                      onClick={() => setSelectedExpiry(expiry)}
                      className={`shrink-0 rounded-md px-2 py-0.5 text-[10px] font-semibold transition ${
                        selected
                          ? 'bg-blue-600 text-white shadow-sm'
                          : 'bg-slate-100 text-slate-600 hover:bg-slate-200 hover:text-slate-900 dark:bg-[#131924] dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-200'
                      }`}
                    >
                      {expiry}
                    </button>
                  )
                })}
              </div>
            )}

            {!underlying ? (
              <div className="flex flex-1 items-center justify-center p-6 text-xs text-slate-500 dark:text-slate-400">
                Search an underlying to load the option chain.
              </div>
            ) : chainLoading && !hasChain ? (
              <div className="flex-1 space-y-2 p-3">
                {Array.from({ length: 12 }).map((_, i) => (
                  <div
                    key={i}
                    className="h-7 animate-pulse rounded-md bg-slate-100 dark:bg-slate-800/60"
                  />
                ))}
              </div>
            ) : hasChain ? (
              <div ref={chainScrollRef} className="desk-scroll min-h-0 flex-1 overflow-y-auto">
                <table className="min-w-full border-collapse text-[10px]">
                  <thead className="sticky top-0 z-20 border-b border-slate-200 bg-slate-50/95 text-[9px] font-semibold uppercase tracking-wider text-slate-500 backdrop-blur dark:border-slate-800 dark:bg-[#131924]/95 dark:text-slate-400">
                    <tr>
                      <th className="px-2 py-1.5 text-left">CE OI</th>
                      <th className="px-1.5 py-1.5 text-left">CE LTP</th>
                      <th className="px-2 py-1.5 text-center">Strike</th>
                      <th className="px-1.5 py-1.5 text-right">PE LTP</th>
                      <th className="px-2 py-1.5 text-right">PE OI</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visibleStrikes.map((row) => {
                      const isAtm = atmStrike != null && row.strike === atmStrike
                      const callItm = spot != null && row.strike < spot
                      const putItm = spot != null && row.strike > spot
                      const callLive = row.call ? prices[row.call.instrumentKey] : undefined
                      const putLive = row.put ? prices[row.put.instrumentKey] : undefined
                      const callClose = row.call
                        ? closes[row.call.instrumentKey] ?? row.call.closePrice
                        : null
                      const putClose = row.put
                        ? closes[row.put.instrumentKey] ?? row.put.closePrice
                        : null
                      const callChange =
                        callLive != null && callClose != null
                          ? changePct(callLive, callClose)
                          : row.call?.ltpChangePct
                      const putChange =
                        putLive != null && putClose != null
                          ? changePct(putLive, putClose)
                          : row.put?.ltpChangePct

                      return (
                        <OptionChainRow
                          key={row.strike}
                          row={row}
                          isAtm={isAtm}
                          spot={spot}
                          callItm={callItm}
                          putItm={putItm}
                          callLive={callLive}
                          putLive={putLive}
                          callChange={callChange}
                          putChange={putChange}
                          maxOi={maxOi}
                          atmRef={isAtm ? atmRowRef : undefined}
                          onBuyCall={handleBuyCall}
                          onSellCall={handleSellCall}
                          onBuyPut={handleBuyPut}
                          onSellPut={handleSellPut}
                        />
                      )
                    })}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-4">
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  {chainLoading
                    ? 'Loading option chain…'
                    : `No option chain for ${underlying.tradingSymbol}. Search and add a FUT/CE/PE contract instead.`}
                </p>
                {!chainLoading && (
                  <div className="relative" ref={contractBoxRef}>
                    <label className="block text-xs">
                      <span className="mb-1.5 block font-medium text-slate-600 dark:text-slate-300">
                        Add instrument
                      </span>
                      <input
                        value={contractQuery}
                        onChange={(e) => setContractQuery(e.target.value)}
                        onFocus={() => setOpenContracts(contractHits.length > 0)}
                        onKeyDown={onContractKeyDown}
                        placeholder={`${underlying.tradingSymbol} futures or options…`}
                        className="h-9 w-full max-w-md rounded-lg border border-slate-200 bg-slate-50/70 px-3 text-xs outline-none focus:border-blue-500 focus:bg-white focus:ring-2 focus:ring-blue-500/20 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100"
                      />
                    </label>
                    {openContracts && contractHits.length > 0 && (
                      <div className="desk-scroll absolute z-20 mt-1.5 max-h-72 w-full max-w-md overflow-auto rounded-xl border border-slate-200 bg-white shadow-xl dark:border-slate-800 dark:bg-[#0D1119]">
                        {contractHits.map((hit, index) => {
                          const active = index === contractActive
                          return (
                            <div
                              key={hit.instrumentKey}
                              ref={(node) => {
                                contractRefs.current[index] = node
                              }}
                              onMouseEnter={() => setContractActive(index)}
                              className={`flex items-center justify-between gap-2 border-b border-slate-100 px-3 py-2 last:border-b-0 dark:border-slate-800/70 ${
                                active
                                  ? 'bg-blue-50 dark:bg-blue-950/40'
                                  : 'bg-white hover:bg-slate-50 dark:bg-[#0D1119] dark:hover:bg-slate-800/40'
                              }`}
                            >
                              <div>
                                <div className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                                  {hit.tradingSymbol}
                                </div>
                                <div className="text-[11px] text-slate-500 dark:text-slate-400">
                                  {hit.kind}
                                  {hit.expiry ? ` · ${hit.expiry}` : ''}
                                  {hit.lotSize ? ` · lot ${hit.lotSize}` : ''}
                                </div>
                              </div>
                              <div className="flex gap-1">
                                <button
                                  type="button"
                                  onMouseDown={(event) => {
                                    event.preventDefault()
                                    void addFromContract(hit, 'BUY')
                                  }}
                                  className="rounded border border-emerald-500/30 bg-emerald-500/10 px-2 py-1 text-[10px] font-bold text-emerald-600 hover:bg-emerald-600 hover:text-white dark:text-emerald-400"
                                >
                                  B
                                </button>
                                <button
                                  type="button"
                                  onMouseDown={(event) => {
                                    event.preventDefault()
                                    void addFromContract(hit, 'SELL')
                                  }}
                                  className="rounded border border-rose-500/30 bg-rose-500/10 px-2 py-1 text-[10px] font-bold text-rose-600 hover:bg-rose-600 hover:text-white dark:text-rose-400"
                                >
                                  S
                                </button>
                              </div>
                            </div>
                          )
                        })}
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        </section>

        {/* Payoff & Active Legs Column */}
        <section className="flex min-h-0 flex-col gap-3 lg:col-span-8">
          <PayoffChart
            legs={legs}
            spot={spot}
            title={chartTitle ? `${chartTitle} Payoff` : null}
            templatesReady={hasChain}
            onApplyTemplate={applySpreadTemplate}
          />

          <div className="flex max-h-[38%] min-h-0 shrink-0 flex-col rounded-xl border border-slate-200/90 bg-white shadow-sm dark:border-slate-800/90 dark:bg-[#0D1119]">
            <div className="flex shrink-0 items-center justify-between border-b border-slate-200/80 px-3 py-2 dark:border-slate-800/80">
              <div className="flex items-center gap-2">
                <h3 className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                  Strategy Legs
                </h3>
                {legs.length > 0 && (
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 font-num text-[10px] font-semibold text-slate-600 dark:bg-[#131924] dark:text-slate-300">
                    {legs.length}
                  </span>
                )}
              </div>
              {legs.length > 0 && (
                <button
                  type="button"
                  onClick={() => setLegs([])}
                  className="rounded-md px-2 py-0.5 text-[11px] font-medium text-rose-600 transition hover:bg-rose-50 dark:text-rose-400 dark:hover:bg-rose-950/30"
                >
                  Clear all
                </button>
              )}
            </div>
            {legs.length === 0 ? (
              <p className="px-3 py-3 text-xs text-slate-500 dark:text-slate-400">
                Select a spread template above or click <span className="font-semibold">B / S</span> on any strike to add legs.
              </p>
            ) : (
              <div className="desk-scroll min-h-0 flex-1 overflow-y-auto">
                {legs.map((leg) => {
                  const canStepStrike = leg.kind === 'CE' || leg.kind === 'PE'
                  return (
                    <div
                      key={leg.id}
                      className="flex flex-wrap items-center gap-2 border-b border-slate-100 px-3 py-2 text-xs last:border-b-0 dark:border-slate-800/70"
                    >
                      <button
                        type="button"
                        title="Toggle BUY / SELL"
                        onClick={() =>
                          updateLeg(leg.id, { side: leg.side === 'BUY' ? 'SELL' : 'BUY' })
                        }
                        className={`shrink-0 rounded-md px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide transition ${
                          leg.side === 'BUY'
                            ? 'bg-emerald-500/15 text-emerald-700 hover:bg-emerald-500/25 dark:text-emerald-400'
                            : 'bg-rose-500/15 text-rose-700 hover:bg-rose-500/25 dark:text-rose-400'
                        }`}
                      >
                        {leg.side}
                      </button>
                      <span className="shrink-0 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700 dark:bg-[#131924] dark:text-slate-300">
                        {leg.kind}
                      </span>
                      {canStepStrike ? (
                        <div className="inline-flex items-center gap-0.5">
                          <button
                            type="button"
                            title="Lower strike"
                            onClick={() => void changeLegStrike(leg, -1)}
                            className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-slate-200 text-slate-600 hover:bg-slate-50 dark:border-slate-800 dark:text-slate-300 dark:hover:bg-slate-800"
                          >
                            −
                          </button>
                          <span className="min-w-[3.75rem] text-center font-num text-xs font-semibold text-slate-900 dark:text-slate-100">
                            {formatPrice(leg.strike, 0)}
                          </span>
                          <button
                            type="button"
                            title="Higher strike"
                            onClick={() => void changeLegStrike(leg, 1)}
                            className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-slate-200 text-slate-600 hover:bg-slate-50 dark:border-slate-800 dark:text-slate-300 dark:hover:bg-slate-800"
                          >
                            +
                          </button>
                        </div>
                      ) : (
                        <span className="truncate font-medium text-slate-900 dark:text-slate-100">
                          {leg.tradingSymbol}
                        </span>
                      )}
                      <select
                        title="Expiry"
                        value={leg.expiry ?? ''}
                        disabled={expiries.length === 0 || !canStepStrike}
                        onChange={(e) => {
                          const next = e.target.value
                          if (next) {
                            void changeLegExpiry(leg, next)
                          }
                        }}
                        className="h-7 max-w-[8.5rem] rounded-md border border-slate-200 bg-white px-2 text-[11px] font-medium text-slate-900 outline-none focus:border-blue-500 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100"
                      >
                        {(leg.expiry && !expiries.includes(leg.expiry)
                          ? [leg.expiry, ...expiries]
                          : expiries
                        ).map((expiry) => (
                          <option key={expiry} value={expiry}>
                            {expiry}
                          </option>
                        ))}
                      </select>
                      <span className="shrink-0 font-num text-[11px] text-slate-500 dark:text-slate-400">
                        LTP {formatPrice(prices[leg.instrumentKey])}
                      </span>
                      <div className="ml-auto flex items-center gap-2">
                        <label className="flex items-center gap-1 text-[10px] text-slate-500">
                          <span>Lots</span>
                          <input
                            type="number"
                            min={1}
                            step={1}
                            title="Qty (lots)"
                            value={leg.lots}
                            onChange={(e) => {
                              const raw = e.target.value
                              if (raw === '') {
                                updateLeg(leg.id, { lots: 1 })
                                return
                              }
                              const next = Number.parseInt(raw, 10)
                              if (!Number.isNaN(next)) {
                                updateLeg(leg.id, { lots: Math.max(1, next) })
                              }
                            }}
                            className="h-7 w-14 rounded-md border border-slate-200 bg-white px-2 font-num text-xs text-slate-900 outline-none focus:border-blue-500 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100"
                          />
                        </label>
                        <label className="flex items-center gap-1 text-[10px] text-slate-500">
                          <span>Avg</span>
                          <input
                            type="number"
                            step="0.05"
                            title="Avg"
                            value={leg.entryPrice}
                            onChange={(e) =>
                              updateLeg(leg.id, { entryPrice: Number(e.target.value) || 0 })
                            }
                            className="h-7 w-18 rounded-md border border-slate-200 bg-white px-2 font-num text-xs text-slate-900 outline-none focus:border-blue-500 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100"
                          />
                        </label>
                        <button
                          type="button"
                          title="Remove leg"
                          onClick={() =>
                            setLegs((prev) => prev.filter((item) => item.id !== leg.id))
                          }
                          className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-slate-400 transition hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-950/40 dark:hover:text-rose-400"
                        >
                          <svg
                            viewBox="0 0 24 24"
                            className="h-3.5 w-3.5"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="2"
                          >
                            <path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6" />
                          </svg>
                        </button>
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        </section>
      </main>
    </div>
  )
}

function Chip({
  label,
  value,
  hint,
}: {
  label: string
  value: string
  hint?: string
}) {
  return (
    <div className="rounded-lg border border-slate-200/80 bg-slate-50/70 px-2.5 py-1 dark:border-slate-800 dark:bg-[#131924]">
      <div className="text-[9px] font-medium uppercase tracking-wider text-slate-500 dark:text-slate-400">
        {label}
      </div>
      <div className="font-num text-xs font-semibold text-slate-900 dark:text-slate-100">
        {value}
      </div>
      {hint ? (
        <div className="max-w-[7rem] truncate text-[9px] text-slate-500 dark:text-slate-400">
          {hint}
        </div>
      ) : null}
    </div>
  )
}
