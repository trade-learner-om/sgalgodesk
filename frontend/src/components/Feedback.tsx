import {
  createContext,
  useCallback,
  useContext,
  useRef,
  useState,
  type ReactNode,
} from 'react'

export type ToastTone = 'info' | 'success' | 'error'

type ToastItem = {
  id: string
  message: string
  tone: ToastTone
}

export type ConfirmOptions = {
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  tone?: 'danger' | 'primary'
}

type FeedbackContextValue = {
  toast: (message: string, tone?: ToastTone) => void
  confirm: (options: ConfirmOptions) => Promise<boolean>
}

const FeedbackContext = createContext<FeedbackContextValue | null>(null)

export function FeedbackProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const [confirmState, setConfirmState] = useState<ConfirmOptions | null>(null)
  const resolverRef = useRef<((value: boolean) => void) | null>(null)

  const toast = useCallback((message: string, tone: ToastTone = 'info') => {
    const id = crypto.randomUUID()
    setToasts((prev) => [...prev.slice(-3), { id, message, tone }])
    window.setTimeout(() => {
      setToasts((prev) => prev.filter((item) => item.id !== id))
    }, 3600)
  }, [])

  const confirm = useCallback((options: ConfirmOptions) => {
    return new Promise<boolean>((resolve) => {
      resolverRef.current = resolve
      setConfirmState(options)
    })
  }, [])

  function resolveConfirm(accepted: boolean) {
    setConfirmState(null)
    const resolve = resolverRef.current
    resolverRef.current = null
    resolve?.(accepted)
  }

  return (
    <FeedbackContext.Provider value={{ toast, confirm }}>
      {children}

      {/* Non-blocking Toast Stack */}
      <div
        aria-live="polite"
        className="pointer-events-none fixed bottom-4 right-4 z-[70] flex max-w-sm flex-col gap-2"
      >
        {toasts.map((item) => {
          const toneStyle =
            item.tone === 'success'
              ? 'border-emerald-500/30 bg-emerald-950/95 text-emerald-100'
              : item.tone === 'error'
                ? 'border-rose-500/30 bg-rose-950/95 text-rose-100'
                : 'border-slate-700/80 bg-slate-900/95 text-slate-100'
          return (
            <div
              key={item.id}
              className={`pointer-events-auto flex items-center gap-2.5 rounded-xl border px-3.5 py-2.5 text-xs font-medium shadow-xl backdrop-blur-md ${toneStyle}`}
            >
              <span
                className={`h-2 w-2 shrink-0 rounded-full ${
                  item.tone === 'success'
                    ? 'bg-emerald-400'
                    : item.tone === 'error'
                      ? 'bg-rose-400'
                      : 'bg-blue-400'
                }`}
              />
              <span className="leading-relaxed">{item.message}</span>
            </div>
          )
        })}
      </div>

      {/* Non-blocking Confirmation Modal */}
      {confirmState && (
        <div
          className="fixed inset-0 z-[80] flex items-center justify-center bg-slate-950/60 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
        >
          <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-800 dark:bg-[#0D1119]">
            <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">
              {confirmState.title}
            </h3>
            <p className="mt-2 whitespace-pre-line text-xs leading-relaxed text-slate-600 dark:text-slate-300">
              {confirmState.message}
            </p>
            <div className="mt-5 flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={() => resolveConfirm(false)}
                className="rounded-lg border border-slate-200 bg-white px-3.5 py-2 text-xs font-medium text-slate-700 transition hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800"
              >
                {confirmState.cancelLabel ?? 'Cancel'}
              </button>
              <button
                type="button"
                onClick={() => resolveConfirm(true)}
                className={`rounded-lg px-3.5 py-2 text-xs font-semibold text-white transition ${
                  confirmState.tone === 'danger'
                    ? 'bg-rose-600 hover:bg-rose-500'
                    : 'bg-blue-600 hover:bg-blue-500'
                }`}
              >
                {confirmState.confirmLabel ?? 'Confirm'}
              </button>
            </div>
          </div>
        </div>
      )}
    </FeedbackContext.Provider>
  )
}

export function useFeedback(): FeedbackContextValue {
  const ctx = useContext(FeedbackContext)
  if (!ctx) {
    return {
      toast: () => undefined,
      confirm: async (opts) => window.confirm(`${opts.title}\n\n${opts.message}`),
    }
  }
  return ctx
}
