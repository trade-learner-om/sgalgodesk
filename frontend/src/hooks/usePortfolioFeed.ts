import { useEffect, useRef } from 'react'
import type { PositionsResponse } from '../api/client'

export type PortfolioSnapshotMessage = PositionsResponse & {
  type?: string
}

type Options = {
  enabled?: boolean
  onSnapshot: (snapshot: PositionsResponse) => void
  onAuthError?: () => void
}

function portfolioWsUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/api/ws/portfolio`
}

export function usePortfolioFeed({ enabled = true, onSnapshot, onAuthError }: Options) {
  const onSnapshotRef = useRef(onSnapshot)
  const onAuthErrorRef = useRef(onAuthError)
  const socketRef = useRef<WebSocket | null>(null)
  onSnapshotRef.current = onSnapshot
  onAuthErrorRef.current = onAuthError

  useEffect(() => {
    if (!enabled) {
      socketRef.current?.close()
      socketRef.current = null
      return
    }

    let closed = false
    let reconnectTimer: number | undefined

    function connect() {
      if (closed) {
        return
      }
      const socket = new WebSocket(portfolioWsUrl())
      socketRef.current = socket

      socket.onmessage = (event) => {
        try {
          const payload = JSON.parse(String(event.data)) as PortfolioSnapshotMessage
          if (payload && Array.isArray(payload.openGroups) && Array.isArray(payload.closedGroups)) {
            onSnapshotRef.current(payload)
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
        reconnectTimer = window.setTimeout(connect, 1500)
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
      socketRef.current?.close()
      socketRef.current = null
    }
  }, [enabled])
}
