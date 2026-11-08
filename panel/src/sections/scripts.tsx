import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import CodeMirror, { EditorView, type ReactCodeMirrorRef } from '@uiw/react-codemirror'
import { javascript } from '@codemirror/lang-javascript'
import { AlertTriangle, FileCode2, LoaderCircle, Save, Search, X } from 'lucide-react'
import { PageHeader } from '@/components/page-header'
import { QueryError } from '@/components/query-error'
import { StatusBadge } from '@/components/status-badge'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { ApiError, errorMessage } from '@/lib/api'
import { formatBytes, formatDateTime, formatNumber } from '@/lib/format'
import { useSaveScript, useScriptFile, useScriptKinds, useScriptList, useScriptSearch } from '@/lib/queries'
import { useTheme } from '@/lib/theme'
import type { ScriptKind, UnencodableChar } from '@/lib/types'
import { cn } from '@/lib/utils'

const KIND_LABEL: Record<ScriptKind, string> = {
  npc: 'NPC',
  portal: '傳送點',
  quest: '任務',
  reactor: '反應物',
  event: '事件',
}

const KINDS: ScriptKind[] = ['npc', 'portal', 'quest', 'reactor', 'event']

export function ScriptsSection() {
  const kinds = useScriptKinds()
  const [kind, setKind] = useState<ScriptKind>('npc')
  const [selected, setSelected] = useState<{ kind: ScriptKind; name: string; line?: number } | null>(null)
  const [filter, setFilter] = useState('')
  const [search, setSearch] = useState('')
  const [dirty, setDirty] = useState(false)

  const confirmDiscard = useCallback(() => {
    return !dirty || window.confirm('目前的修改尚未存檔，確定要放棄？')
  }, [dirty])

  function open(next: { kind: ScriptKind; name: string; line?: number }) {
    if (selected && (selected.kind !== next.kind || selected.name !== next.name) && !confirmDiscard()) return
    setSelected(next)
  }

  // 有未存檔修改時，關閉分頁前提醒
  useEffect(() => {
    if (!dirty) return
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault()
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [dirty])

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="腳本"
        description="Libs/scripts/ 下的 Nashorn 腳本。檔案是 BIG5，這裡的編輯以 UTF-8 進行、存檔時轉回 BIG5。"
      />
      <QueryError error={kinds.error} />

      <div className="grid gap-4 xl:grid-cols-[22rem_1fr]">
        <div className="flex flex-col gap-3">
          <Tabs value={kind} onValueChange={(v) => setKind(v as ScriptKind)}>
            <TabsList className="w-full">
              {KINDS.map((k) => (
                <TabsTrigger key={k} value={k} className="flex-1 px-1 text-xs">
                  {KIND_LABEL[k]}
                  {kinds.data && (
                    <span className="ml-1 text-muted-foreground tabular-nums">
                      {kinds.data.find((x) => x.kind === k)?.count ?? 0}
                    </span>
                  )}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>

          <div className="relative">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden />
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="全文搜尋（至少 2 個字，Enter 搜尋）"
              className="pl-8"
              onKeyDown={(e) => {
                if (e.key === 'Escape') setSearch('')
              }}
            />
          </div>

          {search.trim().length >= 2 ? (
            <SearchResults q={search} kind={kind} onOpen={open} selected={selected} />
          ) : (
            <FileList kind={kind} filter={filter} setFilter={setFilter} onOpen={open} selected={selected} />
          )}
        </div>

        <div className="min-w-0">
          {selected ? (
            <Editor
              key={`${selected.kind}/${selected.name}`}
              kind={selected.kind}
              name={selected.name}
              jumpToLine={selected.line}
              onDirty={setDirty}
              onClose={() => {
                if (confirmDiscard()) setSelected(null)
              }}
            />
          ) : (
            <Card className="py-16">
              <CardContent className="text-center text-sm text-muted-foreground">
                從左側選一支腳本，或用全文搜尋找到它
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  )
}

function FileList({
  kind,
  filter,
  setFilter,
  onOpen,
  selected,
}: {
  kind: ScriptKind
  filter: string
  setFilter: (v: string) => void
  onOpen: (s: { kind: ScriptKind; name: string }) => void
  selected: { kind: ScriptKind; name: string } | null
}) {
  const list = useScriptList(kind)
  const needle = filter.trim().toLowerCase()
  const rows = useMemo(
    () => (list.data ?? []).filter((f) => !needle || f.name.toLowerCase().includes(needle)),
    [list.data, needle],
  )
  return (
    <Card className="gap-2 py-3">
      <CardContent className="flex flex-col gap-2 px-3">
        <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="依檔名過濾" className="h-8 text-xs" />
        <QueryError error={list.error} />
        <div className="text-xs text-muted-foreground tabular-nums">
          {list.data ? `${formatNumber(rows.length)} / ${formatNumber(list.data.length)} 支` : '載入中…'}
        </div>
        <ul className="flex max-h-[60svh] flex-col gap-0.5 overflow-y-auto">
          {rows.map((f) => {
            const active = selected?.kind === kind && selected.name === f.name
            return (
              <li key={f.name}>
                <button
                  type="button"
                  onClick={() => onOpen({ kind, name: f.name })}
                  aria-current={active ? 'true' : undefined}
                  className={cn(
                    'flex w-full items-center gap-2 rounded-md px-2 py-1 text-left text-sm transition-colors hover:bg-muted',
                    active && 'bg-accent text-accent-foreground',
                  )}
                >
                  <FileCode2 className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                  <span className="truncate font-mono text-xs">{f.name}</span>
                  <span className="ml-auto shrink-0 text-[11px] text-muted-foreground tabular-nums">{formatBytes(f.size)}</span>
                </button>
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
  )
}

function SearchResults({
  q,
  kind,
  onOpen,
  selected,
}: {
  q: string
  kind: ScriptKind
  onOpen: (s: { kind: ScriptKind; name: string; line?: number }) => void
  selected: { kind: ScriptKind; name: string } | null
}) {
  const [scope, setScope] = useState<'kind' | 'all'>('kind')
  const [submitted, setSubmitted] = useState(q)
  useEffect(() => {
    const t = setTimeout(() => setSubmitted(q), 400)
    return () => clearTimeout(t)
  }, [q])
  const result = useScriptSearch(submitted, scope === 'kind' ? kind : '')
  return (
    <Card className="gap-2 py-3">
      <CardContent className="flex flex-col gap-2 px-3">
        <div className="flex items-center gap-2 text-xs">
          <Tabs value={scope} onValueChange={(v) => setScope(v as 'kind' | 'all')}>
            <TabsList className="h-7">
              <TabsTrigger value="kind" className="px-2 text-xs">
                只搜 {KIND_LABEL[kind]}
              </TabsTrigger>
              <TabsTrigger value="all" className="px-2 text-xs">
                全部
              </TabsTrigger>
            </TabsList>
          </Tabs>
          {result.isFetching && <LoaderCircle className="size-3.5 animate-spin text-muted-foreground" aria-hidden />}
        </div>
        <QueryError error={result.error} />
        {result.data && (
          <div className="text-xs text-muted-foreground tabular-nums">
            {formatNumber(result.data.hits.length)} 處命中，{formatNumber(result.data.filesMatched)} 個檔案
            {result.data.capped && '（已達上限，請縮小範圍）'}
          </div>
        )}
        <ul className="flex max-h-[60svh] flex-col gap-0.5 overflow-y-auto">
          {result.data?.hits.map((h, i) => {
            const active = selected?.kind === h.kind && selected.name === h.name
            return (
              <li key={i}>
                <button
                  type="button"
                  onClick={() => onOpen({ kind: h.kind, name: h.name, line: h.line })}
                  className={cn(
                    'flex w-full flex-col gap-0.5 rounded-md px-2 py-1 text-left transition-colors hover:bg-muted',
                    active && 'bg-accent text-accent-foreground',
                  )}
                >
                  <span className="flex items-center gap-1.5 font-mono text-xs">
                    <Badge variant="outline" className="px-1 py-0 text-[10px]">
                      {KIND_LABEL[h.kind]}
                    </Badge>
                    <span className="truncate">{h.name}</span>
                    <span className="text-muted-foreground">:{h.line}</span>
                  </span>
                  <span className="truncate font-mono text-[11px] text-muted-foreground">{h.text.trim()}</span>
                </button>
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
  )
}

function Editor({
  kind,
  name,
  jumpToLine,
  onDirty,
  onClose,
}: {
  kind: ScriptKind
  name: string
  jumpToLine?: number
  onDirty: (dirty: boolean) => void
  onClose: () => void
}) {
  const file = useScriptFile(kind, name)
  const save = useSaveScript()
  const { dark } = useTheme()
  const ref = useRef<ReactCodeMirrorRef>(null)
  const [text, setText] = useState<string | null>(null)
  const loaded = file.data
  const dirty = loaded !== undefined && text !== null && text !== loaded.content

  useEffect(() => {
    onDirty(dirty)
  }, [dirty, onDirty])

  // 檔案第一次到手時把內容放進編輯器；之後只有存檔成功會同步
  useEffect(() => {
    if (loaded && text === null) setText(loaded.content)
  }, [loaded, text])

  const goToLine = useCallback((line: number, col = 1) => {
    const view = ref.current?.view
    if (!view) return
    const doc = view.state.doc
    const l = Math.max(1, Math.min(doc.lines, line))
    const info = doc.line(l)
    const pos = Math.min(info.to, info.from + Math.max(0, col - 1))
    view.dispatch({ selection: { anchor: pos }, effects: EditorView.scrollIntoView(pos, { y: 'center' }) })
    view.focus()
  }, [])

  const jumped = useRef(false)
  useEffect(() => {
    if (jumpToLine && text !== null && !jumped.current) {
      jumped.current = true
      // 等 CodeMirror 掛好
      setTimeout(() => goToLine(jumpToLine), 50)
    }
  }, [jumpToLine, text, goToLine])

  const unencodable: UnencodableChar[] | null = useMemo(() => {
    if (!save.isError || !(save.error instanceof ApiError)) return null
    const d = save.error.details as { unencodable?: UnencodableChar[] } | undefined
    return d?.unencodable ?? null
  }, [save.isError, save.error])

  function doSave() {
    if (text === null) return
    save.mutate({ kind, name, content: text })
  }

  // Ctrl+S 存檔
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault()
        if (dirty && !save.isPending) doSave()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  })

  const extensions = useMemo(() => [javascript(), EditorView.lineWrapping], [])

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <Badge variant="outline">{KIND_LABEL[kind]}</Badge>
          <h2 className="truncate font-mono text-sm font-medium">{name}</h2>
          {loaded && (
            <span className="text-xs text-muted-foreground tabular-nums">
              {formatBytes(loaded.size)} · {formatDateTime(loaded.modified)}
            </span>
          )}
          {dirty && (
            <StatusBadge tone="warning">未存檔</StatusBadge>
          )}
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" onClick={doSave} disabled={!dirty || save.isPending}>
            {save.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Save data-icon="inline-start" />}
            存檔（Ctrl+S）
          </Button>
          <Button size="sm" variant="ghost" onClick={onClose} aria-label="關閉">
            <X />
          </Button>
        </div>
      </div>

      <QueryError error={file.error} />

      {loaded && !loaded.encodingOk && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>這支腳本不是合法的 BIG5（偵測為 {loaded.detectedEncoding}）</AlertTitle>
          <AlertDescription>
            遊戲讀它時會拋 MalformedInputException 並得到空字串，所以它現在完全沒有作用。
            以下顯示的是以 {loaded.detectedEncoding} 解讀的內容；直接存檔就會轉成 BIG5 並開始生效——
            注意這等於新增行為，不是還原。
          </AlertDescription>
        </Alert>
      )}

      {save.isError && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>存檔失敗</AlertTitle>
          <AlertDescription>
            <p>{errorMessage(save.error)}</p>
            {unencodable && (
              <ul className="mt-2 flex flex-wrap gap-1.5">
                {unencodable.map((u, i) => (
                  <li key={i}>
                    <button
                      type="button"
                      onClick={() => goToLine(u.line, u.col)}
                      className="rounded border border-red-500/40 bg-background px-1.5 py-0.5 font-mono text-xs hover:bg-red-500/10"
                      title={`跳到第 ${u.line} 行第 ${u.col} 字`}
                    >
                      {u.line}:{u.col} 「{u.char}」{u.codePoint}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </AlertDescription>
        </Alert>
      )}

      {save.isSuccess && !dirty && (
        <Alert>
          <AlertTitle>已存檔（{formatBytes(save.data.bytes)}，BIG5）</AlertTitle>
          <AlertDescription>{save.data.afterSave}</AlertDescription>
        </Alert>
      )}

      {loaded && (
        <p className="text-xs text-muted-foreground">{loaded.afterSave}</p>
      )}

      {text === null ? (
        <div className="flex h-[60svh] items-center justify-center rounded-lg border text-sm text-muted-foreground">
          {file.isPending ? '載入中…' : '無法載入'}
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border">
          <CodeMirror
            ref={ref}
            value={text}
            height="65svh"
            theme={dark ? 'dark' : 'light'}
            extensions={extensions}
            basicSetup={{ foldGutter: true, highlightActiveLine: true, tabSize: 4 }}
            onChange={(v) => setText(v)}
          />
        </div>
      )}
    </div>
  )
}
