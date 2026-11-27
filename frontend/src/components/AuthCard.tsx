import { useState, type FormEvent } from 'react'
import { ApiError, api, type MeResponse } from '../api/client'
import { ThemeToggle } from './ThemeToggle'

type Props = {
  onAuthenticated: (me: MeResponse) => void
}

export function AuthCard({ onAuthenticated }: Props) {
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const me =
        mode === 'login'
          ? await api.login(email.trim(), password)
          : await api.signup(email.trim(), password)
      onAuthenticated(me)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="desk-grid-bg relative flex min-h-screen items-center justify-center overflow-hidden bg-[#F6F8FB] px-4 py-10 dark:bg-[#070A0F]">
      <div className="pointer-events-none absolute -top-40 left-1/2 h-96 w-[42rem] -translate-x-1/2 rounded-full bg-blue-500/10 blur-3xl dark:bg-blue-600/15" />

      <div className="absolute right-4 top-4 z-10">
        <ThemeToggle />
      </div>

      <div className="relative z-10 w-full max-w-[420px]">
        <div className="rounded-2xl border border-slate-200/90 bg-white/95 p-7 shadow-xl shadow-slate-900/5 backdrop-blur-xl dark:border-slate-800/90 dark:bg-[#0D1119]/95 dark:shadow-black/40">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-blue-600 to-indigo-600 text-sm font-bold tracking-tight text-white shadow-md shadow-blue-500/25">
              SG
            </div>
            <div>
              <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-blue-600 dark:text-blue-400">
                Options &amp; F&amp;O Terminal
              </p>
              <h1 className="text-xl font-semibold tracking-tight text-slate-900 dark:text-slate-100">
                SG Algo Desk
              </h1>
            </div>
          </div>

          <p className="mt-4 text-xs leading-relaxed text-slate-500 dark:text-slate-400">
            Analyze live option chains, simulate T0 &amp; expiry payoffs, and execute multi-leg spreads with real-time streaming.
          </p>

          <div className="mt-6 grid grid-cols-2 rounded-xl bg-slate-100 p-1 dark:bg-[#070A0F]">
            <button
              type="button"
              onClick={() => {
                setMode('login')
                setError(null)
              }}
              className={`rounded-lg py-1.5 text-xs font-semibold transition ${
                mode === 'login'
                  ? 'bg-white text-slate-900 shadow-sm dark:bg-[#131924] dark:text-slate-100'
                  : 'text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200'
              }`}
            >
              Sign in
            </button>
            <button
              type="button"
              onClick={() => {
                setMode('signup')
                setError(null)
              }}
              className={`rounded-lg py-1.5 text-xs font-semibold transition ${
                mode === 'signup'
                  ? 'bg-white text-slate-900 shadow-sm dark:bg-[#131924] dark:text-slate-100'
                  : 'text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200'
              }`}
            >
              Create account
            </button>
          </div>

          <form className="mt-5 space-y-4" onSubmit={onSubmit}>
            <label className="block text-xs">
              <span className="mb-1.5 block font-medium text-slate-600 dark:text-slate-300">
                Email address
              </span>
              <input
                type="email"
                required
                autoComplete="email"
                placeholder="trader@desk.local"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="h-10 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3 text-sm text-slate-900 outline-none transition focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100 dark:focus:border-blue-500 dark:focus:bg-[#0B0F17]"
              />
            </label>

            <label className="block text-xs">
              <span className="mb-1.5 block font-medium text-slate-600 dark:text-slate-300">
                Password
              </span>
              <div className="relative">
                <input
                  type={showPassword ? 'text' : 'password'}
                  required
                  minLength={8}
                  autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                  placeholder="Minimum 8 characters"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="h-10 w-full rounded-xl border border-slate-200 bg-slate-50/70 pl-3 pr-14 text-sm text-slate-900 outline-none transition focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 dark:border-slate-800 dark:bg-[#070A0F] dark:text-slate-100 dark:focus:border-blue-500 dark:focus:bg-[#0B0F17]"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 rounded px-1.5 py-0.5 text-[11px] font-medium text-slate-400 hover:text-slate-700 dark:hover:text-slate-200"
                >
                  {showPassword ? 'Hide' : 'Show'}
                </button>
              </div>
            </label>

            {error && (
              <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={busy}
              className="h-10 w-full rounded-xl bg-gradient-to-r from-blue-600 to-indigo-600 px-4 text-sm font-semibold text-white shadow-md shadow-blue-500/20 transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {busy ? 'Authenticating…' : mode === 'login' ? 'Sign in to desk' : 'Create account'}
            </button>
          </form>
        </div>
      </div>
    </div>
  )
}
