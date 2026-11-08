import { useMemo, useState } from 'react'
import { createColumnHelper, type FilterFn } from '@tanstack/react-table'
import { EyeOff, Search } from 'lucide-react'
import { DataTable } from '@/components/data-table'
import { PageHeader } from '@/components/page-header'
import { PollStatus } from '@/components/poll-status'
import { QueryError } from '@/components/query-error'
import { FlagBadge, StatusBadge } from '@/components/status-badge'
import { Input } from '@/components/ui/input'
import { formatNumber } from '@/lib/format'
import { usePlayers } from '@/lib/queries'
import type { PlayerInfo } from '@/lib/types'

const col = createColumnHelper<PlayerInfo>()

const numeric = { className: 'text-right tabular-nums' }

const columns = [
  col.accessor('name', {
    header: '名稱',
    cell: (c) => <span className="font-medium">{c.getValue()}</span>,
  }),
  col.accessor('level', {
    header: '等級',
    cell: (c) => formatNumber(c.getValue()),
    meta: numeric,
  }),
  col.accessor('jobName', {
    header: '職業',
    cell: (c) => (
      <span title={`職業代碼 ${c.row.original.job}`}>{c.getValue()}</span>
    ),
  }),
  col.accessor('channel', {
    header: '頻道',
    cell: (c) => c.getValue(),
    meta: numeric,
  }),
  col.accessor('mapName', {
    header: '地圖',
    cell: (c) => (
      <span className="inline-flex flex-col leading-tight">
        <span>{c.getValue() || <span className="text-muted-foreground">（未知）</span>}</span>
        <span className="font-mono text-[11px] text-muted-foreground">{c.row.original.mapId}</span>
      </span>
    ),
  }),
  col.accessor('accountName', {
    header: '帳號',
    cell: (c) => (
      <span>
        {c.getValue()}
        <span className="ml-1 font-mono text-[11px] text-muted-foreground">#{c.row.original.accountId}</span>
      </span>
    ),
  }),
  col.accessor('gmLevel', {
    header: 'GM 等級',
    cell: (c) => {
      const v = c.getValue()
      return v > 0 ? <StatusBadge tone="good">GM {v}</StatusBadge> : <span className="text-muted-foreground">0</span>
    },
    meta: numeric,
  }),
  col.accessor('ip', {
    header: 'IP',
    cell: (c) => <span className="font-mono text-xs">{c.getValue()}</span>,
  }),
  col.accessor('hidden', {
    header: '隱身',
    cell: (c) => <FlagBadge value={c.getValue()} onLabel="隱身" offLabel="否" onTone="warning" onIcon={EyeOff} />,
  }),
]

/** 只比對名稱 / 帳號 / 地圖（含 id），不比對 IP 之類的其他欄位 */
const playerFilter: FilterFn<PlayerInfo> = (row, _columnId, filterValue: string) => {
  const q = filterValue.trim().toLowerCase()
  if (!q) return true
  const p = row.original
  return (
    p.name.toLowerCase().includes(q) ||
    p.accountName.toLowerCase().includes(q) ||
    p.mapName.toLowerCase().includes(q) ||
    String(p.mapId).includes(q)
  )
}

export function PlayersSection() {
  const q = usePlayers()
  const [filter, setFilter] = useState('')
  const data = useMemo(() => q.data ?? [], [q.data])

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="線上玩家"
        description={q.data ? `目前 ${formatNumber(q.data.length)} 人在線上` : '所有頻道的線上角色'}
        actions={
          <PollStatus
            updatedAt={q.dataUpdatedAt}
            fetching={q.isFetching}
            onRefresh={() => void q.refetch()}
          />
        }
      />
      <QueryError error={q.error} />
      <div className="relative max-w-sm">
        <Search
          className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
          aria-hidden
        />
        <Input
          type="search"
          placeholder="篩選名稱、帳號或地圖…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          className="pl-8"
          aria-label="篩選玩家"
        />
      </div>
      <DataTable
        columns={columns}
        data={data}
        getRowId={(r) => String(r.id)}
        globalFilter={filter}
        globalFilterFn={playerFilter}
        initialSorting={[{ id: 'name', desc: false }]}
        emptyMessage={filter ? '沒有符合的玩家' : '目前沒有玩家在線上'}
        loading={q.isPending}
      />
    </div>
  )
}
