import { useEffect, useMemo, useState, type FormEvent } from 'react'
import type { ColumnDef } from '@tanstack/react-table'
import { AlertTriangle, ChevronLeft, ChevronRight, LoaderCircle, Plus, Save, Search, Trash2, UserX } from 'lucide-react'
import { DataTable } from '@/components/data-table'
import { PageHeader } from '@/components/page-header'
import { PollStatus } from '@/components/poll-status'
import { QueryError } from '@/components/query-error'
import { StatusBadge } from '@/components/status-badge'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { errorMessage } from '@/lib/api'
import { formatDateTime, formatNumber } from '@/lib/format'
import {
  useAccount,
  useAccounts,
  useAddBan,
  useBans,
  useCharacter,
  useCharacters,
  useKickAccount,
  useKickCharacter,
  useRemoveBan,
  useUpdateAccount,
  useUpdateCharacter,
} from '@/lib/queries'
import type { AccountPatch, AccountRow, CharacterPatch, CharacterRow } from '@/lib/types'

type Tab = 'accounts' | 'characters' | 'ip' | 'mac'
const PAGE_SIZE = 50

export function AccountsSection() {
  const [tab, setTab] = useState<Tab>('accounts')
  const [openAccount, setOpenAccount] = useState<number | null>(null)
  const [openCharacter, setOpenCharacter] = useState<number | null>(null)

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title="帳號與角色" description="accounts / characters / ipbans / macbans" />
      <Tabs value={tab} onValueChange={(v) => setTab(v as Tab)}>
        <TabsList>
          <TabsTrigger value="accounts">帳號</TabsTrigger>
          <TabsTrigger value="characters">角色</TabsTrigger>
          <TabsTrigger value="ip">IP 封鎖</TabsTrigger>
          <TabsTrigger value="mac">MAC 封鎖</TabsTrigger>
        </TabsList>
      </Tabs>
      {tab === 'accounts' && <AccountsTab onOpen={setOpenAccount} />}
      {tab === 'characters' && <CharactersTab onOpen={setOpenCharacter} />}
      {tab === 'ip' && <BansTab kind="ip" />}
      {tab === 'mac' && <BansTab kind="mac" />}

      <AccountDialog id={openAccount} onClose={() => setOpenAccount(null)} onOpenCharacter={setOpenCharacter} />
      <CharacterDialog id={openCharacter} onClose={() => setOpenCharacter(null)} />
    </div>
  )
}

/* ---------------------------------------------------------------------- */
/* 共用：搜尋列與分頁                                                        */
/* ---------------------------------------------------------------------- */

function useDebounced(value: string, ms = 350) {
  const [v, setV] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return v
}

function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size))
  return (
    <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
      <span className="tabular-nums">
        共 {formatNumber(total)} 筆 · 第 {page} / {pages} 頁
      </span>
      <div className="flex items-center gap-1">
        <Button size="icon-xs" variant="outline" disabled={page <= 1} onClick={() => onPage(page - 1)} aria-label="上一頁">
          <ChevronLeft />
        </Button>
        <Button size="icon-xs" variant="outline" disabled={page >= pages} onClick={() => onPage(page + 1)} aria-label="下一頁">
          <ChevronRight />
        </Button>
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------------- */
/* 帳號                                                                     */
/* ---------------------------------------------------------------------- */

function AccountsTab({ onOpen }: { onOpen: (id: number) => void }) {
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState('')
  const [page, setPage] = useState(1)
  const dq = useDebounced(q)
  useEffect(() => setPage(1), [dq, filter])
  const list = useAccounts(dq, filter, page, PAGE_SIZE)

  const columns = useMemo<ColumnDef<AccountRow, unknown>[]>(
    () => [
      { accessorKey: 'id', header: 'ID', meta: { className: 'tabular-nums text-right w-16' } },
      {
        accessorKey: 'name',
        header: '帳號',
        cell: ({ row }) => (
          <button type="button" className="font-medium hover:underline" onClick={() => onOpen(row.original.id)}>
            {row.original.name}
          </button>
        ),
      },
      {
        id: 'state',
        header: '狀態',
        accessorFn: (r) => (r.online ? 2 : r.banned > 0 ? 1 : 0),
        cell: ({ row }) => {
          const a = row.original
          return (
            <div className="flex flex-wrap gap-1">
              {a.online && (
                <StatusBadge tone="good">
                  線上 · {a.onlineCharacter} · 頻道 {a.onlineChannel}
                </StatusBadge>
              )}
              {a.banned > 0 && <StatusBadge tone="critical">封鎖</StatusBadge>}
              {a.tempban && <StatusBadge tone="warning">暫封至 {a.tempban}</StatusBadge>}
              {a.gm > 0 && <StatusBadge tone="warning">GM</StatusBadge>}
              {!a.online && a.banned <= 0 && !a.tempban && a.gm <= 0 && <span className="text-xs text-muted-foreground">—</span>}
            </div>
          )
        },
      },
      { accessorKey: 'email', header: 'Email', cell: ({ getValue }) => <span className="text-xs">{(getValue() as string | null) ?? ''}</span> },
      { accessorKey: 'ACash', header: '點數', meta: { className: 'tabular-nums text-right' }, cell: ({ getValue }) => formatNumber(getValue() as number) },
      { accessorKey: 'mPoints', header: '楓點', meta: { className: 'tabular-nums text-right' }, cell: ({ getValue }) => formatNumber(getValue() as number) },
      { accessorKey: 'sessionIP', header: '最近 IP', cell: ({ getValue }) => <span className="font-mono text-xs">{(getValue() as string | null) ?? ''}</span> },
      { accessorKey: 'lastlogin', header: '最後登入', cell: ({ getValue }) => <span className="text-xs tabular-nums">{getValue() ? formatDateTime(getValue() as number) : '—'}</span> },
    ],
    [onOpen],
  )

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-64 flex-1">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="帳號名稱 / Email / IP / ID" className="pl-8" />
        </div>
        <Select value={filter || 'all'} onValueChange={(v) => setFilter(v === 'all' ? '' : v)}>
          <SelectTrigger size="sm" className="w-36" aria-label="篩選">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">全部</SelectItem>
            <SelectItem value="banned">已封鎖</SelectItem>
            <SelectItem value="gm">GM 帳號</SelectItem>
            <SelectItem value="online">loggedin &gt; 0</SelectItem>
          </SelectContent>
        </Select>
        <PollStatus updatedAt={list.dataUpdatedAt} fetching={list.isFetching} onRefresh={() => void list.refetch()} />
      </div>
      <QueryError error={list.error} />
      <DataTable
        columns={columns}
        data={list.data?.items ?? []}
        getRowId={(r) => String(r.id)}
        loading={list.isPending}
        emptyMessage="沒有符合的帳號"
      />
      {list.data && <Pager page={list.data.page} size={list.data.size} total={list.data.total} onPage={setPage} />}
    </div>
  )
}

function AccountDialog({
  id,
  onClose,
  onOpenCharacter,
}: {
  id: number | null
  onClose: () => void
  onOpenCharacter: (id: number) => void
}) {
  const detail = useAccount(id)
  const update = useUpdateAccount()
  const kick = useKickAccount()
  const a = detail.data?.account
  const [draft, setDraft] = useState<Record<string, string>>({})
  useEffect(() => {
    if (!a) return
    setDraft({
      banned: String(a.banned),
      banreason: a.banreason ?? '',
      tempban: a.tempban ?? '',
      gm: String(a.gm),
      email: a.email ?? '',
      ACash: String(a.ACash),
      mPoints: String(a.mPoints),
      points: String(a.points),
      vpoints: String(a.vpoints),
    })
  }, [a])
  // 只在換一個帳號時清掉上次的結果；存檔後的重抓會換掉 a，但結果要留著給人看
  useEffect(() => {
    update.reset()
    kick.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!a) return
    const patch: AccountPatch = {}
    if (draft.banned !== String(a.banned)) patch.banned = Number(draft.banned)
    if (draft.banreason !== (a.banreason ?? '')) patch.banreason = draft.banreason
    if (draft.tempban !== (a.tempban ?? '')) patch.tempban = draft.tempban || null
    if (draft.gm !== String(a.gm)) patch.gm = Number(draft.gm)
    if (draft.email !== (a.email ?? '')) patch.email = draft.email
    for (const k of ['ACash', 'mPoints', 'points', 'vpoints'] as const) {
      if (draft[k] !== String(a[k])) patch[k] = Number(draft[k])
    }
    if (Object.keys(patch).length === 0) return
    update.mutate({ id: a.id, patch })
  }

  const field = (key: string, label: string, props: React.ComponentProps<typeof Input> = {}) => (
    <div className="flex flex-col gap-1">
      <Label htmlFor={`acc-${key}`} className="text-xs">
        {label}
      </Label>
      <Input id={`acc-${key}`} value={draft[key] ?? ''} onChange={(e) => setDraft((d) => ({ ...d, [key]: e.target.value }))} className="h-8 text-xs" {...props} />
    </div>
  )

  return (
    <Dialog open={id !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            帳號 {a ? <span className="font-mono">{a.name}</span> : '…'}
            {a && <span className="text-sm font-normal text-muted-foreground">#{a.id}</span>}
            {a?.online && (
              <StatusBadge tone="good">
                線上 · {a.onlineCharacter} · 頻道 {a.onlineChannel}
              </StatusBadge>
            )}
          </DialogTitle>
          <DialogDescription>
            封鎖、GM 與 Email 只在登入時讀取；點數類欄位在帳號線上時會同時寫進記憶體中的角色，讓下一次自動存檔不會蓋掉。
          </DialogDescription>
        </DialogHeader>
        <QueryError error={detail.error} />
        {a && (
          <form onSubmit={submit} className="flex flex-col gap-4">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
              <div className="flex flex-col gap-1">
                <Label className="text-xs">封鎖狀態</Label>
                <Select value={draft.banned ?? '0'} onValueChange={(v) => setDraft((d) => ({ ...d, banned: v }))}>
                  <SelectTrigger size="sm" className="h-8 text-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="0">未封鎖</SelectItem>
                    <SelectItem value="1">封鎖</SelectItem>
                    <SelectItem value="2">封鎖（自動）</SelectItem>
                    <SelectItem value="-1">下次登入自動解封</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              {field('banreason', '封鎖原因')}
              {field('tempban', '暫時封鎖至（yyyy-MM-dd HH:mm，留空解除）', { placeholder: '2030-01-01 12:00' })}
              <div className="flex flex-col gap-1">
                <Label className="text-xs">GM 帳號（可繞過封鎖）</Label>
                <Select value={draft.gm ?? '0'} onValueChange={(v) => setDraft((d) => ({ ...d, gm: v }))}>
                  <SelectTrigger size="sm" className="h-8 text-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="0">否</SelectItem>
                    <SelectItem value="1">是</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              {field('email', 'Email')}
              {field('ACash', '點數（ACash）', { type: 'number', inputMode: 'numeric' })}
              {field('mPoints', '楓葉點數（mPoints）', { type: 'number', inputMode: 'numeric' })}
              {field('points', 'points', { type: 'number', inputMode: 'numeric' })}
              {field('vpoints', 'vpoints', { type: 'number', inputMode: 'numeric' })}
            </div>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-muted-foreground sm:grid-cols-3">
              <dt>建立</dt>
              <dd className="col-span-1 tabular-nums sm:col-span-2">{a.createdat ? formatDateTime(a.createdat) : '—'}</dd>
              <dt>最後登入</dt>
              <dd className="col-span-1 tabular-nums sm:col-span-2">{a.lastlogin ? formatDateTime(a.lastlogin) : '—'}</dd>
              <dt>最近 IP</dt>
              <dd className="col-span-1 font-mono sm:col-span-2">{a.sessionIP ?? '—'}</dd>
              <dt>MAC</dt>
              <dd className="col-span-1 font-mono break-all sm:col-span-2">{a.macs ?? '—'}</dd>
            </dl>
            {update.isError && (
              <Alert variant="destructive">
                <AlertTitle>寫入失敗</AlertTitle>
                <AlertDescription>{errorMessage(update.error)}</AlertDescription>
              </Alert>
            )}
            {update.data && (
              <div className="flex flex-wrap items-center gap-2 text-xs">
                <StatusBadge tone="good">已寫入 {update.data.saved.columns.join(', ')}</StatusBadge>
                {update.data.applied.live.length > 0 && <StatusBadge tone="good">已同步到線上角色：{update.data.applied.live.join(', ')}</StatusBadge>}
                {update.data.applied.nextLogin.length > 0 && <StatusBadge tone="warning">下次登入生效：{update.data.applied.nextLogin.join(', ')}</StatusBadge>}
                {update.data.kicked && <StatusBadge tone="critical" icon={UserX}>已踢下線</StatusBadge>}
              </div>
            )}
            <DialogFooter className="gap-2 sm:justify-between">
              <div className="flex items-center gap-2">
                {a.online && (
                  <Button type="button" variant="outline" size="sm" disabled={kick.isPending} onClick={() => kick.mutate(a.id)}>
                    <UserX data-icon="inline-start" />
                    踢下線
                  </Button>
                )}
                {kick.isError && <span className="text-xs text-red-600 dark:text-red-400">{errorMessage(kick.error)}</span>}
                {kick.data && <span className="text-xs text-muted-foreground">已關閉 {kick.data.character} 的連線</span>}
              </div>
              <Button type="submit" size="sm" disabled={update.isPending}>
                {update.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Save data-icon="inline-start" />}
                儲存
              </Button>
            </DialogFooter>
          </form>
        )}
        {detail.data && (
          <div className="border-t pt-3">
            <div className="mb-2 text-xs font-medium text-muted-foreground">角色（{detail.data.characters.length}）</div>
            <ul className="flex flex-col divide-y text-sm">
              {detail.data.characters.map((c) => (
                <li key={c.id} className="flex items-center gap-3 py-1.5">
                  <button type="button" className="font-medium hover:underline" onClick={() => onOpenCharacter(c.id)}>
                    {c.name}
                  </button>
                  <span className="text-xs text-muted-foreground">
                    Lv.{c.level} {c.jobName} · #{c.id}
                  </span>
                  {c.online && <StatusBadge tone="good">線上 · 頻道 {c.onlineChannel}</StatusBadge>}
                </li>
              ))}
              {detail.data.characters.length === 0 && <li className="py-2 text-xs text-muted-foreground">沒有角色</li>}
            </ul>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

/* ---------------------------------------------------------------------- */
/* 角色                                                                     */
/* ---------------------------------------------------------------------- */

function CharactersTab({ onOpen }: { onOpen: (id: number) => void }) {
  const [q, setQ] = useState('')
  const [page, setPage] = useState(1)
  const dq = useDebounced(q)
  useEffect(() => setPage(1), [dq])
  const list = useCharacters(dq, page, PAGE_SIZE)

  const columns = useMemo<ColumnDef<CharacterRow, unknown>[]>(
    () => [
      { accessorKey: 'id', header: 'ID', meta: { className: 'tabular-nums text-right w-16' } },
      {
        accessorKey: 'name',
        header: '角色',
        cell: ({ row }) => (
          <button type="button" className="font-medium hover:underline" onClick={() => onOpen(row.original.id)}>
            {row.original.name}
          </button>
        ),
      },
      { accessorKey: 'level', header: '等級', meta: { className: 'tabular-nums text-right' } },
      { accessorKey: 'jobName', header: '職業' },
      { accessorKey: 'meso', header: '楓幣', meta: { className: 'tabular-nums text-right' }, cell: ({ getValue }) => formatNumber(getValue() as number) },
      { accessorKey: 'map', header: '地圖', meta: { className: 'tabular-nums' } },
      { accessorKey: 'accountId', header: '帳號 ID', meta: { className: 'tabular-nums text-right' } },
      {
        id: 'online',
        header: '狀態',
        accessorFn: (r) => (r.online ? 1 : 0),
        cell: ({ row }) =>
          row.original.online ? <StatusBadge tone="good">線上 · 頻道 {row.original.onlineChannel}</StatusBadge> : <span className="text-xs text-muted-foreground">離線</span>,
      },
    ],
    [onOpen],
  )

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-64 flex-1">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="角色名稱 / 角色 ID / 帳號 ID" className="pl-8" />
        </div>
        <PollStatus updatedAt={list.dataUpdatedAt} fetching={list.isFetching} onRefresh={() => void list.refetch()} />
      </div>
      <QueryError error={list.error} />
      <DataTable columns={columns} data={list.data?.items ?? []} getRowId={(r) => String(r.id)} loading={list.isPending} emptyMessage="沒有符合的角色" />
      {list.data && <Pager page={list.data.page} size={list.data.size} total={list.data.total} onPage={setPage} />}
    </div>
  )
}

const CHARACTER_FIELDS: { key: keyof CharacterPatch; label: string }[] = [
  { key: 'level', label: '等級' },
  { key: 'exp', label: '經驗' },
  { key: 'job', label: '職業代碼' },
  { key: 'meso', label: '楓幣' },
  { key: 'fame', label: '人氣' },
  { key: 'ap', label: 'AP' },
  { key: 'str', label: 'STR' },
  { key: 'dex', label: 'DEX' },
  { key: 'int', label: 'INT' },
  { key: 'luk', label: 'LUK' },
  { key: 'hp', label: 'HP' },
  { key: 'maxhp', label: '最大 HP' },
  { key: 'mp', label: 'MP' },
  { key: 'maxmp', label: '最大 MP' },
  { key: 'map', label: '地圖' },
  { key: 'spawnpoint', label: '出生點' },
  { key: 'gm', label: 'GM 等級' },
  { key: 'hair', label: '髮型' },
  { key: 'face', label: '臉型' },
  { key: 'skincolor', label: '膚色' },
  { key: 'gender', label: '性別' },
  { key: 'beans', label: '豆豆' },
  { key: 'sp', label: 'SP（逗號分隔）' },
]

function CharacterDialog({ id, onClose }: { id: number | null; onClose: () => void }) {
  const detail = useCharacter(id)
  const update = useUpdateCharacter()
  const kick = useKickCharacter()
  const c = detail.data
  const [draft, setDraft] = useState<Record<string, string>>({})
  useEffect(() => {
    if (!c) return
    const d: Record<string, string> = {}
    for (const f of CHARACTER_FIELDS) d[f.key] = String(c[f.key])
    setDraft(d)
  }, [c])
  useEffect(() => {
    update.reset()
    kick.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!c) return
    const patch: Record<string, unknown> = {}
    for (const f of CHARACTER_FIELDS) {
      if (draft[f.key] !== String(c[f.key])) patch[f.key] = f.key === 'sp' ? draft[f.key] : Number(draft[f.key])
    }
    if (Object.keys(patch).length === 0) return
    update.mutate({ id: c.id, patch: patch as CharacterPatch })
  }

  return (
    <Dialog open={id !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            角色 {c ? c.name : '…'}
            {c && <span className="text-sm font-normal text-muted-foreground">#{c.id} · 帳號 #{c.accountId}</span>}
            {c?.online && <StatusBadge tone="good">線上 · 頻道 {c.onlineChannel}</StatusBadge>}
          </DialogTitle>
          <DialogDescription>
            線上角色會直接改記憶體裡的角色並推送更新給玩家（立即看得到），同時寫入資料庫；離線角色只寫資料庫，下次登入生效。
          </DialogDescription>
        </DialogHeader>
        <QueryError error={detail.error} />
        {c && (
          <form onSubmit={submit} className="flex flex-col gap-4">
            {c.online && (
              <Alert>
                <AlertTriangle />
                <AlertTitle>角色在線上（頻道 {c.onlineChannel}，地圖 {c.onlineMap}）</AlertTitle>
                <AlertDescription className="flex flex-wrap items-center gap-2">
                  儲存會即時套用到線上角色；改地圖或出生點會把角色傳送過去，性別要重新登入才看得到。
                  <Button type="button" size="xs" variant="outline" disabled={kick.isPending} onClick={() => kick.mutate(c.id)}>
                    <UserX data-icon="inline-start" />
                    踢下線
                  </Button>
                  {kick.data && <span>已關閉連線，幾秒後重新整理</span>}
                  {kick.isError && <span className="text-red-600 dark:text-red-400">{errorMessage(kick.error)}</span>}
                </AlertDescription>
              </Alert>
            )}
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              {CHARACTER_FIELDS.map((f) => (
                <div key={f.key} className="flex flex-col gap-1">
                  <Label htmlFor={`chr-${f.key}`} className="text-xs">
                    {f.label}
                  </Label>
                  <Input
                    id={`chr-${f.key}`}
                    value={draft[f.key] ?? ''}
                    onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))}
                    className="h-8 font-mono text-xs"
                    type={f.key === 'sp' ? 'text' : 'number'}
                    inputMode="numeric"
                  />
                </div>
              ))}
            </div>
            <div className="text-xs text-muted-foreground">
              職業 {c.jobName}（{c.job}） · 公會 {c.guildid || '—'} · 建立於 {c.createdate ? formatDateTime(c.createdate) : '—'}
            </div>
            {update.isError && (
              <Alert variant="destructive">
                <AlertTitle>寫入失敗</AlertTitle>
                <AlertDescription>{errorMessage(update.error)}</AlertDescription>
              </Alert>
            )}
            {update.data && (
              <div className="flex flex-wrap items-center gap-2 text-xs">
                <StatusBadge tone="good">已寫入 {update.data.saved.columns.join(', ')}</StatusBadge>
                {update.data.applied.live && update.data.applied.live.length > 0 && (
                  <StatusBadge tone="good">線上已生效：{update.data.applied.live.join(', ')}</StatusBadge>
                )}
                {update.data.applied.nextLogin && update.data.applied.nextLogin.length > 0 && (
                  <StatusBadge tone="warning">重新登入後可見：{update.data.applied.nextLogin.join(', ')}</StatusBadge>
                )}
                {!update.data.applied.ok && <StatusBadge tone="critical">{update.data.applied.error}</StatusBadge>}
              </div>
            )}
            <DialogFooter>
              <Button type="submit" size="sm" disabled={update.isPending}>
                {update.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Save data-icon="inline-start" />}
                {c.online ? '儲存（即時套用）' : '儲存'}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}

/* ---------------------------------------------------------------------- */
/* IP / MAC 封鎖                                                            */
/* ---------------------------------------------------------------------- */

function BansTab({ kind }: { kind: 'ip' | 'mac' }) {
  const list = useBans(kind)
  const add = useAddBan(kind)
  const remove = useRemoveBan(kind)
  const [value, setValue] = useState('')

  return (
    <div className="flex flex-col gap-3">
      {kind === 'mac' && (
        <Alert>
          <AlertTriangle />
          <AlertTitle>macbans 是 MEMORY 引擎的表</AlertTitle>
          <AlertDescription>MySQL 一重啟這張表就清空，這裡的 MAC 封鎖不是永久的。</AlertDescription>
        </Alert>
      )}
      <form
        className="flex flex-wrap items-center gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          if (!value.trim()) return
          add.mutate(value.trim(), { onSuccess: () => setValue('') })
        }}
      >
        <Input
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder={kind === 'ip' ? 'IP 或前綴，例如 1.2.3.4 或 1.2.' : 'MAC，例如 00-11-22-33-44-55'}
          className="max-w-xs font-mono"
        />
        <Button type="submit" size="sm" disabled={add.isPending || !value.trim()}>
          <Plus data-icon="inline-start" />
          新增封鎖
        </Button>
        {add.isError && <span className="text-xs text-red-600 dark:text-red-400">{errorMessage(add.error)}</span>}
        <PollStatus className="ml-auto" updatedAt={list.dataUpdatedAt} fetching={list.isFetching} onRefresh={() => void list.refetch()} />
      </form>
      <QueryError error={list.error} />
      <Card className="py-2">
        <CardContent className="px-2">
          {list.data && list.data.items.length === 0 ? (
            <p className="px-2 py-4 text-sm text-muted-foreground">目前沒有{kind === 'ip' ? ' IP' : ' MAC'}封鎖</p>
          ) : (
            <ul className="divide-y">
              {list.data?.items.map((b) => (
                <li key={b.id} className="flex items-center gap-3 px-2 py-1.5 text-sm">
                  <Badge variant="outline" className="tabular-nums">
                    #{b.id}
                  </Badge>
                  <span className="font-mono">{b.value}</span>
                  <Button size="icon-xs" variant="ghost" className="ml-auto" title="解除" disabled={remove.isPending} onClick={() => remove.mutate(b.id)}>
                    <Trash2 />
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
      {remove.isError && <span className="text-xs text-red-600 dark:text-red-400">{errorMessage(remove.error)}</span>}
      {kind === 'ip' && (
        <p className="text-xs text-muted-foreground">
          比對方式是「登入 IP 以此開頭」（LIKE CONCAT(ip, '%')），所以 <code className="font-mono">1.2.</code> 會擋整個 1.2.x.x。
        </p>
      )}
    </div>
  )
}
