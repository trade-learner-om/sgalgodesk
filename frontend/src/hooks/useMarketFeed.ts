import { useEffect, useRef } from 'react'

export type MarketTick = {
  instrumentKey: string
  ltp: number
  close?: number | null
}

type Options = {
  instrumentKeys: string[]
  enabled?: boolean
  onTick?: (tick: MarketTick) => void
  onBatch?: (ticks: MarketTick[]) => void
  onAuthError?: () => void
  /** Coalesce burst ticks via rAF/interval (ms). Defaults to 60ms for smooth 16fps+ UI updates. */
  batchMs?: number
}

function marketWsUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/api/ws/market`
}

export function useMarketFeed({
  instrumentKeys,
  enabled = true,
  onTick,
  onBatch,
  onAuthError,
  batchMs = 60,
}: Options) {
  const onTickRef = useRef(onTick)
  const onBatchRef = useRef(onBatch)
  const onAuthErrorRef = useRef(onAuthError)
  const keysRef = useRef<string[]>(instrumentKeys)
  const socketRef = useRef<WebSocket | null>(null)
  const pendingRef = useRef<Map<string, MarketTick>>(new Map())
  const flushTimerRef = useRef<number | null>(null)

  onTickRef.current = onTick
  onBatchRef.current = onBatch
  onAuthErrorRef.current = onAuthError
  keysRef.current = instrumentKeys

  const keyFingerprint = instrumentKeys.slice().sort().join('\0')

  useEffect(() => {
    if (!enabled) {
      socketRef.current?.close()
      socketRef.current = null
      return
    }

    let closed = false
    let reconnectTimer: number | undefined

    function flush() {
      flushTimerRef.current = null
      if (pendingRef.current.size === 0) {
        return
      }
      const batch = [...pendingRef.current.values()]
      pendingRef.current.clear()
      if (onBatchRef.current) {
        onBatchRef.current(batch)
      } else if (onTickRef.current) {
        for (const tick of batch) {
          onTickRef.current(tick)
        }
      }
    }

    function scheduleFlush() {
      if (flushTimerRef.current != null) {
        return
      }
      flushTimerRef.current = window.setTimeout(flush, batchMs)
    }

    function sendSet(socket: WebSocket) {
      const keys = keysRef.current.slice().sort()
      socket.send(JSON.stringify({ action: 'set', instrumentKeys: keys }))
    }

    function connect() {
      if (closed) {
        return
      }
      const socket = new WebSocket(marketWsUrl())
      socketRef.current = socket

      socket.onopen = () => {
        sendSet(socket)
      }

      socket.onmessage = (event) => {
        try {
          const payload = JSON.parse(String(event.data)) as MarketTick
          if (payload?.instrumentKey && typeof payload.ltp === 'number') {
            pendingRef.current.set(payload.instrumentKey, payload)
            scheduleFlush()
          }
        } catch {
          // ignore malformed frames
        }
      }

      socket.onclose = (event) => {
        socketRef.current = null
        if (closed) {
          return
        }
        if (event.code === 1008 || event.code === 4401) {
          onAuthErrorRef.current?.()
          return
        }
        reconnectTimer = window.setTimeout(connect, 2500)
      }

      socket.onerror = () => {
        socket.close()
      }
    }

    connect()

    return () => {
      closed = true
      if (reconnectTimer) {
        window.clearTimeout(reconnectTimer)
      }
      if (flushTimerRef.current != null) {
        window.clearTimeout(flushTimerRef.current)
        flushTimerRef.current = null
      }
      pendingRef.current.clear()
      socketRef.current?.close()
      socketRef.current = null
    }
  }, [enabled, batchMs])

  useEffect(() => {
    if (!enabled) {
      return
    }
    const socket = socketRef.current
    if (socket && socket.readyState === WebSocket.OPEN) {
      const keys = keyFingerprint ? keyFingerprint.split('\0').filter(Boolean) : []
      socket.send(JSON.stringify({ action: 'set', instrumentKeys: keys }))
    }
  }, [enabled, keyFingerprint])
}
