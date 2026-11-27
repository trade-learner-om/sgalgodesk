import { useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'

export function MstockPrimaryIpHint() {
  const [ip, setIp] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const res = await api.mstockPrimaryIp()
        if (!cancelled) {
          setIp(res.ip)
          setError(null)
        }
      } catch (err) {
        if (!cancelled) {
          setIp(null)
          setError(
            err instanceof ApiError
              ? err.message
              : 'Could not detect server IP — set mstock.primary-ip on the backend',
          )
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  async function copy() {
    if (!ip) {
      return
    }
    try {
      await navigator.clipboard.writeText(ip)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1600)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="rounded-xl border border-amber-200/80 bg-amber-50/70 px-3.5 py-2.5 text-xs dark:border-amber-900/50 dark:bg-amber-950/30">
      <p className="font-medium text-amber-900 dark:text-amber-200">
        Register this egress IP as Primary / Static IP in MStock Trading APIs
      </p>
      {ip ? (
        <div className="mt-2 flex items-center gap-2">
          <code className="flex-1 truncate rounded-lg border border-amber-200/90 bg-white px-2.5 py-1.5 font-mono text-[11px] font-medium text-slate-900 dark:border-amber-900/60 dark:bg-[#070A0F] dark:text-slate-100">
            {ip}
          </code>
          <button
            type="button"
            onClick={() => void copy()}
            className="shrink-0 rounded-lg border border-amber-300 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-amber-900 transition hover:bg-amber-100 dark:border-amber-800 dark:bg-[#0D1119] dark:text-amber-200 dark:hover:bg-amber-900/40"
          >
            {copied ? '✓ Copied' : 'Copy IP'}
          </button>
        </div>
      ) : error ? (
        <p className="mt-1.5 text-rose-600 dark:text-rose-400">{error}</p>
      ) : (
        <p className="mt-1.5 text-amber-800/70 dark:text-amber-200/60">Detecting server IP…</p>
      )}
    </div>
  )
}
