import { useState, type FormEvent } from 'react'
import { KeyRound, LoaderCircle } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ThemeToggle } from '@/components/theme-toggle'
import { ApiError, apiFetch, errorMessage } from '@/lib/api'
import { setToken } from '@/lib/auth'
import type { PingResponse } from '@/lib/types'

export interface LoginCardProps {
  onLoggedIn: (ping: PingResponse) => void
}

/** 貼上伺服器 console 印出的 token，用 /api/ping 驗證後才存起來 */
export function LoginCard({ onLoggedIn }: LoginCardProps) {
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const token = value.trim()
    if (!token) {
      setError('請輸入 Token')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const ping = await apiFetch<PingResponse>('/api/ping', { token, keepTokenOn401: true })
      setToken(token)
      onLoggedIn(ping)
    } catch (err) {
      if (err instanceof ApiError && err.isUnauthorized) setError('Token 錯誤')
      else setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-svh items-center justify-center bg-background p-4">
      <div className="fixed top-3 right-3">
        <ThemeToggle />
      </div>
      <Card className="w-full max-w-sm">
        <form onSubmit={submit}>
          <CardHeader className="pb-4">
            <CardTitle className="flex items-center gap-2 text-lg">
              <KeyRound className="size-5" aria-hidden />
              Frija v113 管理面板
            </CardTitle>
            <CardDescription>
              請貼上伺服器啟動時印在 console 的存取 Token。
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            <div className="grid gap-2">
              <Label htmlFor="token">Token</Label>
              <Input
                id="token"
                type="password"
                autoComplete="off"
                autoFocus
                spellCheck={false}
                value={value}
                onChange={(e) => setValue(e.target.value)}
                disabled={busy}
                className="font-mono"
              />
            </div>
            {error && (
              <Alert variant="destructive">
                <AlertTitle>登入失敗</AlertTitle>
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
          </CardContent>
          <CardFooter className="pt-4">
            <Button type="submit" className="w-full" disabled={busy}>
              {busy && <LoaderCircle className="animate-spin" data-icon="inline-start" />}
              {busy ? '驗證中…' : '登入'}
            </Button>
          </CardFooter>
        </form>
      </Card>
    </div>
  )
}
