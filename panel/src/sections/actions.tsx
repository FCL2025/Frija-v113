import { useEffect, useState, type FormEvent } from 'react'
import { LoaderCircle, Megaphone, Power, RefreshCw, Save, Send, TrendingUp } from 'lucide-react'
import { Outcome } from '@/components/outcome'
import { PageHeader } from '@/components/page-header'
import { QueryError } from '@/components/query-error'
import { StatusBadge } from '@/components/status-badge'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import { errorMessage } from '@/lib/api'
import { formatNumber } from '@/lib/format'
import { usePolling } from '@/lib/polling'
import {
  useBroadcast,
  useChannels,
  useReload,
  useServerMessage,
  useSetRates,
  useShutdown,
  useStatus,
} from '@/lib/queries'
import type { BroadcastType, RatesRequest, ReloadResponse, ReloadTarget } from '@/lib/types'

export function ActionsSection() {
  return (
    <div className="flex flex-col gap-4">
      <PageHeader title="即時操作" description="按下去會馬上影響線上玩家的動作" />
      <div className="grid gap-4 xl:grid-cols-2">
        <RatesCard />
        <NoticeCard />
        <ReloadCard />
        <ShutdownCard />
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------------- */
/* 倍率                                                                     */
/* ---------------------------------------------------------------------- */

const RATE_FIELDS: { key: keyof RatesRequest; label: string; channelKey: 'expRate' | 'mesoRate' | 'dropRate' | 'cashRate' }[] = [
  { key: 'exp', label: '經驗', channelKey: 'expRate' },
  { key: 'meso', label: '楓幣', channelKey: 'mesoRate' },
  { key: 'drop', label: '掉寶', channelKey: 'dropRate' },
  { key: 'cash', label: '點數', channelKey: 'cashRate' },
]

function RatesCard() {
  const channels = useChannels()
  const mutation = useSetRates()
  const [draft, setDraft] = useState<Record<keyof RatesRequest, string>>({ exp: '', meso: '', drop: '', cash: '' })
  const [touched, setTouched] = useState(false)

  // 沒動過表單時跟著線上值走（頻道 1 代表全部）
  const first = channels.data?.[0]
  useEffect(() => {
    if (!first || touched) return
    setDraft({
      exp: String(first.expRate),
      meso: String(first.mesoRate),
      drop: String(first.dropRate),
      cash: String(first.cashRate),
    })
  }, [first, touched])

  const inconsistent =
    channels.data && channels.data.length > 1
      ? RATE_FIELDS.some((f) => new Set(channels.data!.map((c) => c[f.channelKey])).size > 1)
      : false

  function submit(e: FormEvent) {
    e.preventDefault()
    const body: RatesRequest = {}
    for (const f of RATE_FIELDS) {
      const n = Number(draft[f.key])
      if (!Number.isInteger(n) || n < 1) return
      body[f.key] = n
    }
    mutation.mutate(body, { onSuccess: () => setTouched(false) })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <TrendingUp className="size-4" aria-hidden />
          倍率
        </CardTitle>
        <CardDescription>
          套用到每個頻道的 setExpRate / setMesoRate / setDropRate / setCashRate，並同時寫回 Settings.ini。
          線上玩家下一次獲得經驗就是新倍率，不用重登。
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="flex flex-col gap-4">
          <QueryError error={channels.error} />
          {inconsistent && (
            <Alert>
              <AlertTitle>各頻道倍率不一致</AlertTitle>
              <AlertDescription>表單顯示的是頻道 1 的值；套用後所有頻道會一致。</AlertDescription>
            </Alert>
          )}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {RATE_FIELDS.map((f) => (
              <div key={f.key} className="flex flex-col gap-1.5">
                <Label htmlFor={`rate-${f.key}`}>{f.label}</Label>
                <Input
                  id={`rate-${f.key}`}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={100000}
                  required
                  value={draft[f.key]}
                  onChange={(e) => {
                    setTouched(true)
                    setDraft((d) => ({ ...d, [f.key]: e.target.value }))
                  }}
                  className="tabular-nums"
                />
              </div>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <Button type="submit" disabled={mutation.isPending || !channels.data}>
              {mutation.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Save data-icon="inline-start" />}
              套用並保存
            </Button>
            {touched && (
              <Button type="button" variant="ghost" size="sm" onClick={() => setTouched(false)}>
                還原成線上值
              </Button>
            )}
          </div>
          {mutation.isError && (
            <Alert variant="destructive">
              <AlertTitle>套用失敗</AlertTitle>
              <AlertDescription>{errorMessage(mutation.error)}</AlertDescription>
            </Alert>
          )}
          {mutation.data && (
            <Outcome
              saved={mutation.data.saved}
              applied={{ ok: mutation.data.applied.ok, mode: 'live' }}
            />
          )}
        </form>
      </CardContent>
    </Card>
  )
}

/* ---------------------------------------------------------------------- */
/* 公告                                                                     */
/* ---------------------------------------------------------------------- */

const BROADCAST_TYPES: { value: BroadcastType; label: string; hint: string }[] = [
  { value: 'notice', label: '[公告]', hint: '聊天視窗裡的公告行' },
  { value: 'popup', label: '對話框', hint: '彈出視窗，玩家要按確定' },
  { value: 'scroll', label: '跑馬燈', hint: '畫面頂端的捲動訊息' },
  { value: 'pink', label: '粉紅文字', hint: '聊天視窗的粉紅色訊息' },
  { value: 'blue', label: '淺藍文字', hint: '聊天視窗的淺藍色訊息' },
]

function NoticeCard() {
  const status = useStatus()
  const broadcast = useBroadcast()
  const serverMessage = useServerMessage()
  const channels = useChannels()

  const [type, setType] = useState<BroadcastType>('notice')
  const [message, setMessage] = useState('')

  const [channelMessage, setChannelMessage] = useState('')
  const [saveChannelMessage, setSaveChannelMessage] = useState(true)
  const [channelTouched, setChannelTouched] = useState(false)
  const current = channels.data?.[0]?.serverMessage
  useEffect(() => {
    if (current !== undefined && !channelTouched) setChannelMessage(current)
  }, [current, channelTouched])

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Megaphone className="size-4" aria-hidden />
          公告
        </CardTitle>
        <CardDescription>
          目前線上 {status.data ? formatNumber(status.data.online) : '—'} 人。兩種公告都是按下按鈕才送出，不會自動儲存。
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <form
          className="flex flex-col gap-3"
          onSubmit={(e) => {
            e.preventDefault()
            if (!message.trim()) return
            broadcast.mutate({ type, message: message.trim() }, { onSuccess: () => setMessage('') })
          }}
        >
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="broadcast-message">一次性廣播</Label>
            <Textarea
              id="broadcast-message"
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              maxLength={400}
              rows={2}
              placeholder="送給所有線上玩家的訊息（上限 400 字）"
            />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Select value={type} onValueChange={(v) => setType(v as BroadcastType)}>
              <SelectTrigger size="sm" className="w-36" aria-label="公告樣式">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {BROADCAST_TYPES.map((t) => (
                  <SelectItem key={t.value} value={t.value}>
                    {t.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <span className="text-xs text-muted-foreground">
              {BROADCAST_TYPES.find((t) => t.value === type)?.hint}
            </span>
            <Button type="submit" size="sm" className="ml-auto" disabled={broadcast.isPending || !message.trim()}>
              {broadcast.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Send data-icon="inline-start" />}
              送出
            </Button>
          </div>
          {broadcast.isError && (
            <Alert variant="destructive">
              <AlertTitle>送出失敗</AlertTitle>
              <AlertDescription>{errorMessage(broadcast.error)}</AlertDescription>
            </Alert>
          )}
          {broadcast.data && (
            <StatusBadge tone="good" className="self-start">
              已送給 {formatNumber(broadcast.data.recipients)} 位玩家
            </StatusBadge>
          )}
        </form>

        <form
          className="flex flex-col gap-3 border-t pt-4"
          onSubmit={(e) => {
            e.preventDefault()
            serverMessage.mutate(
              { message: channelMessage, save: saveChannelMessage },
              { onSuccess: () => setChannelTouched(false) },
            )
          }}
        >
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="server-message">頻道公告（tms.ServerMessage）</Label>
            <Input
              id="server-message"
              value={channelMessage}
              onChange={(e) => {
                setChannelTouched(true)
                setChannelMessage(e.target.value)
              }}
              placeholder="玩家進入頻道時看到的訊息"
            />
            <p className="text-xs text-muted-foreground">
              setServerMessage() 會在呼叫的當下對每個頻道廣播一次，所以這是「送出」而不是「儲存」。
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-2 text-sm">
              <Switch checked={saveChannelMessage} onCheckedChange={setSaveChannelMessage} />
              同時寫入 Settings.ini
            </label>
            <Button type="submit" size="sm" className="ml-auto" disabled={serverMessage.isPending}>
              {serverMessage.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Send data-icon="inline-start" />}
              送出頻道公告
            </Button>
          </div>
          {serverMessage.isError && (
            <Alert variant="destructive">
              <AlertTitle>送出失敗</AlertTitle>
              <AlertDescription>{errorMessage(serverMessage.error)}</AlertDescription>
            </Alert>
          )}
          {serverMessage.data && (
            <Outcome
              saved={serverMessage.data.saved}
              applied={{ ok: serverMessage.data.applied.ok, mode: 'live' }}
              applyWord="送出"
            />
          )}
        </form>
      </CardContent>
    </Card>
  )
}

/* ---------------------------------------------------------------------- */
/* Reload                                                                   */
/* ---------------------------------------------------------------------- */

const RELOADS: { target: ReloadTarget; label: string; hint: string }[] = [
  { target: 'ops', label: '封包代碼', hint: '重新讀 Libs/opcode/recv.ini 與 send.ini' },
  { target: 'drops', label: '掉寶', hint: '清掉怪物與反應物的掉寶快取，下次打怪重讀資料庫' },
  { target: 'portal', label: '傳送點', hint: '清掉傳送點腳本快取' },
  { target: 'shops', label: '商店', hint: '清掉 NPC 商店快取，下次開店重讀資料庫' },
  { target: 'quests', label: '任務', hint: '清掉任務資料快取' },
  { target: 'events', label: '事件腳本', hint: '每個頻道重新編譯 tms.Events 的腳本（約 2 秒）' },
  { target: 'ipmonitor', label: 'IP 監控', hint: '重新讀 Logs/LogIPs.txt' },
]

function ReloadCard() {
  const reload = useReload()
  const [results, setResults] = useState<Partial<Record<ReloadTarget, ReloadResponse | { error: string }>>>({})
  const [busy, setBusy] = useState<ReloadTarget | null>(null)

  function run(target: ReloadTarget) {
    setBusy(target)
    reload.mutate(target, {
      onSuccess: (r) => setResults((s) => ({ ...s, [target]: r })),
      onError: (err) => setResults((s) => ({ ...s, [target]: { error: errorMessage(err) } })),
      onSettled: () => setBusy(null),
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <RefreshCw className="size-4" aria-hidden />
          重新載入
        </CardTitle>
        <CardDescription>與遊戲內 !reload… 指令相同的動作，直接呼叫對應的快取清除。</CardDescription>
      </CardHeader>
      <CardContent>
        <ul className="flex flex-col divide-y">
          {RELOADS.map((r) => {
            const res = results[r.target]
            return (
              <li key={r.target} className="flex flex-wrap items-center gap-3 py-2">
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium">{r.label}</div>
                  <div className="text-xs text-muted-foreground">{r.hint}</div>
                  {res && 'error' in res && (
                    <div className="mt-1 text-xs text-red-600 dark:text-red-400">{res.error}</div>
                  )}
                  {res && !('error' in res) && (
                    <div className="mt-1 flex flex-wrap items-center gap-2">
                      <StatusBadge tone={res.ok ? 'good' : 'critical'}>
                        {res.ok ? '完成' : '部分失敗'} · {formatNumber(res.elapsedMs)} ms
                      </StatusBadge>
                      {res.errors.map((e, i) => (
                        <span key={i} className="text-xs text-red-600 dark:text-red-400">
                          {e}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy !== null}
                  onClick={() => run(r.target)}
                >
                  {busy === r.target ? (
                    <LoaderCircle className="animate-spin" data-icon="inline-start" />
                  ) : (
                    <RefreshCw data-icon="inline-start" />
                  )}
                  重載
                </Button>
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
  )
}

/* ---------------------------------------------------------------------- */
/* 關機                                                                     */
/* ---------------------------------------------------------------------- */

const CONFIRM_WORD = '關機'

function ShutdownCard() {
  const status = useStatus()
  const shutdown = useShutdown()
  const { setPaused } = usePolling()
  const [typed, setTyped] = useState('')
  const [open, setOpen] = useState(false)

  function confirm() {
    shutdown.mutate(undefined, {
      onSuccess: () => {
        // 伺服器幾秒內就會消失，再輪詢只會滿畫面「無法連線」
        setPaused(true)
        setOpen(false)
      },
    })
  }

  const online = status.data?.online ?? 0
  const alreadyDown = status.data?.shutdown || shutdown.data?.ok

  return (
    <Card className="border-red-500/30">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Power className="size-4" aria-hidden />
          關閉伺服器
        </CardTitle>
        <CardDescription>
          走 ShutdownServer 的正常流程：踢下線並儲存所有玩家 → 存公會 / 聯盟 / 家族 → 關閉資料庫 → 結束程序。
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Alert>
          <AlertTitle>面板無法重新啟動伺服器</AlertTitle>
          <AlertDescription>
            面板活在遊戲伺服器的 JVM 裡，關機後會一起消失。之後要在伺服器主機上重新執行 Launcher.bat。
          </AlertDescription>
        </Alert>
        {alreadyDown ? (
          <StatusBadge tone="critical" icon={Power} className="self-start">
            伺服器關機中…
          </StatusBadge>
        ) : (
          <AlertDialog open={open} onOpenChange={(o) => { setOpen(o); if (!o) setTyped('') }}>
            <AlertDialogTrigger asChild>
              <Button variant="destructive" className="self-start">
                <Power data-icon="inline-start" />
                關閉伺服器…
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>確定要關閉伺服器？</AlertDialogTitle>
                <AlertDialogDescription asChild>
                  <div className="flex flex-col gap-3">
                    <p>
                      目前線上 <strong>{formatNumber(online)}</strong> 人，全部會被踢下線並儲存。
                      關機後面板也會消失，必須在主機上手動重啟。
                    </p>
                    <div className="flex flex-col gap-1.5">
                      <Label htmlFor="shutdown-confirm">請輸入「{CONFIRM_WORD}」以確認</Label>
                      <Input
                        id="shutdown-confirm"
                        value={typed}
                        onChange={(e) => setTyped(e.target.value)}
                        autoComplete="off"
                      />
                    </div>
                    {shutdown.isError && (
                      <p className="text-sm text-red-600 dark:text-red-400">{errorMessage(shutdown.error)}</p>
                    )}
                  </div>
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>取消</AlertDialogCancel>
                <AlertDialogAction
                  disabled={typed.trim() !== CONFIRM_WORD || shutdown.isPending}
                  onClick={(e) => {
                    e.preventDefault()
                    confirm()
                  }}
                  className="bg-destructive text-white hover:bg-destructive/90"
                >
                  {shutdown.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Power data-icon="inline-start" />}
                  關閉伺服器
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        )}
      </CardContent>
    </Card>
  )
}
