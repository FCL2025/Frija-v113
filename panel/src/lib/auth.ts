/**
 * Token 儲存：localStorage 的 `panel.token`。
 * 用 useSyncExternalStore 讓 React 與非 React 程式碼（QueryClient 的 401 處理）共用同一份狀態。
 */
const STORAGE_KEY = 'panel.token'

let token: string | null = readStored()
const listeners = new Set<() => void>()

function readStored(): string | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return v && v.trim() !== '' ? v : null
  } catch {
    return null
  }
}

function emit() {
  for (const l of listeners) l()
}

export function getToken(): string | null {
  return token
}

export function setToken(value: string) {
  token = value
  try {
    localStorage.setItem(STORAGE_KEY, value)
  } catch {
    /* 私密模式等情況：只保留在記憶體 */
  }
  emit()
}

export function clearToken() {
  if (token === null) return
  token = null
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    /* ignore */
  }
  emit()
}

export function subscribeToken(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
