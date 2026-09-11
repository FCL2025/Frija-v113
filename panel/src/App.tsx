import { Suspense, lazy, useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import { LoaderCircle, LogOut, Users } from 'lucide-react'
import { AppSidebar } from '@/components/app-sidebar'
import { LoginCard } from '@/components/login-card'
import { ThemeToggle } from '@/components/theme-toggle'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { SidebarInset, SidebarProvider, SidebarTrigger } from '@/components/ui/sidebar'
import { ApiError, apiFetch } from '@/lib/api'
import { clearToken, getToken, subscribeToken } from '@/lib/auth'
import { formatNumber } from '@/lib/format'
import { PollingContext, type PollingState } from '@/lib/polling'
import { queryClient } from '@/lib/query'
import { usePing, useStatus } from '@/lib/queries'
import { SECTIONS, sectionFromHash, type SectionId } from '@/lib/sections'
import type { PingResponse } from '@/lib/types'
import { ActionsSection } from '@/sections/actions'
import { ChannelsSection } from '@/sections/channels'
import { DashboardSection } from '@/sections/dashboard'
import { LogsSection } from '@/sections/logs'
import { PlayersSection } from '@/sections/players'
import { SettingsSection } from '@/sections/settings'

// 腳本編輯器帶著 CodeMirror，佔了一半以上的 bundle；只有點進去才載入。
const ScriptsSection = lazy(() => import('@/sections/scripts').then((m) => ({ default: m.ScriptsSection })))

export default function App() {
  const token = useSyncExternalStore(subscribeToken, getToken)
  // 已用 /api/ping 驗證過的 token；跟目前 token 不同就代表要重新驗證
  const [verifiedToken, setVerifiedToken] = useState<string | null>(null)

  // 開頁時若已有存好的 token，先打一次 /api/ping 確認還有效。
  // 401 會由 apiFetch 清掉 token；網路失敗或其他錯誤仍進入主畫面，由各區塊顯示錯誤。
  useEffect(() => {
    if (!token || verifiedToken === token) return
    let cancelled = false
    apiFetch<PingResponse>('/api/ping')
      .then(() => {
        if (!cancelled) setVerifiedToken(token)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        if (err instanceof ApiError && err.isUnauthorized) return
        setVerifiedToken(token)
      })
    return () => {
      cancelled = true
    }
  }, [token, verifiedToken])

  // 登出（或任何一次 401）後把快取清掉，下次登入不會閃出舊資料。
  useEffect(() => {
    if (!token) {
      setVerifiedToken(null)
      queryClient.clear()
    }
  }, [token])

  if (!token) {
    return <LoginCard onLoggedIn={() => setVerifiedToken(getToken())} />
  }

  if (verifiedToken !== token) {
    return (
      <div className="flex min-h-svh items-center justify-center gap-2 text-sm text-muted-foreground">
        <LoaderCircle className="size-4 animate-spin" aria-hidden />
        驗證 Token 中…
      </div>
    )
  }

  return <Shell onLogout={clearToken} />
}

function Shell({ onLogout }: { onLogout: () => void }) {
  const [section, setSection] = useState<SectionId>(() => sectionFromHash())
  const [paused, setPaused] = useState(false)

  useEffect(() => {
    const onHash = () => setSection(sectionFromHash())
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  const select = useCallback((id: SectionId) => {
    setSection(id)
    if (location.hash !== `#${id}`) location.hash = id
  }, [])

  const polling = useMemo<PollingState>(() => ({ paused, setPaused }), [paused])

  const status = useStatus()
  const ping = usePing()
  const serverName = status.data?.serverName ?? ping.data?.serverName
  const meta = SECTIONS.find((s) => s.id === section) ?? SECTIONS[0]

  useEffect(() => {
    document.title = `${meta.label} · TMS113 管理面板`
  }, [meta.label])

  return (
    <PollingContext.Provider value={polling}>
      <SidebarProvider>
        <AppSidebar
          active={section}
          onSelect={select}
          serverName={serverName}
          version={ping.data ? `${ping.data.version}.${ping.data.patch}` : undefined}
        />
        <SidebarInset>
          <header className="sticky top-0 z-10 flex h-14 shrink-0 items-center gap-2 border-b bg-background/95 px-4 backdrop-blur supports-[backdrop-filter]:bg-background/80">
            <SidebarTrigger className="-ml-1" />
            <Separator orientation="vertical" className="mr-1 data-[orientation=vertical]:h-4" />
            <div className="flex min-w-0 flex-1 items-center gap-2">
              <span className="truncate text-sm font-medium">{serverName ?? 'TMS113 管理面板'}</span>
              <OnlineBadge
                online={status.data?.online}
                offline={status.isError && status.error instanceof ApiError && status.error.isNetworkError}
              />
            </div>
            <ThemeToggle />
            <Button variant="outline" size="sm" onClick={onLogout}>
              <LogOut data-icon="inline-start" aria-hidden />
              登出
            </Button>
          </header>
          <div className="flex flex-1 flex-col gap-4 p-4 md:p-6">
            {section === 'dashboard' && <DashboardSection />}
            {section === 'channels' && <ChannelsSection />}
            {section === 'players' && <PlayersSection />}
            {section === 'logs' && <LogsSection />}
            {section === 'actions' && <ActionsSection />}
            {section === 'settings' && <SettingsSection />}
            {section === 'scripts' && (
              <Suspense fallback={<div className="p-4 text-sm text-muted-foreground">載入編輯器…</div>}>
                <ScriptsSection />
              </Suspense>
            )}
          </div>
        </SidebarInset>
      </SidebarProvider>
    </PollingContext.Provider>
  )
}

function OnlineBadge({ online, offline }: { online: number | undefined; offline: boolean }) {
  if (offline) {
    return (
      <Badge variant="destructive" title="無法連線到伺服器">
        離線
      </Badge>
    )
  }
  return (
    <Badge variant="secondary" className="tabular-nums" title="World.getConnected() 的即時人數">
      <Users aria-hidden />
      {online === undefined ? '線上 — 人' : `線上 ${formatNumber(online)} 人`}
    </Badge>
  )
}
