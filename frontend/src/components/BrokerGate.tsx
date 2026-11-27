import { useMemo, useState, type FormEvent } from 'react'
import { ApiError, api, type BrokerStatus, type MeResponse } from '../api/client'
import { useFeedback } from './Feedback'
import { MstockPrimaryIpHint } from './MstockPrimaryIpHint'
import { ThemeToggle } from './ThemeToggle'

type Props = {
  me: MeResponse
  onLogout: () => void
  onUpdated: (me: MeResponse) => void
  /** Desk DEVICE_ID / Redis app session expired — clear local me and show AuthCard. */
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

export function BrokerGate({ me, onLogout, onUpdated, onUnauthenticated }: Props) {
  const { confirm, toast } = useFeedback()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const [mApiKey, setMApiKey] = useState('')
  const [mUser, setMUser] = useState('')
  const [mPass, setMPass] = useState('')
  const [mOtp, setMOtp] = useState('')
  const [mTotp, setMTotp] = useState('')
  const [mNeedOtp, setMNeedOtp] = useState(false)

  const mstock = useMemo(() => mstockStatus(me), [me])
  const deskReady = mstock.linked && mstock.tokenValid
  const currentStep = !mstock.credentialsConfigured ? 1 : !mNeedOtp && !mstock.tokenValid ? 2 : 3

  async function refresh() {
    const profile = await api.me()
    onUpdated(profile)
    return profile
  }

  async function saveMstockKey(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.saveMstockApiKey(mApiKey.trim())
      toast('MStock API key saved', 'success')
      await refresh()
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
      toast('OTP sent to your registered device', 'info')
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
      const profile = await refresh()
      if (profile.brokers?.some((b) => b.id === 'MSTOCK' && b.tokenValid)) {
        await api.setActiveBroker('MSTOCK')
        await refresh()
      }
      toast('MStock session connected', 'success')
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        onUnauthenticated()
        return
      }
      setError(err instanceof ApiError ? err.message : 'MStock session failed')
    } finally {
      setBusy(false)
    }
  }

  async function removeBroker() {
    const ok = await confirm({
      title: 'Remove MStock account',
      message: 'This clears your saved MStock API key and active session from SG Algo Desk.',
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
      toast('MStock credentials removed', 'info')
      await refresh()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not remove broker')
    } finally {
      setBusy(false)
    }
  }

  const steps = [
    { num: 1, label: 'API Key' },
    { num: 2, label: 'Client Login' },
    { num: 3, label: 'OTP / TOTP' },
  ]

  return (
    <div className="desk-grid-bg flex min-h-screen flex-col bg-[#F6F8FB] dark:bg-[#070A0F]">
      <header className="sticky top-0 z-30 border-b border-slate-200/80 bg-white/85 backdrop-blur-md dark:border-slate-800/80 dark:bg-[#0D1119]/85">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
          <div className="flex items-center gap-3">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 to-indigo-600 text-xs font-bold text-white">
              SG
            </div>
            <div>
              <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                SG Algo Desk
              </p>
              <h1 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                Broker Onboarding
              </h1>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <ThemeToggle />
            <button
              type="button"
              onClick={onLogout}
              className="rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 transition hover:bg-slate-100 dark:border-slate-800 dark:text-slate-300 dark:hover:bg-slate-800"
            >
              Log out
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-lg flex-1 flex-col justify-center px-4 py-8">
        <div className="mb-6 flex items-center justify-between px-2">
          {steps.map((s, idx) => {
            const done = deskReady || currentStep > s.num
            const active = !deskReady && currentStep === s.num
            return (
              <div key={s.num} className="flex flex-1 items-center">
                <div className="flex items-center gap-2">
                  <div
                    className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold transition ${
                      done
                        ? 'bg-emerald-500 text-white'
                        : active
                          ? 'bg-blue-600 text-white ring-4 ring-blue-500/20'
                          : 'border border-slate-300 bg-white text-slate-400 dark:border-slate-700 dark:bg-[#0D1119]'
                    }`}
                  >
                    {done ? '✓' : s.num}
                  </div>
                  <span
                    className={`text-xs font-medium ${
                      active || done
                        ? 'text-slate-900 dark:text-slate-100'
                        : 'text-slate-400 dark:text-slate-500'
                    }`}
                  >
                    {s.label}
                  </span>
                </div>
                {idx < steps.length - 1 && (
                  <div
                    className={`mx-3 h-px flex-1 ${
                      done ? 'bg-emerald-500' : 'bg-slate-200 dark:bg-slate-800'
                    }`}
                  />
                )}
              </div>
            )
          })}
        </div>

        <div className="rounded-2xl border border-slate-200/90 bg-white p-7 shadow-xl shadow-slate-900/5 dark:border-slate-800/90 dark:bg-[#0D1119] dark:shadow-black/40">
          {error && (
            <div className="mb-4 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2.5 text-xs font-medium text-rose-700 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300">
              {error}
            </div>
          )}

          {!mstock.credentialsConfigured ? (
            <form className="space-y-4" onSubmit={saveMstockKey}>
              <div>
                <h2 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
                  Configure MStock API Key
                </h2>
                <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                  Generate your Trading API key at trade.mstock.com → Trading APIs.
                </p>
              </div>
              <MstockPrimaryIpHint />
              <label className="block text-xs">
                <span className="mb-1.5 block font-medium text-slate-600 dark:text-slate-300">
                  API key
                </span>
                <input
                  required
                  value={mApiKey}
                  onChange={(e) => setMApiKey(e.target.value)}
                  placeholder="Paste your MStock API key"
                  className="h-10 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3 font-mono text-xs text-slate-900 outline-none focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100"
                />
              </label>
              <button
                type="submit"
                disabled={busy}
                className="h-10 w-full rounded-xl bg-blue-600 px-4 text-sm font-semibold text-white transition hover:bg-blue-500 disabled:opacity-60"
              >
                {busy ? 'Saving…' : 'Save & Continue'}
              </button>
            </form>
          ) : !mstock.tokenValid && !mNeedOtp ? (
            <form className="space-y-4" onSubmit={mstockLogin}>
              <div>
                <h2 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
                  {mstock.linked ? 'Reconnect MStock Session' : 'Sign in to MStock'}
                </h2>
                <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                  MStock sessions reset daily at midnight IST.
                </p>
              </div>
              <MstockPrimaryIpHint />
              <label className="block text-xs">
                <span className="mb-1.5 block font-medium text-slate-600 dark:text-slate-300">
                  Client code / username
                </span>
                <input
                  required
                  value={mUser}
                  onChange={(e) => setMUser(e.target.value)}
                  className="h-10 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3 text-sm text-slate-900 outline-none focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100"
                />
              </label>
              <label className="block text-xs">
                <span className="mb-1.5 block font-medium text-slate-600 dark:text-slate-300">
                  Password
                </span>
                <input
                  type="password"
                  required
                  value={mPass}
                  onChange={(e) => setMPass(e.target.value)}
                  className="h-10 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3 text-sm text-slate-900 outline-none focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100"
                />
              </label>
              <div className="flex gap-2 pt-1">
                <button
                  type="submit"
                  disabled={busy}
                  className="h-10 flex-1 rounded-xl bg-blue-600 px-4 text-sm font-semibold text-white transition hover:bg-blue-500 disabled:opacity-60"
                >
                  {busy ? 'Sending…' : 'Send OTP'}
                </button>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void removeBroker()}
                  className="h-10 rounded-xl border border-rose-200 px-4 text-xs font-semibold text-rose-600 transition hover:bg-rose-50 dark:border-rose-900/60 dark:text-rose-400 dark:hover:bg-rose-950/30"
                >
                  Reset
                </button>
              </div>
            </form>
          ) : !mstock.tokenValid && mNeedOtp ? (
            <form className="space-y-4" onSubmit={mstockSession}>
              <div>
                <h2 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
                  Verify OTP or TOTP
                </h2>
                <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                  Enter the SMS/email OTP or your authenticator TOTP code.
                </p>
              </div>
              <label className="block text-xs">
                <span className="mb-1.5 block font-medium text-slate-600 dark:text-slate-300">
                  OTP
                </span>
                <input
                  value={mOtp}
                  onChange={(e) => setMOtp(e.target.value)}
                  placeholder="6-digit OTP"
                  className="h-10 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3 font-mono text-sm text-slate-900 outline-none focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100"
                />
              </label>
              <label className="block text-xs">
                <span className="mb-1.5 block font-medium text-slate-600 dark:text-slate-300">
                  Or TOTP
                </span>
                <input
                  value={mTotp}
                  onChange={(e) => setMTotp(e.target.value)}
                  placeholder="Authenticator code"
                  className="h-10 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3 font-mono text-sm text-slate-900 outline-none focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100"
                />
              </label>
              <div className="flex gap-2 pt-1">
                <button
                  type="submit"
                  disabled={busy || (!mOtp.trim() && !mTotp.trim())}
                  className="h-10 flex-1 rounded-xl bg-blue-600 px-4 text-sm font-semibold text-white transition hover:bg-blue-500 disabled:opacity-60"
                >
                  {busy ? 'Connecting…' : 'Open Desk'}
                </button>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void removeBroker()}
                  className="h-10 rounded-xl border border-rose-200 px-4 text-xs font-semibold text-rose-600 transition hover:bg-rose-50 dark:border-rose-900/60 dark:text-rose-400 dark:hover:bg-rose-950/30"
                >
                  Reset
                </button>
              </div>
            </form>
          ) : (
            <div className="space-y-3 text-center">
              <div className="mx-auto flex h-10 w-10 items-center justify-center rounded-full bg-emerald-500/15 text-emerald-500">
                ✓
              </div>
              <h2 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
                MStock Connected
              </h2>
              <p className="text-xs text-slate-500">
                {mstock.userName ? `Linked as ${mstock.userName}` : 'Session active until midnight IST.'}
              </p>
            </div>
          )}

          {deskReady && (
            <p className="mt-4 text-center text-xs font-medium text-emerald-600 dark:text-emerald-400">
              Broker ready — opening terminal…
            </p>
          )}
        </div>
      </main>
    </div>
  )
}
