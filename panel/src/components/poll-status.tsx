import { Pause, Play, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { formatTime } from '@/lib/format'
import { usePolling } from '@/lib/polling'
import { cn } from '@/lib/utils'

export interface PollStatusProps {
  /** useQuery 的 dataUpdatedAt（0 表示還沒拿到資料） */
  updatedAt: number
  /** 正在抓取中（顯示旋轉圖示） */
  fetching?: boolean
  /** 手動重新整理 */
  onRefresh?: () => void
  className?: string
}

/** 「更新於 HH:mm:ss」+ 暫停 / 繼續輪詢 + 手動重新整理 */
export function PollStatus({ updatedAt, fetching = false, onRefresh, className }: PollStatusProps) {
  const { paused, setPaused } = usePolling()
  return (
    <div className={cn('flex items-center gap-2 text-xs text-muted-foreground', className)}>
      <span className="tabular-nums">
        {updatedAt > 0 ? `更新於 ${formatTime(updatedAt)}` : '尚未更新'}
      </span>
      {paused && <span className="text-amber-600 dark:text-amber-400">（已暫停）</span>}
      {onRefresh && (
        <Button
          variant="ghost"
          size="icon-xs"
          onClick={onRefresh}
          disabled={fetching}
          aria-label="重新整理"
          title="重新整理"
        >
          <RefreshCw className={cn(fetching && 'animate-spin')} />
        </Button>
      )}
      <Button
        variant="outline"
        size="xs"
        onClick={() => setPaused(!paused)}
        aria-pressed={paused}
        title={paused ? '繼續自動更新' : '暫停自動更新'}
      >
        {paused ? <Play data-icon="inline-start" /> : <Pause data-icon="inline-start" />}
        {paused ? '繼續' : '暫停'}
      </Button>
    </div>
  )
}
