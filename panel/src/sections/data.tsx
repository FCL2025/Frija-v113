import { useEffect, useMemo, useState, type FormEvent } from 'react'
import type { ColumnDef } from '@tanstack/react-table'
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight, LoaderCircle, Pencil, Plus, Save, Search, Trash2 } from 'lucide-react'
import { DataTable } from '@/components/data-table'
import { NamePicker } from '@/components/name-picker'
import { PageHeader } from '@/components/page-header'
import { QueryError } from '@/components/query-error'
import { StatusBadge } from '@/components/status-badge'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { errorMessage } from '@/lib/api'
import { formatNumber } from '@/lib/format'
import {
  useAddShopItem,
  useCashItems,
  useCreateShop,
  useDeleteCashItem,
  useDeleteDrop,
  useDeleteGlobalDrop,
  useDeleteShop,
  useDeleteShopItem,
  useDrops,
  useGlobalDrops,
  useReorderShop,
  useSaveCashItem,
  useSaveDrop,
  useSaveGlobalDrop,
  useShop,
  useShops,
  useUpdateShopItem,
} from '@/lib/queries'
import type { CashItemRow, DropRow, GlobalDropRow, NameHit, ShopItemRow } from '@/lib/types'
import { cn } from '@/lib/utils'

type Tab = 'drops' | 'global' | 'shops' | 'cash'
const PAGE_SIZE = 50

export function DataSection() {
  const [tab, setTab] = useState<Tab>('drops')
  return (
    <div className="flex flex-col gap-4">
      <PageHeader title="掉寶與商店" description="drop_data / drop_data_global / shops + shopitems / cashshop_modified_items；每次寫入後自動清掉對應的快取" />
      <Tabs value={tab} onValueChange={(v) => setTab(v as Tab)}>
        <TabsList>
          <TabsTrigger value="drops">掉寶</TabsTrigger>
          <TabsTrigger value="global">全域掉寶</TabsTrigger>
          <TabsTrigger value="shops">商店</TabsTrigger>
          <TabsTrigger value="cash">商城</TabsTrigger>
        </TabsList>
      </Tabs>
      {tab === 'drops' && <DropsTab />}
      {tab === 'global' && <GlobalDropsTab />}
      {tab === 'shops' && <ShopsTab />}
      {tab === 'cash' && <CashTab />}
    </div>
  )
}

/* ---------------------------------------------------------------------- */
/* 共用                                                                     */
/* ---------------------------------------------------------------------- */

function chancePercent(chance: number) {
  return `${((chance / 1_000_000) * 100).toFixed(chance >= 100_000 ? 0 : chance >= 10_000 ? 1 : 3)}%`
}

function NumField({ id, label, value, onChange, hint }: { id: string; label: string; value: string; onChange: (v: string) => void; hint?: string }) {
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={id} className="text-xs">
        {label}
      </Label>
      <Input id={id} type="number" inputMode="numeric" value={value} onChange={(e) => onChange(e.target.value)} className="h-8 font-mono text-xs" />
      {hint && <span className="text-[11px] text-muted-foreground">{hint}</span>}
    </div>
  )
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

function ItemLabel({ id, name }: { id: number; name: string | null }) {
  if (id === 0) return <span>楓幣</span>
  return (
    <span>
      {name ?? <span className="text-muted-foreground">（未知道具）</span>}
      <span className="ml-1 font-mono text-xs text-muted-foreground">{id}</span>
    </span>
  )
}

/* ---------------------------------------------------------------------- */
/* 掉寶                                                                     */
/* ---------------------------------------------------------------------- */

function DropsTab() {
  const [mob, setMob] = useState<NameHit | null>(null)
  const [item, setItem] = useState<NameHit | null>(null)
  const [page, setPage] = useState(1)
  useEffect(() => setPage(1), [mob, item])
  const drops = useDrops(mob?.id ?? 0, item?.id ?? 0, page, PAGE_SIZE)
  const del = useDeleteDrop()
  const [editing, setEditing] = useState<DropRow | 'new' | null>(null)

  const columns = useMemo<ColumnDef<DropRow, unknown>[]>(
    () => [
      { accessorKey: 'id', header: 'ID', meta: { className: 'tabular-nums text-right w-20' } },
      {
        id: 'mob',
        header: '怪物',
        accessorFn: (r) => r.mobName ?? String(r.dropperid),
        cell: ({ row }) => (
          <span>
            {row.original.mobName ?? <span className="text-muted-foreground">（未知）</span>}
            <span className="ml-1 font-mono text-xs text-muted-foreground">{row.original.dropperid}</span>
          </span>
        ),
      },
      { id: 'item', header: '道具', accessorFn: (r) => r.itemName ?? String(r.itemid), cell: ({ row }) => <ItemLabel id={row.original.itemid} name={row.original.itemName} /> },
      { id: 'qty', header: '數量', accessorFn: (r) => r.minimum_quantity, cell: ({ row }) => <span className="tabular-nums">{row.original.minimum_quantity}–{row.original.maximum_quantity}</span> },
      { accessorKey: 'questid', header: '任務', meta: { className: 'tabular-nums text-right' }, cell: ({ getValue }) => (getValue() as number) || '' },
      {
        accessorKey: 'chance',
        header: '機率',
        meta: { className: 'tabular-nums text-right' },
        cell: ({ getValue }) => (
          <span title={`${formatNumber(getValue() as number)} / 1,000,000`}>
            {chancePercent(getValue() as number)}
          </span>
        ),
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="flex justify-end gap-1">
            <Button size="icon-xs" variant="ghost" title="編輯" onClick={() => setEditing(row.original)}>
              <Pencil />
            </Button>
            <Button size="icon-xs" variant="ghost" title="刪除" disabled={del.isPending} onClick={() => window.confirm(`刪除這筆掉寶（#${row.original.id}）？`) && del.mutate(row.original.id)}>
              <Trash2 />
            </Button>
          </div>
        ),
      },
    ],
    [del],
  )

  return (
    <div className="flex flex-col gap-3">
      <div className="grid gap-2 sm:grid-cols-2">
        <div className="flex flex-col gap-1">
          <Label className="text-xs">怪物</Label>
          <NamePicker kind="mobs" value={mob?.id ?? 0} valueName={mob?.name} onChange={setMob} placeholder="怪物名稱或 id" />
        </div>
        <div className="flex flex-col gap-1">
          <Label className="text-xs">道具（反查哪些怪會掉）</Label>
          <NamePicker kind="items" value={item?.id ?? 0} valueName={item?.name} onChange={setItem} placeholder="道具名稱或 id" />
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" onClick={() => setEditing('new')} disabled={!mob && !item}>
          <Plus data-icon="inline-start" />
          新增掉寶
        </Button>
        {del.isError && <span className="text-xs text-red-600 dark:text-red-400">{errorMessage(del.error)}</span>}
        <span className="ml-auto text-xs text-muted-foreground">機率分母 1,000,000；999999 = 必掉。道具 0 = 楓幣。</span>
      </div>
      <QueryError error={drops.error} />
      {!mob && !item ? (
        <Card className="py-10">
          <CardContent className="text-center text-sm text-muted-foreground">先選一隻怪物，或用道具反查</CardContent>
        </Card>
      ) : (
        <>
          <DataTable columns={columns} data={drops.data?.items ?? []} getRowId={(r) => String(r.id)} loading={drops.isPending} emptyMessage="這隻怪沒有掉寶資料" initialSorting={[{ id: 'chance', desc: true }]} />
          {drops.data && <Pager page={drops.data.page} size={drops.data.size} total={drops.data.total} onPage={setPage} />}
        </>
      )}
      <DropDialog row={editing} defaultMob={mob} defaultItem={item} onClose={() => setEditing(null)} />
    </div>
  )
}

function DropDialog({ row, defaultMob, defaultItem, onClose }: { row: DropRow | 'new' | null; defaultMob: NameHit | null; defaultItem: NameHit | null; onClose: () => void }) {
  const save = useSaveDrop()
  const [mob, setMob] = useState<NameHit | null>(null)
  const [item, setItem] = useState<NameHit | null>(null)
  const [f, setF] = useState({ min: '1', max: '1', questid: '0', chance: '10000' })
  useEffect(() => {
    if (row === null) return
    if (row === 'new') {
      setMob(defaultMob)
      setItem(defaultItem)
      setF({ min: '1', max: '1', questid: '0', chance: '10000' })
    } else {
      setMob({ id: row.dropperid, name: row.mobName ?? '' })
      setItem({ id: row.itemid, name: row.itemid === 0 ? '楓幣' : (row.itemName ?? '') })
      setF({ min: String(row.minimum_quantity), max: String(row.maximum_quantity), questid: String(row.questid), chance: String(row.chance) })
    }
    save.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [row])

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!mob || !item) return
    save.mutate(
      {
        id: row && row !== 'new' ? row.id : undefined,
        patch: { dropperid: mob.id, itemid: item.id, minimum_quantity: Number(f.min), maximum_quantity: Number(f.max), questid: Number(f.questid), chance: Number(f.chance) },
      },
      { onSuccess: onClose },
    )
  }

  return (
    <Dialog open={row !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{row === 'new' ? '新增掉寶' : `編輯掉寶 #${typeof row === 'object' && row ? row.id : ''}`}</DialogTitle>
            <DialogDescription>存檔後會清掉掉寶快取，下一隻被打死的怪就用新資料。</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-1">
            <Label className="text-xs">怪物</Label>
            <NamePicker kind="mobs" value={mob?.id ?? 0} valueName={mob?.name} onChange={setMob} />
          </div>
          <div className="flex flex-col gap-1">
            <Label className="text-xs">道具（輸入 0 並按 Enter = 楓幣）</Label>
            <NamePicker kind="items" value={item?.id ?? -1} valueName={item ? (item.id === 0 ? '楓幣' : item.name) : null} onChange={(h) => setItem(h ? { id: h.id, name: h.id === 0 ? '楓幣' : h.name } : null)} />
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <NumField id="drop-min" label="最小數量" value={f.min} onChange={(v) => setF((s) => ({ ...s, min: v }))} />
            <NumField id="drop-max" label="最大數量" value={f.max} onChange={(v) => setF((s) => ({ ...s, max: v }))} />
            <NumField id="drop-quest" label="任務 ID" value={f.questid} onChange={(v) => setF((s) => ({ ...s, questid: v }))} hint="0 = 不限" />
            <NumField id="drop-chance" label="機率" value={f.chance} onChange={(v) => setF((s) => ({ ...s, chance: v }))} hint={Number.isFinite(Number(f.chance)) ? chancePercent(Number(f.chance)) : ''} />
          </div>
          {save.isError && (
            <Alert variant="destructive">
              <AlertTitle>存檔失敗</AlertTitle>
              <AlertDescription>{errorMessage(save.error)}</AlertDescription>
            </Alert>
          )}
          <DialogFooter>
            <Button type="submit" size="sm" disabled={save.isPending || !mob || !item}>
              {save.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Save data-icon="inline-start" />}
              存檔並重載掉寶
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/* ---------------------------------------------------------------------- */
/* 全域掉寶                                                                 */
/* ---------------------------------------------------------------------- */

function GlobalDropsTab() {
  const list = useGlobalDrops()
  const del = useDeleteGlobalDrop()
  const [editing, setEditing] = useState<GlobalDropRow | 'new' | null>(null)

  const columns = useMemo<ColumnDef<GlobalDropRow, unknown>[]>(
    () => [
      { accessorKey: 'id', header: 'ID', meta: { className: 'tabular-nums text-right w-16' } },
      { id: 'item', header: '道具', accessorFn: (r) => r.itemName ?? String(r.itemid), cell: ({ row }) => <ItemLabel id={row.original.itemid} name={row.original.itemName} /> },
      { accessorKey: 'continent', header: '範圍', meta: { className: 'tabular-nums' }, cell: ({ getValue }) => ((getValue() as number) < 0 ? '全部' : String(getValue())) },
      { accessorKey: 'dropType', header: '類型', meta: { className: 'tabular-nums' } },
      { id: 'qty', header: '數量', accessorFn: (r) => r.minimum_quantity, cell: ({ row }) => <span className="tabular-nums">{row.original.minimum_quantity}–{row.original.maximum_quantity}</span> },
      { accessorKey: 'chance', header: '機率', meta: { className: 'tabular-nums text-right' }, cell: ({ getValue }) => chancePercent(getValue() as number) },
      { accessorKey: 'comments', header: '備註' },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="flex justify-end gap-1">
            <Button size="icon-xs" variant="ghost" title="編輯" onClick={() => setEditing(row.original)}>
              <Pencil />
            </Button>
            <Button size="icon-xs" variant="ghost" title="刪除" disabled={del.isPending} onClick={() => window.confirm(`刪除這筆全域掉寶（#${row.original.id}）？`) && del.mutate(row.original.id)}>
              <Trash2 />
            </Button>
          </div>
        ),
      },
    ],
    [del],
  )

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" onClick={() => setEditing('new')}>
          <Plus data-icon="inline-start" />
          新增全域掉寶
        </Button>
        <span className="ml-auto text-xs text-muted-foreground">所有怪都可能掉；範圍 -1 = 全部，1 位數 = 大洲、2 位數 = 區域、3 位數 = 地圖前綴。chance 為 0 的列不會載入。</span>
      </div>
      <QueryError error={list.error} />
      <DataTable columns={columns} data={list.data ?? []} getRowId={(r) => String(r.id)} loading={list.isPending} emptyMessage="沒有全域掉寶" />
      <GlobalDropDialog row={editing} onClose={() => setEditing(null)} />
    </div>
  )
}

function GlobalDropDialog({ row, onClose }: { row: GlobalDropRow | 'new' | null; onClose: () => void }) {
  const save = useSaveGlobalDrop()
  const [item, setItem] = useState<NameHit | null>(null)
  const [f, setF] = useState({ continent: '-1', dropType: '0', min: '1', max: '1', questid: '0', chance: '10000', comments: '' })
  useEffect(() => {
    if (row === null) return
    if (row === 'new') {
      setItem(null)
      setF({ continent: '-1', dropType: '0', min: '1', max: '1', questid: '0', chance: '10000', comments: '' })
    } else {
      setItem({ id: row.itemid, name: row.itemName ?? '' })
      setF({ continent: String(row.continent), dropType: String(row.dropType), min: String(row.minimum_quantity), max: String(row.maximum_quantity), questid: String(row.questid), chance: String(row.chance), comments: row.comments ?? '' })
    }
    save.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [row])

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!item) return
    save.mutate(
      {
        id: row && row !== 'new' ? row.id : undefined,
        patch: { continent: Number(f.continent), dropType: Number(f.dropType), itemid: item.id, minimum_quantity: Number(f.min), maximum_quantity: Number(f.max), questid: Number(f.questid), chance: Number(f.chance), comments: f.comments },
      },
      { onSuccess: onClose },
    )
  }

  return (
    <Dialog open={row !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{row === 'new' ? '新增全域掉寶' : `編輯全域掉寶 #${typeof row === 'object' && row ? row.id : ''}`}</DialogTitle>
          </DialogHeader>
          <div className="flex flex-col gap-1">
            <Label className="text-xs">道具</Label>
            <NamePicker kind="items" value={item?.id ?? 0} valueName={item?.name} onChange={setItem} />
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            <NumField id="g-continent" label="範圍" value={f.continent} onChange={(v) => setF((s) => ({ ...s, continent: v }))} hint="-1 = 全部" />
            <NumField id="g-type" label="類型" value={f.dropType} onChange={(v) => setF((s) => ({ ...s, dropType: v }))} />
            <NumField id="g-quest" label="任務 ID" value={f.questid} onChange={(v) => setF((s) => ({ ...s, questid: v }))} />
            <NumField id="g-min" label="最小數量" value={f.min} onChange={(v) => setF((s) => ({ ...s, min: v }))} />
            <NumField id="g-max" label="最大數量" value={f.max} onChange={(v) => setF((s) => ({ ...s, max: v }))} />
            <NumField id="g-chance" label="機率" value={f.chance} onChange={(v) => setF((s) => ({ ...s, chance: v }))} hint={Number.isFinite(Number(f.chance)) ? chancePercent(Number(f.chance)) : ''} />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="g-comments" className="text-xs">
              備註
            </Label>
            <Input id="g-comments" value={f.comments} onChange={(e) => setF((s) => ({ ...s, comments: e.target.value }))} maxLength={45} className="h-8 text-xs" />
          </div>
          {save.isError && (
            <Alert variant="destructive">
              <AlertTitle>存檔失敗</AlertTitle>
              <AlertDescription>{errorMessage(save.error)}</AlertDescription>
            </Alert>
          )}
          <DialogFooter>
            <Button type="submit" size="sm" disabled={save.isPending || !item}>
              {save.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Save data-icon="inline-start" />}
              存檔並重載掉寶
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/* ---------------------------------------------------------------------- */
/* 商店                                                                     */
/* ---------------------------------------------------------------------- */

function ShopsTab() {
  const shops = useShops()
  const [q, setQ] = useState('')
  const [selected, setSelected] = useState<number | null>(null)
  const create = useCreateShop()
  const [newNpc, setNewNpc] = useState<NameHit | null>(null)
  const needle = q.trim().toLowerCase()
  const rows = useMemo(
    () => (shops.data ?? []).filter((s) => !needle || String(s.shopid).startsWith(needle) || String(s.npcid).startsWith(needle) || (s.npcName ?? '').toLowerCase().includes(needle)),
    [shops.data, needle],
  )

  return (
    <div className="grid gap-4 xl:grid-cols-[20rem_1fr]">
      <div className="flex flex-col gap-2">
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="NPC 名稱 / NPC id / 商店 id" className="pl-8" />
        </div>
        <QueryError error={shops.error} />
        <Card className="gap-2 py-2">
          <CardContent className="px-1">
            <ul className="flex max-h-[55svh] flex-col gap-0.5 overflow-y-auto">
              {rows.map((s) => (
                <li key={s.shopid}>
                  <button
                    type="button"
                    onClick={() => setSelected(s.shopid)}
                    className={cn('flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted', selected === s.shopid && 'bg-accent text-accent-foreground')}
                  >
                    <span className="truncate">{s.npcName ?? <span className="text-muted-foreground">（未知 NPC）</span>}</span>
                    <span className="ml-auto shrink-0 text-[11px] text-muted-foreground tabular-nums">
                      #{s.shopid} · {s.items} 項
                    </span>
                  </button>
                </li>
              ))}
              {shops.data && rows.length === 0 && <li className="px-2 py-3 text-xs text-muted-foreground">沒有符合的商店</li>}
            </ul>
          </CardContent>
        </Card>
        <form
          className="flex flex-col gap-2 rounded-lg border p-2"
          onSubmit={(e) => {
            e.preventDefault()
            if (!newNpc) return
            create.mutate(newNpc.id, {
              onSuccess: (r) => {
                setNewNpc(null)
                if (r.shopid) setSelected(r.shopid)
              },
            })
          }}
        >
          <Label className="text-xs">為 NPC 建立商店</Label>
          <NamePicker kind="npcs" value={newNpc?.id ?? 0} valueName={newNpc?.name} onChange={setNewNpc} placeholder="NPC 名稱或 id" />
          <Button type="submit" size="sm" variant="secondary" disabled={!newNpc || create.isPending}>
            <Plus data-icon="inline-start" />
            建立商店
          </Button>
          {create.isError && <span className="text-xs text-red-600 dark:text-red-400">{errorMessage(create.error)}</span>}
        </form>
      </div>
      <div className="min-w-0">
        {selected === null ? (
          <Card className="py-10">
            <CardContent className="text-center text-sm text-muted-foreground">從左側選一間商店</CardContent>
          </Card>
        ) : (
          <ShopEditor shopid={selected} onDeleted={() => setSelected(null)} />
        )}
      </div>
    </div>
  )
}

function ShopEditor({ shopid, onDeleted }: { shopid: number; onDeleted: () => void }) {
  const shop = useShop(shopid)
  const add = useAddShopItem()
  const update = useUpdateShopItem()
  const del = useDeleteShopItem()
  const reorder = useReorderShop()
  const delShop = useDeleteShop()
  const [newItem, setNewItem] = useState<NameHit | null>(null)
  const [newPrice, setNewPrice] = useState('0')
  const [editing, setEditing] = useState<ShopItemRow | null>(null)
  const items = shop.data?.items ?? []

  function move(index: number, dir: -1 | 1) {
    const ids = items.map((i) => i.shopitemid)
    const j = index + dir
    if (j < 0 || j >= ids.length) return
    ;[ids[index], ids[j]] = [ids[j], ids[index]]
    reorder.mutate({ shopid, ids })
  }

  const busy = add.isPending || update.isPending || del.isPending || reorder.isPending
  const err = add.error ?? update.error ?? del.error ?? reorder.error ?? delShop.error

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-medium">
          {shop.data?.npcName ?? '…'} <span className="text-sm font-normal text-muted-foreground">商店 #{shopid} · NPC {shop.data?.npcid}</span>
        </h2>
        <Button size="sm" variant="ghost" className="ml-auto text-red-600 dark:text-red-400" disabled={delShop.isPending} onClick={() => window.confirm(`刪除整間商店 #${shopid} 與它的 ${items.length} 個品項？`) && delShop.mutate(shopid, { onSuccess: onDeleted })}>
          <Trash2 data-icon="inline-start" />
          刪除商店
        </Button>
      </div>
      <QueryError error={shop.error} />
      {err && (
        <Alert variant="destructive">
          <AlertTitle>操作失敗</AlertTitle>
          <AlertDescription>{errorMessage(err)}</AlertDescription>
        </Alert>
      )}
      <div className="overflow-x-auto rounded-lg border">
        <table className="w-full text-sm">
          <thead className="bg-muted/50 text-xs text-muted-foreground">
            <tr>
              <th className="px-2 py-1.5 text-left">#</th>
              <th className="px-2 py-1.5 text-left">道具</th>
              <th className="px-2 py-1.5 text-right">價格</th>
              <th className="px-2 py-1.5 text-right">pitch</th>
              <th className="px-2 py-1.5 text-left">需求道具</th>
              <th className="px-2 py-1.5 text-right">position</th>
              <th className="px-2 py-1.5" />
            </tr>
          </thead>
          <tbody className="divide-y">
            {items.map((it, i) => (
              <tr key={it.shopitemid} className="hover:bg-muted/30">
                <td className="px-2 py-1 text-xs text-muted-foreground tabular-nums">{i + 1}</td>
                <td className="px-2 py-1">
                  <ItemLabel id={it.itemid} name={it.itemName} />
                </td>
                <td className="px-2 py-1 text-right tabular-nums">{formatNumber(it.price)}</td>
                <td className="px-2 py-1 text-right tabular-nums">{it.pitch}</td>
                <td className="px-2 py-1 text-xs">{it.reqitem > 0 ? <span>{it.reqitemName ?? it.reqitem} × {it.reqitemq}</span> : <span className="text-muted-foreground">—</span>}</td>
                <td className="px-2 py-1 text-right font-mono text-xs text-muted-foreground tabular-nums">{it.position}</td>
                <td className="px-2 py-1">
                  <div className="flex justify-end gap-0.5">
                    <Button size="icon-xs" variant="ghost" title="上移" disabled={busy || i === 0} onClick={() => move(i, -1)}>
                      <ArrowUp />
                    </Button>
                    <Button size="icon-xs" variant="ghost" title="下移" disabled={busy || i === items.length - 1} onClick={() => move(i, 1)}>
                      <ArrowDown />
                    </Button>
                    <Button size="icon-xs" variant="ghost" title="編輯" onClick={() => setEditing(it)}>
                      <Pencil />
                    </Button>
                    <Button size="icon-xs" variant="ghost" title="移除" disabled={busy} onClick={() => del.mutate(it.shopitemid)}>
                      <Trash2 />
                    </Button>
                  </div>
                </td>
              </tr>
            ))}
            {shop.data && items.length === 0 && (
              <tr>
                <td colSpan={7} className="px-2 py-6 text-center text-sm text-muted-foreground">
                  這間商店沒有品項
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <form
        className="flex flex-wrap items-end gap-2 rounded-lg border p-2"
        onSubmit={(e) => {
          e.preventDefault()
          if (!newItem) return
          add.mutate({ shopid, patch: { itemid: newItem.id, price: Number(newPrice) || 0 } }, { onSuccess: () => setNewItem(null) })
        }}
      >
        <div className="flex min-w-64 flex-1 flex-col gap-1">
          <Label className="text-xs">加入道具（排在最後，position 自動 +4）</Label>
          <NamePicker kind="items" value={newItem?.id ?? 0} valueName={newItem?.name} onChange={setNewItem} />
        </div>
        <NumField id="shop-new-price" label="價格" value={newPrice} onChange={setNewPrice} />
        <Button type="submit" size="sm" disabled={!newItem || busy}>
          <Plus data-icon="inline-start" />
          加入
        </Button>
      </form>
      <p className="text-xs text-muted-foreground">每次改動都會清掉商店快取（MapleShopFactory.clear），玩家下次開店就是新內容；已開著的商店視窗要重開。</p>
      <ShopItemDialog row={editing} onClose={() => setEditing(null)} onSave={(patch) => editing && update.mutate({ shopitemid: editing.shopitemid, patch }, { onSuccess: () => setEditing(null) })} pending={update.isPending} error={update.error} />
    </div>
  )
}

function ShopItemDialog({ row, onClose, onSave, pending, error }: { row: ShopItemRow | null; onClose: () => void; onSave: (patch: { price: number; pitch: number; reqitem: number; reqitemq: number; itemid: number }) => void; pending: boolean; error: unknown }) {
  const [item, setItem] = useState<NameHit | null>(null)
  const [req, setReq] = useState<NameHit | null>(null)
  const [f, setF] = useState({ price: '0', pitch: '0', reqitemq: '0' })
  useEffect(() => {
    if (!row) return
    setItem({ id: row.itemid, name: row.itemName ?? '' })
    setReq(row.reqitem > 0 ? { id: row.reqitem, name: row.reqitemName ?? '' } : null)
    setF({ price: String(row.price), pitch: String(row.pitch), reqitemq: String(row.reqitemq) })
  }, [row])
  return (
    <Dialog open={row !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <form
          onSubmit={(e) => {
            e.preventDefault()
            if (!item) return
            onSave({ itemid: item.id, price: Number(f.price) || 0, pitch: Number(f.pitch) || 0, reqitem: req?.id ?? 0, reqitemq: req ? Number(f.reqitemq) || 0 : 0 })
          }}
          className="flex flex-col gap-4"
        >
          <DialogHeader>
            <DialogTitle>編輯品項 #{row?.shopitemid}</DialogTitle>
          </DialogHeader>
          <div className="flex flex-col gap-1">
            <Label className="text-xs">道具</Label>
            <NamePicker kind="items" value={item?.id ?? 0} valueName={item?.name} onChange={setItem} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <NumField id="si-price" label="價格（楓幣）" value={f.price} onChange={(v) => setF((s) => ({ ...s, price: v }))} />
            <NumField id="si-pitch" label="pitch" value={f.pitch} onChange={(v) => setF((s) => ({ ...s, pitch: v }))} hint="以其他道具計價時的單位" />
          </div>
          <div className="flex flex-col gap-1">
            <Label className="text-xs">需求道具（以物易物；留空 = 用楓幣）</Label>
            <NamePicker kind="items" value={req?.id ?? 0} valueName={req?.name} onChange={setReq} />
          </div>
          {req && <NumField id="si-reqq" label="需求數量" value={f.reqitemq} onChange={(v) => setF((s) => ({ ...s, reqitemq: v }))} />}
          {error != null && (
            <Alert variant="destructive">
              <AlertTitle>存檔失敗</AlertTitle>
              <AlertDescription>{errorMessage(error)}</AlertDescription>
            </Alert>
          )}
          <DialogFooter>
            <Button type="submit" size="sm" disabled={pending || !item}>
              {pending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Save data-icon="inline-start" />}
              存檔
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/* ---------------------------------------------------------------------- */
/* 商城                                                                     */
/* ---------------------------------------------------------------------- */

function CashTab() {
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState('')
  const [page, setPage] = useState(1)
  const [dq, setDq] = useState('')
  useEffect(() => {
    const t = setTimeout(() => setDq(q), 350)
    return () => clearTimeout(t)
  }, [q])
  useEffect(() => setPage(1), [dq, filter])
  const list = useCashItems(dq, filter, page, PAGE_SIZE)
  const del = useDeleteCashItem()
  const [editing, setEditing] = useState<CashItemRow | 'new' | null>(null)

  const columns = useMemo<ColumnDef<CashItemRow, unknown>[]>(
    () => [
      { accessorKey: 'serial', header: 'SN', meta: { className: 'tabular-nums w-24' } },
      {
        id: 'item',
        header: '道具',
        accessorFn: (r) => r.name,
        cell: ({ row }) => (
          <span>
            {row.original.name}
            <span className="ml-1 font-mono text-xs text-muted-foreground">{row.original.resolvedItemId || ''}</span>
          </span>
        ),
      },
      {
        id: 'price',
        header: '價格',
        accessorFn: (r) => r.effectivePrice ?? -1,
        meta: { className: 'tabular-nums text-right' },
        cell: ({ row }) => (
          <span>
            {row.original.effectivePrice === null ? '—' : formatNumber(row.original.effectivePrice)}
            {row.original.discount_price >= 0 && <span className="ml-1 text-[10px] text-amber-600 dark:text-amber-400">折扣</span>}
            {row.original.meso > 0 && <span className="ml-1 text-[10px] text-muted-foreground">楓幣</span>}
          </span>
        ),
      },
      {
        id: 'flags',
        header: '狀態',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="flex flex-wrap gap-1">
            {row.original.showup > 0 ? <StatusBadge tone="good">上架</StatusBadge> : <StatusBadge tone="neutral">隱藏</StatusBadge>}
            {row.original.mark === 0 && <StatusBadge tone="warning">NEW</StatusBadge>}
            {row.original.package > 0 && <StatusBadge tone="neutral">套組</StatusBadge>}
          </div>
        ),
      },
      { accessorKey: 'period', header: '天數', meta: { className: 'tabular-nums text-right' }, cell: ({ getValue }) => (getValue() as number) || '' },
      { accessorKey: 'count', header: '數量', meta: { className: 'tabular-nums text-right' }, cell: ({ getValue }) => (getValue() as number) || '' },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="flex justify-end gap-1">
            <Button size="icon-xs" variant="ghost" title="編輯" onClick={() => setEditing(row.original)}>
              <Pencil />
            </Button>
            <Button size="icon-xs" variant="ghost" title="刪除" disabled={del.isPending} onClick={() => window.confirm(`刪除 SN ${row.original.serial} 的修改資料？（商城會回到 Commodity.img 的原始設定）`) && del.mutate(row.original.serial)}>
              <Trash2 />
            </Button>
          </div>
        ),
      },
    ],
    [del],
  )

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-64 flex-1">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="名稱 / SN / 道具 id" className="pl-8" />
        </div>
        <Select value={filter || 'all'} onValueChange={(v) => setFilter(v === 'all' ? '' : v)}>
          <SelectTrigger size="sm" className="w-32" aria-label="篩選">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">全部</SelectItem>
            <SelectItem value="showup">上架中</SelectItem>
            <SelectItem value="hidden">隱藏</SelectItem>
            <SelectItem value="discount">有折扣</SelectItem>
          </SelectContent>
        </Select>
        <Button size="sm" onClick={() => setEditing('new')}>
          <Plus data-icon="inline-start" />
          新增
        </Button>
        {del.isError && <span className="text-xs text-red-600 dark:text-red-400">{errorMessage(del.error)}</span>}
      </div>
      <QueryError error={list.error} />
      <DataTable columns={columns} data={list.data?.items ?? []} getRowId={(r) => String(r.serial)} loading={list.isPending} emptyMessage="沒有符合的商城道具" />
      {list.data && <Pager page={list.data.page} size={list.data.size} total={list.data.total} onPage={setPage} />}
      <p className="text-xs text-muted-foreground">分頁載入（每頁 {PAGE_SIZE} 筆）。存檔後會重讀 cashshop_modified_items 進記憶體；已開著商城的玩家要重開商城。</p>
      <CashDialog row={editing} onClose={() => setEditing(null)} />
    </div>
  )
}

const CASH_FIELDS: { key: keyof CashItemRow; label: string; hint?: string }[] = [
  { key: 'discount_price', label: '折扣價', hint: '-1 = 不折扣' },
  { key: 'meso', label: '楓幣價', hint: '>0 時以楓幣計價' },
  { key: 'mark', label: '標記', hint: '0 = NEW，-1 = 無' },
  { key: 'priority', label: '優先', hint: '>= 0 會加上 0x10 旗標' },
  { key: 'period', label: '天數', hint: '0 = 用原設定' },
  { key: 'count', label: '數量', hint: '0 = 用原設定' },
  { key: 'gender', label: '性別', hint: '0 男 1 女 2 不限' },
  { key: 'extra_flags', label: 'extra_flags' },
  { key: 'unk_1', label: 'unk_1' },
  { key: 'unk_2', label: 'unk_2' },
  { key: 'unk_3', label: 'unk_3' },
]

function CashDialog({ row, onClose }: { row: CashItemRow | 'new' | null; onClose: () => void }) {
  const save = useSaveCashItem()
  const [serial, setSerial] = useState('')
  const [name, setName] = useState('')
  const [item, setItem] = useState<NameHit | null>(null)
  const [showup, setShowup] = useState('1')
  const [pkg, setPkg] = useState('0')
  const [f, setF] = useState<Record<string, string>>({})
  useEffect(() => {
    if (row === null) return
    if (row === 'new') {
      setSerial('')
      setName('')
      setItem(null)
      setShowup('1')
      setPkg('0')
      const d: Record<string, string> = {}
      for (const c of CASH_FIELDS) d[c.key] = c.key === 'discount_price' || c.key === 'mark' || c.key === 'priority' ? '-1' : c.key === 'gender' ? '2' : '0'
      setF(d)
    } else {
      setSerial(String(row.serial))
      setName(row.name)
      setItem(row.itemid > 0 ? { id: row.itemid, name: row.itemName ?? '' } : null)
      setShowup(String(row.showup))
      setPkg(String(row.package))
      const d: Record<string, string> = {}
      for (const c of CASH_FIELDS) d[c.key] = String(row[c.key])
      setF(d)
    }
    save.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [row])

  function submit(e: FormEvent) {
    e.preventDefault()
    const patch: Record<string, unknown> = { name, showup: Number(showup), package: Number(pkg), itemid: item?.id ?? 0 }
    for (const c of CASH_FIELDS) patch[c.key] = Number(f[c.key]) || 0
    save.mutate({ serial: Number(serial), create: row === 'new', patch }, { onSuccess: onClose })
  }

  return (
    <Dialog open={row !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{row === 'new' ? '新增商城修改資料' : `商城 SN ${typeof row === 'object' && row ? row.serial : ''}`}</DialogTitle>
            <DialogDescription>這張表覆蓋 Commodity.img 的設定：折扣價、上架與否、天數等；0 / -1 代表沿用原始值。</DialogDescription>
          </DialogHeader>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            <div className="flex flex-col gap-1">
              <Label htmlFor="cs-serial" className="text-xs">
                SN（serial）
              </Label>
              <Input id="cs-serial" type="number" inputMode="numeric" value={serial} onChange={(e) => setSerial(e.target.value)} disabled={row !== 'new'} className="h-8 font-mono text-xs" required />
            </div>
            <div className="col-span-2 flex flex-col gap-1">
              <Label htmlFor="cs-name" className="text-xs">
                名稱
              </Label>
              <Input id="cs-name" value={name} onChange={(e) => setName(e.target.value)} maxLength={70} className="h-8 text-xs" />
            </div>
            <div className="col-span-2 flex flex-col gap-1 sm:col-span-3">
              <Label className="text-xs">道具（留空 = 用 Commodity.img 的道具）</Label>
              <NamePicker kind="items" value={item?.id ?? 0} valueName={item?.name} onChange={setItem} />
            </div>
            <div className="flex flex-col gap-1">
              <Label className="text-xs">上架</Label>
              <Select value={showup} onValueChange={setShowup}>
                <SelectTrigger size="sm" className="h-8 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="1">上架（用這裡的設定）</SelectItem>
                  <SelectItem value="0">隱藏 / 沿用原設定</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1">
              <Label className="text-xs">套組</Label>
              <Select value={pkg} onValueChange={setPkg}>
                <SelectTrigger size="sm" className="h-8 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="0">否</SelectItem>
                  <SelectItem value="1">是</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {CASH_FIELDS.map((c) => (
              <NumField key={c.key} id={`cs-${c.key}`} label={c.label} value={f[c.key] ?? ''} onChange={(v) => setF((s) => ({ ...s, [c.key]: v }))} hint={c.hint} />
            ))}
          </div>
          {save.isError && (
            <Alert variant="destructive">
              <AlertTitle>存檔失敗</AlertTitle>
              <AlertDescription>{errorMessage(save.error)}</AlertDescription>
            </Alert>
          )}
          <DialogFooter>
            <Button type="submit" size="sm" disabled={save.isPending || !serial}>
              {save.isPending ? <LoaderCircle className="animate-spin" data-icon="inline-start" /> : <Save data-icon="inline-start" />}
              存檔並重載商城
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
