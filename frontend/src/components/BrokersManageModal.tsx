import { useMemo, useState, type FormEvent } from 'react'
import { ApiError, api, type BrokerStatus, type MeResponse } from '../api/client'
import { useFeedback } from './Feedback'
import { MstockPrimaryIpHint } from './MstockPrimaryIpHint'

type Props = {
  me: MeResponse
  onClose: () => void
  onUpdated: (me: MeResponse) => void
  onNoValidBroker: () => void
  onUnauthenticated: () => void
}

function mstockStatus(me: MeResponse): BrokerStatus {
  return (
    me.brokers?.find((b) => b.id === 'MSTOCK') ?? {
      id: 'MSTOCK',
      credentialsConfigured: me.credentialsConfigured,
      linked: me.brokerLinked,
      tokenValid: me.tokenValid,
      userName: me.userName,
    }
  )
}

function statusText(b: BrokerStatus): string {
  if (b.tokenValid) {
    return b.userName ? `Linked as ${b.userName}` : 'Session active'
  }
  if (b.linked && !b.tokenValid) {
    return 'Session expired — reconnect required'
  }
  if (b.credentialsConfigured) {
    return 'API key saved — sign in to connect'
  }
  return 'Not configured'
}

export function BrokersManageModal({
  me,
  onClose,
  onUpdated,
  onNoValidBroker,
  onUnauthenticated,
}: Props) {
  const { confirm, toast } = useFeedback()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [panelOpen, setPanelOpen] = useState(false)

  const [mApiKey, setMApiKey] = useState('')
  const [mUser, setMUser] = useState('')
  const [mPass, setMPass] = useState('')
  const [mOtp, setMOtp] = useState('')
  const [mTotp, setMTotp] = useState('')
  const [mNeedOtp, setMNeedOtp] = useState(false)

  const mstock = useMemo(() => mstockStatus(me), [me])

  async function refresh() {
    const profile = await api.me()
    onUpdated(profile)
    return profile
  }

  async function afterMutation(profile: MeResponse) {
    const anyValid = (profile.brokers ?? []).some((b) => b.tokenValid) || profile.tokenValid
    if (!anyValid) {
      onNoValidBroker()
      onClose()
    }
  }

  async function removeBroker() {
    const ok = await confirm({
      title: 'Remove MStock account',
      message: 'Remove your MStock API key and disconnect this desk session?',
      confirmLabel: 'Remove',
      tone: 'danger',
    })
    if (!ok) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.clearMstockCredentials()
      setMNeedOtp(false)
      toast('MStock account removed', 'info')
      const profile = await refresh()
      await afterMutation(profile)
      setPanelOpen(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not remove broker')
    } finally {
      setBusy(false)
    }
  }

  async function saveMstockKey(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.saveMstockApiKey(mApiKey.trim())
      await refresh()
      setMApiKey('')
      toast('API key saved', 'success')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save MStock API key')
    } finally {
      setBusy(false)
    }
  }

  async function mstockLogin(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.mstockLogin(mUser.trim(), mPass)
      setMNeedOtp(true)
      toast('OTP sent', 'info')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'MStock login failed')
    } finally {
      setBusy(false)
    }
  }

  async function mstockSession(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (mTotp.trim()) {
        await api.mstockSessionTotp(mTotp.trim())
      } else {
        await api.mstockSessionOtp(mOtp.trim())
      }
      setMNeedOtp(false)
      await api.setActiveBroker('MSTOCK')
      await refresh()
      toast('MStock session refreshed', 'success')
      onClose()
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        onClose()
        onUnauthenticated()
        return
      }
      setError(err instanceof ApiError ? err.message : 'MStock session failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 p-4 backdrop-blur-sm">
      <div className="max-h-[90vh] w-full max-w-lg overflow-auto rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-800 dark:bg-[#0D1119]">
        <div className="mb-4 flex items-center justify-between gap-2">
          <div>
            <h2 className="text-base font-semibold text-slate-900 dark:text-slate-100">
              Broker Connections
            </h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Manage your MStock Trading API session and credentials.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close modal"
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800 dark:hover:text-slate-200"
          >
            ✕
          </button>
        </div>

        {error && (
          <div className="mb-3 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300">
            {error}
          </div>
        )}

        <div className="rounded-xl border border-slate-200 bg-slate-50/50 p-4 dark:border-slate-800 dark:bg-[#131924]">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <div className="flex items-center gap-2">
                <p className="text-sm font-semibold text-slate-900 dark:text-slate-100">MStock</p>
                {mstock.tokenValid && (
                  <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-emerald-600 dark:text-emerald-400">
                    <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
                    Connected
                  </span>
                )}
              </div>
              <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{statusText(mstock)}</p>
            </div>
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  setPanelOpen((v) => !v)
                  setMNeedOtp(false)
                }}
                className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition ${
                  mstock.tokenValid
                    ? 'border border-slate-200 bg-white text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-[#0D1119] dark:text-slate-200'
                    : 'bg-blue-600 text-white hover:bg-blue-500'
                }`}
              >
                {mstock.tokenValid ? 'Reconnect' : mstock.credentialsConfigured ? 'Connect' : 'Add'}
              </button>
              {(mstock.credentialsConfigured || mstock.linked) && (
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void removeBroker()}
                  className="rounded-lg border border-rose-200 px-3 py-1.5 text-xs font-semibold text-rose-600 transition hover:bg-rose-50 dark:border-rose-900/60 dark:text-rose-400 dark:hover:bg-rose-950/30"
                >
                  Remove
                </button>
              )}
            </div>
          </div>
        </div>

        {panelOpen && (
          <div className="mt-4 rounded-xl border border-slate-200 p-4 dark:border-slate-800">
            <h3 className="mb-3 text-sm font-semibold text-slate-900 dark:text-slate-100">
              {mstock.credentialsConfigured ? 'Refresh MStock Session' : 'Add MStock API Key'}
            </h3>
            {!mstock.credentialsConfigured ? (
              <form className="space-y-3" onSubmit={saveMstockKey}>
                <MstockPrimaryIpHint />
                <label className="block text-xs">
                  <span className="mb-1 block font-medium text-slate-600 dark:text-slate-300">
                    API key
                  </span>
                  <input
                    required
                    value={mApiKey}
                    onChange={(e) => setMApiKey(e.target.value)}
                    className="h-9 w-full rounded-lg border border-slate-200 bg-white px-3 font-mono text-xs dark:border-slate-700 dark:bg-[#070A0F] dark:text-slate-100"
                  />
                </label>
                <button
                  type="submit"
                  disabled={busy}
                  className="rounded-lg bg-blue-600 px-3.5 py-2 text-xs font-semibold text-white hover:bg-blue-500 disabled:opacity-60"
                >
                  Save API key
                </button>
              </form>
            ) : !mNeedOtp ? (
              <form className="space-y-3" onSubmit={mstockLogin}>
                <MstockPrimaryIpHint />
                <label className="block text-xs">
                  <span className="mb-1 block font-medium text-slate-600 dark:text-slate-300">
                    Client code / username
                  </span>
                  <input
                    required
                    value={mUser}
                    onChange={(e) => setMUser(e.target.value)}
                    className="h-9 w-full rounded-lg border border-slate-200 bg-white px-3 text-xs dark:border-slate-700 dark:bg-[#070A0F] dark:text-slate-100"
                  />
                </label>
                <label className="block text-xs">
                  <span className="mb-1 block font-medium text-slate-600 dark:text-slate-300">
                    Password
                  </span>
                  <input
                    type="password"
                    required
                    value={mPass}
                    onChange={(e) => setMPass(e.target.value)}
                    className="h-9 w-full rounded-lg border border-slate-200 bg-white px-3 text-xs dark:border-slate-700 dark:bg-[#070A0F] dark:text-slate-100"
                  />
                </label>
                <button
                  type="submit"
                  disabled={busy}
                  className="rounded-lg bg-blue-600 px-3.5 py-2 text-xs font-semibold text-white hover:bg-blue-500 disabled:opacity-60"
                >
                  Send OTP
                </button>
              </form>
            ) : (
              <form className="space-y-3" onSubmit={mstockSession}>
                <label className="block text-xs">
                  <span className="mb-1 block font-medium text-slate-600 dark:text-slate-300">
                    OTP
                  </span>
                  <input
                    value={mOtp}
                    onChange={(e) => setMOtp(e.target.value)}
                    className="h-9 w-full rounded-lg border border-slate-200 bg-white px-3 font-mono text-xs dark:border-slate-700 dark:bg-[#070A0F] dark:text-slate-100"
                  />
                </label>
                <label className="block text-xs">
                  <span className="mb-1 block font-medium text-slate-600 dark:text-slate-300">
                    Or TOTP
                  </span>
                  <input
                    value={mTotp}
                    onChange={(e) => setMTotp(e.target.value)}
                    className="h-9 w-full rounded-lg border border-slate-200 bg-white px-3 font-mono text-xs dark:border-slate-700 dark:bg-[#070A0F] dark:text-slate-100"
                  />
                </label>
                <button
                  type="submit"
                  disabled={busy || (!mOtp.trim() && !mTotp.trim())}
                  className="rounded-lg bg-blue-600 px-3.5 py-2 text-xs font-semibold text-white hover:bg-blue-500 disabled:opacity-60"
                >
                  Create session
                </button>
              </form>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
