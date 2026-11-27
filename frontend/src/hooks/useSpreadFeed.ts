import { useEffect, useRef } from 'react'

export type SpreadLegInput = {
  instrumentKey: string
  side: 'BUY' | 'SELL'
  quantity: number
  lotSize: number
  lots: number
  entryPrice: number
}

export type SpreadTick = {
  ts: number
  spreadMark: number
  runningPnl: number
  runningPnl1Lot: number
  combinedPremium: number
  lots: number
  kind: 'credit' | 'debit'
}

type Options = {
  legs: SpreadLegInput[]
  enabled?: boolean
  onTick: (tick: SpreadTick) => void
  onAuthError?: () => void
}

function spreadWsUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/api/ws/spread`
}

export function useSpreadFeed({ legs, enabled = true, onTick, onAuthError }: Options) {
  const onTickRef = useRef(onTick)
  const onAuthErrorRef = useRef(onAuthError)
  const legsRef = useRef(legs)
  onTickRef.current = onTick
  onAuthErrorRef.current = onAuthError
  legsRef.current = legs

  const fingerprint = legs
    .map((l) => `${l.instrumentKey}:${l.side}:${l.quantity}:${l.lotSize}:${l.lots}:${l.entryPrice}`)
    .sort()
    .join('|')

  useEffect(() => {
    if (!enabled || legs.length === 0) {
      return
    }

    let closed = false
    let reconnectTimer: number | undefined
    let socket: WebSocket | null = null

    function sendSet(ws: WebSocket) {
      ws.send(
        JSON.stringify({
          action: 'set',
          legs: legsRef.current.map((l) => ({
            instrumentKey: l.instrumentKey,
            side: l.side,
            quantity: l.quantity,
            lotSize: l.lotSize,
            lots: l.lots,
            entryPrice: l.entryPrice,
          })),
        }),
      )
    }

    function connect() {
      if (closed) {
        return
      }
      socket = new WebSocket(spreadWsUrl())
      socket.onopen = () => {
        if (socket) {
          sendSet(socket)
        }
      }
      socket.onmessage = (event) => {
        try {
          const payload = JSON.parse(String(event.data)) as SpreadTick
          if (typeof payload.ts === 'number') {
            const runningPnl1Lot =
              typeof payload.runningPnl1Lot === 'number'
                ? payload.runningPnl1Lot
                : payload.runningPnl
            onTickRef.current({
              ...payload,
              runningPnl1Lot,
              lots: typeof payload.lots === 'number' ? payload.lots : 1,
            })
          }
        } catch {
          /* ignore */
        }
      }
      socket.onclose = (ev) => {
        if (ev.code === 4401) {
          onAuthErrorRef.current?.()
          return
        }
        if (!closed) {
          reconnectTimer = window.setTimeout(connect, 1500)
        }
      }
    }

    connect()
    return () => {
      closed = true
      if (reconnectTimer != null) {
        window.clearTimeout(reconnectTimer)
      }
      socket?.close()
    }
  }, [enabled, fingerprint])
}
