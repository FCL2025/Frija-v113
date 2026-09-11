import {
  Coffee,
  Cpu,
  Database,
  Hourglass,
  LogIn,
  MemoryStick,
  Power,
  Radio,
  ShieldAlert,
  Timer,
  Users,
} from 'lucide-react'
import { PageHeader } from '@/components/page-header'
import { PollStatus } from '@/components/poll-status'
import { QueryError } from '@/components/query-error'
import { Meter, StatCard } from '@/components/stat-card'
import { FlagBadge } from '@/components/status-badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import {
  formatDuration,
  formatMB,
  formatNumber,
  formatSeconds,
  formatDateTime,
  percent,
} from '@/lib/format'
import { usePing, useStatus } from '@/lib/queries'
import type { StatusResponse } from '@/lib/types'

export function DashboardSection() {
  const status = useStatus()
  const ping = usePing()
  const s = status.data
  const p = ping.data

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="儀表板"
        description={
          s ? (
            <>
              {s.serverName}
              {s.eventMessage && <span className="ml-2 text-muted-foreground/80">· {s.eventMessage}</span>}
            </>
          ) : (
            '伺服器狀態總覽'
          )
        }
        actions={
          <PollStatus
            updatedAt={status.dataUpdatedAt}
            fetching={status.isFetching}
            onRefresh={() => {
              void status.refetch()
              void ping.refetch()
            }}
          />
        }
      />

      <QueryError error={status.error ?? ping.error} />

      {!s ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} className="h-28 rounded-xl" />
          ))}
        </div>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard
              icon={<Users className="size-4" aria-hidden />}
              label="線上人數"
              value={formatNumber(s.online)}
              suffix={`/ ${formatNumber(s.userLimit)}`}
              detail={`${percent(s.online, s.userLimit).toFixed(0)}% 使用率`}
              footer={<Meter value={s.online} max={s.userLimit} label="線上人數使用率" />}
            />
            <StatCard
              icon={<Radio className="size-4" aria-hidden />}
              label="頻道數"
              value={formatNumber(s.channelCount)}
              detail={`${formatNumber(Object.keys(s.perChannel).length)} 個頻道回報中`}
            />
            <StatCard
              icon={<LogIn className="size-4" aria-hidden />}
              label="登入伺服器連線數"
              value={formatNumber(s.loginSessions)}
              detail="尚未選角的連線"
            />
            <StatCard
              icon={<Database className="size-4" aria-hidden />}
              label="資料庫連線"
              value={formatNumber(s.db.active)}
              suffix={`使用中 / ${formatNumber(s.db.poolSize)}`}
              detail={`閒置 ${formatNumber(s.db.idle)} 條`}
              footer={<Meter value={s.db.active} max={s.db.poolSize} label="資料庫連線池使用率" />}
            />
            <StatCard
              icon={<MemoryStick className="size-4" aria-hidden />}
              label="JVM 記憶體"
              value={formatMB(s.jvm.heapUsed)}
              suffix={`/ ${formatMB(s.jvm.heapMax)}`}
              detail={`已配置 ${formatMB(s.jvm.heapCommitted)}`}
              footer={<Meter value={s.jvm.heapUsed} max={s.jvm.heapMax} label="Heap 使用率" />}
            />
            <StatCard
              icon={<Cpu className="size-4" aria-hidden />}
              label="執行緒數"
              value={formatNumber(s.jvm.threads)}
              detail={`${formatNumber(s.jvm.cores)} 核心`}
            />
            <StatCard
              icon={<Hourglass className="size-4" aria-hidden />}
              label="啟動耗時"
              value={p ? formatSeconds(p.startupMs) : '—'}
              detail={p ? `JVM 啟動於 ${formatDateTime(p.startedAt)}` : ping.isError ? '無法取得' : '讀取中…'}
            />
            <StatCard
              icon={<Timer className="size-4" aria-hidden />}
              label="執行時間"
              value={<span className="text-xl">{formatDuration(s.jvm.uptimeMs)}</span>}
              detail={
                <span className="inline-flex items-center gap-1">
                  <Coffee className="size-3" aria-hidden />
                  Java {s.jvm.javaVersion}
                  {p && ` · v${p.version} patch ${p.patch}`}
                </span>
              }
            />
          </div>

          <div className="grid gap-3 lg:grid-cols-3">
            <Card className="gap-3 py-4">
              <CardHeader className="px-4">
                <CardTitle className="text-sm font-medium text-muted-foreground">伺服器狀態</CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-3 px-4">
                <StatusRow label="僅限管理員">
                  <FlagBadge
                    value={s.adminOnly}
                    onLabel="僅限管理員登入"
                    offLabel="開放登入"
                    onTone="warning"
                    onIcon={ShieldAlert}
                  />
                </StatusRow>
                <StatusRow label="關機程序">
                  <FlagBadge
                    value={s.shutdown}
                    onLabel="關機中"
                    offLabel="正常運作"
                    onTone="critical"
                    onIcon={Power}
                  />
                </StatusRow>
              </CardContent>
            </Card>

            <Card className="gap-3 py-4 lg:col-span-2">
              <CardHeader className="px-4">
                <CardTitle className="text-sm font-medium text-muted-foreground">各頻道人數</CardTitle>
              </CardHeader>
              <CardContent className="px-4">
                <ChannelBars perChannel={s.perChannel} />
              </CardContent>
            </Card>
          </div>
        </>
      )}
    </div>
  )
}

function StatusRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3 text-sm">
      <span className="text-muted-foreground">{label}</span>
      {children}
    </div>
  )
}

/** 單一序列的橫條清單：條長依該頻道人數相對於最多頻道的比例 */
function ChannelBars({ perChannel }: { perChannel: StatusResponse['perChannel'] }) {
  const rows = Object.entries(perChannel)
    .map(([ch, n]) => ({ channel: Number(ch), count: n }))
    .sort((a, b) => a.channel - b.channel)
  if (rows.length === 0) {
    return <p className="text-sm text-muted-foreground">沒有頻道資料</p>
  }
  const max = Math.max(1, ...rows.map((r) => r.count))
  return (
    <ul className="flex flex-col gap-2">
      {rows.map((r) => (
        <li key={r.channel} className="grid grid-cols-[4rem_1fr_3rem] items-center gap-3 text-sm">
          <span className="text-muted-foreground">頻道 {r.channel}</span>
          <div className="h-2 overflow-hidden rounded-full bg-muted">
            <div
              className="h-full rounded-full bg-sky-600 transition-[width] duration-500 dark:bg-sky-400"
              style={{ width: `${(r.count / max) * 100}%` }}
            />
          </div>
          <span className="text-right tabular-nums">{formatNumber(r.count)}</span>
        </li>
      ))}
    </ul>
  )
}
