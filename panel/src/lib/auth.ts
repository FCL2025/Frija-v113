/**
 * Token 儲存：localStorage 的 `panel.token`。
 * 用 useSyncExternalStore 讓 React 與非 React 程式碼（QueryClient 的 401 處理）共用同一份狀態。
 *
 * 伺服器沒設 tms.PanelToken 時根本不需要 token，所以「沒有 token」不等於「要登入」：
 * 只有伺服器真的回了 401，`loginRequired` 才會變成 true。
 */
const STORAGE_KEY = 'panel.token'

let token: string | null = readStored()
let loginRequired = false
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

export function getLoginRequired(): boolean {
  return loginRequired
}

export function setToken(value: string) {
  token = value
  loginRequired = false
  try {
    localStorage.setItem(STORAGE_KEY, value)
  } catch {
    /* 私密模式等情況：只保留在記憶體 */
  }
  emit()
}

/** 伺服器回了 401：丟掉 token 並顯示登入畫面。 */
export function clearToken() {
  if (token === null && loginRequired) return
  forget()
  loginRequired = true
  emit()
}

/** 使用者按登出：丟掉 token，再問一次伺服器要不要登入（不需要的話直接回到主畫面）。 */
export function logout() {
  forget()
  loginRequired = false
  emit()
}

function forget() {
  token = null
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    /* ignore */
  }
}

export function subscribeToken(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
