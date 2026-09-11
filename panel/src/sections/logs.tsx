import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowDownToLine, FileText } from 'lucide-react'
import { PageHeader } from '@/components/page-header'
import { PollStatus } from '@/components/poll-status'
import { QueryError } from '@/components/query-error'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { formatBytes, formatDateTime, formatNumber } from '@/lib/format'
import { useLogFiles, useLogTail } from '@/lib/queries'
import { cn } from '@/lib/utils'

const LINE_OPTIONS = [100, 200, 500, 1000] as const
type LineCount = (typeof LINE_OPTIONS)[number]

/** 距離底部多少 px 內視為「還在底部」，繼續自動捲到底 */
const STICK_THRESHOLD = 24

export function LogsSection() {
  const files = useLogFiles()
  const [selected, setSelected] = useState<string | null>(null)
  const [lines, setLines] = useState<LineCount>(200)

  // 選中的檔案被刪掉時（列表裡不見了），取消選取
  useEffect(() => {
    if (selected && files.data && !files.data.some((f) => f.name === selected)) {
      setSelected(null)
    }
  }, [files.data, selected])

  const tail = useLogTail(selected, lines)

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="日誌"
        description="Logs/ 目錄下的 *.rtf 與 *.txt"
        actions={
          <PollStatus
            updatedAt={selected ? tail.dataUpdatedAt : files.dataUpdatedAt}
            fetching={files.isFetching || tail.isFetching}
            onRefresh={() => {
              void files.refetch()
              if (selected) void tail.refetch()
            }}
          />
        }
      />

      <QueryError error={files.error} />

      <div className="grid gap-4 lg:grid-cols-[18rem_1fr]">
        <Card className="gap-2 py-3 lg:sticky lg:top-16 lg:self-start">
          <CardHeader className="px-3">
            <CardTitle className="text-sm font-medium text-muted-foreground">
              檔案{files.data ? `（${files.data.length}）` : ''}
            </CardTitle>
          </CardHeader>
          <CardContent className="px-1">
            {files.isPending ? (
              <p className="px-2 py-4 text-sm text-muted-foreground">載入中…</p>
            ) : !files.data || files.data.length === 0 ? (
              <p className="px-2 py-4 text-sm text-muted-foreground">Logs/ 目錄裡沒有日誌檔</p>
            ) : (
              <ul className="flex max-h-[60svh] flex-col gap-0.5 overflow-y-auto">
                {files.data.map((f) => {
                  const active = f.name === selected
                  return (
                    <li key={f.name}>
                      <button
                        type="button"
                        onClick={() => setSelected(f.name)}
                        aria-current={active ? 'true' : undefined}
                        className={cn(
                          'flex w-full flex-col gap-0.5 rounded-md px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted',
                          active && 'bg-accent text-accent-foreground',
                        )}
                      >
                        <span className="flex items-center gap-1.5 truncate font-medium">
                          <FileText className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                          <span className="truncate">{f.name}</span>
                        </span>
                        <span className="pl-5 text-[11px] text-muted-foreground tabular-nums">
                          {formatBytes(f.size)} · {formatDateTime(f.modified)}
                        </span>
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </CardContent>
        </Card>

        <div className="flex min-w-0 flex-col gap-3">
          {selected === null ? (
            <Card className="py-10">
              <CardContent className="text-center text-sm text-muted-foreground">
                從左側選擇一個日誌檔
              </CardContent>
            </Card>
          ) : (
            <>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex min-w-0 items-center gap-2">
                  <h2 className="truncate font-mono text-sm font-medium">{selected}</h2>
                  {tail.data && (
                    <span className="text-xs text-muted-foreground tabular-nums">
                      {formatBytes(tail.data.size)} · {formatDateTime(tail.data.modified)}
                    </span>
                  )}
                  {tail.data?.truncated && (
                    <Badge variant="secondary" title="檔案還有更早的內容沒顯示">
                      已截斷
                    </Badge>
                  )}
                </div>
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <span>顯示最後</span>
                  <Select value={String(lines)} onValueChange={(v) => setLines(Number(v) as LineCount)}>
                    <SelectTrigger size="sm" className="w-24" aria-label="行數">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {LINE_OPTIONS.map((n) => (
                        <SelectItem key={n} value={String(n)}>
                          {formatNumber(n)} 行
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>
              <QueryError error={tail.error} />
              <LogViewer lines={tail.data?.lines} loading={tail.isPending} />
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/** 等寬 <pre> 檢視器：使用者在底部時新內容自動捲到底，往上捲了就停住並顯示「跳到底部」 */
function LogViewer({ lines, loading }: { lines: string[] | undefined; loading: boolean }) {
  const ref = useRef<HTMLPreElement>(null)
  const stickRef = useRef(true)
  const [stuck, setStuck] = useState(true)

  const text = useMemo(() => (lines ? lines.join('\n') : ''), [lines])

  function scrollToBottom() {
    const el = ref.current
    if (el) el.scrollTop = el.scrollHeight
    stickRef.current = true
    setStuck(true)
  }

  // 內容更新後，若之前就在底部，就跟著捲到底
  useEffect(() => {
    if (stickRef.current) {
      const el = ref.current
      if (el) el.scrollTop = el.scrollHeight
    }
  }, [text])

  function onScroll() {
    const el = ref.current
    if (!el) return
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_THRESHOLD
    stickRef.current = atBottom
    setStuck(atBottom)
  }

  return (
    <div className="relative">
      <pre
        ref={ref}
        onScroll={onScroll}
        className="h-[65svh] overflow-auto rounded-lg border bg-muted/40 p-3 font-mono text-xs leading-5 whitespace-pre-wrap break-all"
        tabIndex={0}
      >
        {loading && !lines ? (
          <span className="text-muted-foreground">載入中…</span>
        ) : lines && lines.length === 0 ? (
          <span className="text-muted-foreground">（檔案是空的）</span>
        ) : (
          text
        )}
      </pre>
      {!stuck && (
        <Button
          size="sm"
          variant="secondary"
          className="absolute right-4 bottom-3 shadow-sm"
          onClick={scrollToBottom}
        >
          <ArrowDownToLine data-icon="inline-start" />
          跳到底部
        </Button>
      )}
    </div>
  )
}
