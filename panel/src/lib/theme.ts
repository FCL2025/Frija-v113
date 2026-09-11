import { useEffect, useState } from 'react'

/**
 * 深色模式：`<html class="dark">`，選擇存在 localStorage 的 `panel.theme`。
 * 沒有存值時跟隨 prefers-color-scheme（index.html 的 inline script 也用同一套規則，避免閃白）。
 */
const STORAGE_KEY = 'panel.theme'

export type ThemeSetting = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

const media = () => window.matchMedia('(prefers-color-scheme: dark)')

export function getThemeSetting(): ThemeSetting {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

export function resolveTheme(setting: ThemeSetting = getThemeSetting()): ResolvedTheme {
  if (setting === 'system') return media().matches ? 'dark' : 'light'
  return setting
}

export function applyTheme(resolved: ResolvedTheme) {
  document.documentElement.classList.toggle('dark', resolved === 'dark')
  document.documentElement.style.colorScheme = resolved
}

export function applyStoredTheme() {
  applyTheme(resolveTheme())
}

export function setThemeSetting(setting: ThemeSetting) {
  try {
    if (setting === 'system') localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, setting)
  } catch {
    /* ignore */
  }
  applyTheme(resolveTheme(setting))
}

/** 跟隨系統時，系統切換也要跟著變 */
export function watchSystemTheme(onChange: () => void): () => void {
  const mq = media()
  const handler = () => {
    if (getThemeSetting() === 'system') {
      applyStoredTheme()
      onChange()
    }
  }
  mq.addEventListener('change', handler)
  return () => mq.removeEventListener('change', handler)
}

/** 目前實際套用的主題；跟著 <html class="dark"> 走，所以切換按鈕與系統變化都會反映 */
export function useTheme(): { dark: boolean } {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'))
  useEffect(() => {
    const observer = new MutationObserver(() => setDark(document.documentElement.classList.contains('dark')))
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
    return () => observer.disconnect()
  }, [])
  return { dark }
}
