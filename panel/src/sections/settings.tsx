import { useEffect, useState, type FormEvent } from 'react'
import { AlertTriangle, LoaderCircle, Pencil, Save, Sparkles, X } from 'lucide-react'
import { Outcome } from '@/components/outcome'
import { PageHeader } from '@/components/page-header'
import { PollStatus } from '@/components/poll-status'
import { QueryError } from '@/components/query-error'
import { StatusBadge, type StatusTone } from '@/components/status-badge'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { errorMessage } from '@/lib/api'
import { usePutSetting, useSettings } from '@/lib/queries'
import type { ApplyMode, PutSettingResponse, SettingEntry } from '@/lib/types'
import { cn } from '@/lib/utils'

const APPLY_META: Record<ApplyMode, { label: string; tone: StatusTone; hint: string }> = {
  live: { label: '即時', tone: 'good', hint: '存檔就生效；直接編輯 Settings.ini 存檔也一樣，伺服器約 2～4 秒內自動套用' },
  restart: { label: '需重啟', tone: 'warning', hint: '只有重啟伺服器才會生效' },
  none: { label: '無作用', tone: 'neutral', hint: '沒有程式讀這個設定，改了不會有任何效果' },
}

export function SettingsSection() {
  const settings = useSettings()
  const [editing, setEditing] = useState<string | null>(null)
  const [results, setResults] = useState<Record<string, PutSettingResponse>>({})

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="設定檔"
        description={
          settings.data ? (
            <span className="font-mono text-xs">{settings.data.path}</span>
          ) : (
            'Settings.ini 的讀取與寫入'
          )
        }
        actions={
          <PollStatus
            updatedAt={settings.dataUpdatedAt}
            fetching={settings.isFetching}
            onRefresh={() => void settings.refetch()}
          />
        }
      />

      <QueryError error={settings.error} />

      {settings.data && !settings.data.bom && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>Settings.ini 沒有 UTF-8 BOM</AlertTitle>
          <AlertDescription>
            伺服器以 UTF-8 讀取這個檔案；範本是帶 BOM 的。寫入器會保留現狀，不會自己加上。
          </AlertDescription>
        </Alert>
      )}

      <p className="text-sm text-muted-foreground">
        逐行改值，其餘位元組（BOM、換行、註解、對齊）原樣保留。「保存」會寫進檔案並立刻套用到執行中的伺服器；
        直接用編輯器改 Settings.ini 存檔也會在幾秒內自動套用。
        <span className="inline-flex flex-wrap gap-1 align-middle">
          {(Object.keys(APPLY_META) as ApplyMode[]).map((m) => (
            <StatusBadge key={m} tone={APPLY_META[m].tone}>
              {APPLY_META[m].label}
            </StatusBadge>
          ))}
        </span>
        三類的意思見各列的提示。
      </p>

      <div className="overflow-x-auto rounded-lg border">
        <Table className="table-fixed">
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="w-56">設定</TableHead>
              <TableHead>值</TableHead>
              <TableHead className="w-24">生效</TableHead>
              <TableHead className="w-28 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {settings.isPending ? (
              <TableRow>
                <TableCell colSpan={4} className="h-24 text-center text-muted-foreground">
                  載入中…
                </TableCell>
              </TableRow>
            ) : (
              settings.data?.entries.map((e) => (
                <SettingRow
                  key={e.key}
                  entry={e}
                  editing={editing === e.key}
                  onEdit={() => setEditing(e.key)}
                  onCancel={() => setEditing(null)}
                  result={results[e.key]}
                  onResult={(r) => {
                    setResults((s) => ({ ...s, [e.key]: r }))
                    setEditing(null)
                  }}
                />
              ))
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

function SettingRow({
  entry,
  editing,
  onEdit,
  onCancel,
  result,
  onResult,
}: {
  entry: SettingEntry
  editing: boolean
  onEdit: () => void
  onCancel: () => void
  result: PutSettingResponse | undefined
  onResult: (r: PutSettingResponse) => void
}) {
  const put = usePutSetting()
  const [draft, setDraft] = useState('')
  useEffect(() => {
    if (editing) setDraft(entry.value ?? entry.commentedDefault ?? '')
  }, [editing, entry.value, entry.commentedDefault])

  const meta = APPLY_META[entry.apply] ?? APPLY_META.restart
  const serverMessage = entry.key === 'tms.ServerMessage'
  const differs =
    entry.active &&
    !entry.secret &&
    entry.runtimeValue !== null &&
    entry.runtimeValue !== undefined &&
    entry.runtimeValue !== entry.value

  function submit() {
    put.mutate({ key: entry.key, value: draft }, { onSuccess: onResult })
  }

  return (
    <TableRow className={cn(!entry.active && 'bg-muted/30')}>
      <TableCell className="align-top">
        <div className="font-mono text-sm">{entry.key}</div>
        {entry.description && (
          <div className="mt-0.5 max-w-[16rem] text-xs text-muted-foreground">{entry.description}</div>
        )}
        {!entry.known && (
          <Badge variant="outline" className="mt-1">
            未分類
          </Badge>
        )}
      </TableCell>
      <TableCell className="align-top">
        {editing ? (
          <form
            className="flex flex-col gap-2"
            onSubmit={(e: FormEvent) => {
              e.preventDefault()
              submit()
            }}
          >
            <Input
              type={entry.secret ? 'password' : 'text'}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              autoFocus
              autoComplete="off"
              className="font-mono text-xs"
              placeholder={entry.secret ? '（不顯示原值）' : undefined}
            />
            <div className="flex flex-wrap items-center gap-2">
              <Button type="submit" size="sm" disabled={put.isPending}>
                {put.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Save data-icon="inline-start" />}
                {entry.apply === 'live' ? '保存（立即生效）' : entry.apply === 'restart' ? '保存（重啟後生效）' : '保存'}
              </Button>
              <Button type="button" size="sm" variant="ghost" onClick={onCancel} disabled={put.isPending}>
                <X data-icon="inline-start" />
                取消
              </Button>
            </div>
            {put.isError && <p className="text-xs text-red-600 dark:text-red-400">{errorMessage(put.error)}</p>}
            {serverMessage && (
              <p className="text-xs text-muted-foreground">
                這個值改了會立刻廣播，請到「即時操作」用「送出頻道公告」。
              </p>
            )}
          </form>
        ) : (
          <div className="flex flex-col gap-1">
            {entry.secret ? (
              <span className="text-xs text-muted-foreground">••••••（不會離開伺服器）</span>
            ) : entry.active ? (
              <code className="block max-w-full font-mono text-xs whitespace-pre-wrap break-all">{entry.value === '' ? <span className="text-muted-foreground">（空）</span> : entry.value}</code>
            ) : (
              <span className="text-xs text-muted-foreground">
                未設定，預設 <code className="font-mono">{entry.commentedDefault}</code>
              </span>
            )}
            {differs && (
              <span className="flex items-center gap-1 text-xs text-amber-700 dark:text-amber-400">
                <AlertTriangle className="size-3" aria-hidden />
                線上目前是 <code className="font-mono">{entry.runtimeValue}</code>
              </span>
            )}
            {result && <Outcome saved={result.saved} applied={result.applied} />}
          </div>
        )}
      </TableCell>
      <TableCell className="align-top">
        <StatusBadge tone={meta.tone} className="cursor-help" >
          <span title={meta.hint}>{meta.label}</span>
        </StatusBadge>
      </TableCell>
      <TableCell className="text-right align-top">
        {!editing && (
          <Button size="sm" variant="ghost" onClick={onEdit} disabled={serverMessage}>
            {entry.active ? <Pencil data-icon="inline-start" /> : <Sparkles data-icon="inline-start" />}
            {entry.active ? '編輯' : '啟用'}
          </Button>
        )}
      </TableCell>
    </TableRow>
  )
}
