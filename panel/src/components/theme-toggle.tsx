import { useEffect, useState } from 'react'
import { Moon, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { resolveTheme, setThemeSetting, watchSystemTheme, type ResolvedTheme } from '@/lib/theme'

/** 深色 / 淺色切換；選擇存在 localStorage 的 panel.theme */
export function ThemeToggle() {
  const [theme, setTheme] = useState<ResolvedTheme>(() => resolveTheme())

  useEffect(() => watchSystemTheme(() => setTheme(resolveTheme())), [])

  const next: ResolvedTheme = theme === 'dark' ? 'light' : 'dark'
  return (
    <Button
      variant="ghost"
      size="icon-sm"
      onClick={() => {
        setThemeSetting(next)
        setTheme(next)
      }}
      aria-label={next === 'dark' ? '切換為深色模式' : '切換為淺色模式'}
      title={next === 'dark' ? '切換為深色模式' : '切換為淺色模式'}
    >
      {theme === 'dark' ? <Sun /> : <Moon />}
    </Button>
  )
}
