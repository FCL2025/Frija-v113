import { useMemo } from 'react'
import { createColumnHelper } from '@tanstack/react-table'
import { MegaphoneOff, Power, ShieldAlert } from 'lucide-react'
import { DataTable } from '@/components/data-table'
import { PageHeader } from '@/components/page-header'
import { PollStatus } from '@/components/poll-status'
import { QueryError } from '@/components/query-error'
import { FlagBadge } from '@/components/status-badge'
import { formatNumber } from '@/lib/format'
import { useChannels } from '@/lib/queries'
import type { ChannelInfo } from '@/lib/types'

const col = createColumnHelper<ChannelInfo>()

const numeric = { className: 'text-right tabular-nums' }

const columns = [
  col.accessor('channel', {
    header: '頻道',
    cell: (c) => <span className="font-medium">{c.getValue()}</span>,
    meta: numeric,
  }),
  col.accessor('ip', {
    header: '位址',
    cell: (c) => <span className="font-mono text-xs">{c.getValue()}</span>,
  }),
  col.accessor('port', {
    header: 'Port',
    cell: (c) => <span className="font-mono text-xs">{c.getValue()}</span>,
    meta: numeric,
  }),
  col.accessor('connected', {
    header: '連線數',
    cell: (c) => formatNumber(c.getValue()),
    meta: numeric,
  }),
  col.accessor('loadedMaps', {
    header: '已載入地圖',
    cell: (c) => formatNumber(c.getValue()),
    meta: numeric,
  }),
  col.accessor('expRate', { header: '經驗', cell: (c) => `×${c.getValue()}`, meta: numeric }),
  col.accessor('mesoRate', { header: '楓幣', cell: (c) => `×${c.getValue()}`, meta: numeric }),
  col.accessor('dropRate', { header: '掉寶', cell: (c) => `×${c.getValue()}`, meta: numeric }),
  col.accessor('cashRate', { header: '點數', cell: (c) => `×${c.getValue()}`, meta: numeric }),
  col.accessor('serverMessage', {
    header: '頻道公告',
    cell: (c) => (
      <span className="block max-w-[28rem] truncate" title={c.getValue()}>
        {c.getValue() || <span className="text-muted-foreground">—</span>}
      </span>
    ),
  }),
  col.accessor('adminOnly', {
    header: '僅限管理員',
    cell: (c) => (
      <FlagBadge value={c.getValue()} onLabel="是" offLabel="否" onTone="warning" onIcon={ShieldAlert} />
    ),
    enableSorting: false,
  }),
  col.accessor('shutdown', {
    header: '關機中',
    cell: (c) => (
      <FlagBadge value={c.getValue()} onLabel="是" offLabel="否" onTone="critical" onIcon={Power} />
    ),
    enableSorting: false,
  }),
  col.accessor('megaphoneMuted', {
    header: '喇叭禁言',
    cell: (c) => (
      <FlagBadge value={c.getValue()} onLabel="是" offLabel="否" onTone="warning" onIcon={MegaphoneOff} />
    ),
    enableSorting: false,
  }),
]

export function ChannelsSection() {
  const q = useChannels()
  const data = useMemo(() => q.data ?? [], [q.data])

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="頻道"
        description={q.data ? `共 ${q.data.length} 個頻道` : '各頻道的連線數與倍率'}
        actions={
          <PollStatus
            updatedAt={q.dataUpdatedAt}
            fetching={q.isFetching}
            onRefresh={() => void q.refetch()}
          />
        }
      />
      <QueryError error={q.error} />
      <DataTable
        columns={columns}
        data={data}
        getRowId={(r) => String(r.channel)}
        initialSorting={[{ id: 'channel', desc: false }]}
        emptyMessage="沒有頻道"
        loading={q.isPending}
      />
    </div>
  )
}
