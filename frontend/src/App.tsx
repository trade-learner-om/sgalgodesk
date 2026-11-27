import { useCallback, useEffect, useState } from 'react'
import { ApiError, api, type MeResponse } from './api/client'
import { AppShell } from './components/AppShell'
import { AuthCard } from './components/AuthCard'
import { BrokerGate } from './components/BrokerGate'
import { FeedbackProvider } from './components/Feedback'

function mstockReady(me: MeResponse): boolean {
  const status = me.brokers?.find((b) => b.id === 'MSTOCK')
  if (status) {
    return status.linked && status.tokenValid
  }
  return me.credentialsConfigured && me.brokerLinked && me.tokenValid
}

function AppContent() {
  const [me, setMe] = useState<MeResponse | null>(null)
  const [loading, setLoading] = useState(true)

  const refreshMe = useCallback(async () => {
    try {
      const profile = await api.me()
      setMe(profile)
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setMe(null)
      }
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refreshMe()
  }, [refreshMe])

  async function handleLogout() {
    try {
      await api.logout()
    } finally {
      setMe(null)
    }
  }

  function handleUnauthenticated() {
    setMe(null)
  }

  if (loading) {
    return (
      <div className="desk-grid-bg flex min-h-screen flex-col items-center justify-center gap-4 bg-[#F6F8FB] dark:bg-[#070A0F]">
        <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-gradient-to-br from-blue-600 to-indigo-600 text-sm font-bold tracking-tight text-white shadow-lg shadow-blue-500/25">
          SG
        </div>
        <div className="flex items-center gap-2 text-xs font-medium text-slate-500 dark:text-slate-400">
          <span className="h-1.5 w-1.5 animate-ping rounded-full bg-blue-500" />
          Initializing SG Algo Desk…
        </div>
      </div>
    )
  }

  if (!me) {
    return <AuthCard onAuthenticated={setMe} />
  }

  if (!mstockReady(me)) {
    return (
      <BrokerGate
        me={me}
        onLogout={handleLogout}
        onUpdated={(profile) => {
          setMe(profile)
        }}
        onUnauthenticated={handleUnauthenticated}
      />
    )
  }

  return (
    <AppShell
      me={me}
      onLogout={handleLogout}
      onMeUpdated={setMe}
      onUnauthenticated={handleUnauthenticated}
      onTokenExpired={() => {
        setMe((prev) => {
          if (!prev) {
            return prev
          }
          return {
            ...prev,
            tokenValid: false,
            brokers: (prev.brokers ?? []).map((b) => ({
              ...b,
              tokenValid: false,
            })),
          }
        })
      }}
    />
  )
}

export default function App() {
  return (
    <FeedbackProvider>
      <AppContent />
    </FeedbackProvider>
  )
}
