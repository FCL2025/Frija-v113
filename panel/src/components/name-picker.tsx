import { useEffect, useId, useRef, useState } from 'react'
import { LoaderCircle, Search, X } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { useNames } from '@/lib/queries'
import type { NameHit } from '@/lib/types'
import { cn } from '@/lib/utils'

export interface NamePickerProps {
  kind: 'items' | 'mobs' | 'npcs'
  /** 目前選到的 id（0 = 未選） */
  value: number
  /** 已知名稱（顯示用；沒有就只顯示 id） */
  valueName?: string | null
  onChange: (hit: NameHit | null) => void
  placeholder?: string
  className?: string
  autoFocus?: boolean
  /** 允許輸入一個沒有名稱的 id（例如楓幣 0 或 WZ 裡沒有的道具） */
  allowRawId?: boolean
}

/**
 * 道具 / 怪物 / NPC 的名稱搜尋框：打字查 /api/names/*，從下拉清單選一個；
 * 也可以直接輸入 id 按 Enter。名稱來自伺服器已有的快取，不會去讀 wz。
 */
export function NamePicker({ kind, value, valueName, onChange, placeholder, className, autoFocus, allowRawId = true }: NamePickerProps) {
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const dq = useDebounced(q, 250)
  const hits = useNames(kind, dq)
  const listId = useId()
  const wrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  const list = hits.data ?? []

  function pick(hit: NameHit) {
    onChange(hit)
    setQ('')
    setOpen(false)
  }

  function onKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((a) => Math.min(list.length - 1, a + 1))
      setOpen(true)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((a) => Math.max(0, a - 1))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (open && list[active]) pick(list[active])
      else if (allowRawId && /^\d+$/.test(q.trim())) pick({ id: Number(q.trim()), name: '' })
    } else if (e.key === 'Escape') {
      setOpen(false)
    }
  }

  return (
    <div ref={wrapRef} className={cn('relative', className)}>
      {value > 0 || (value === 0 && valueName) ? (
        <div className="flex h-9 items-center gap-2 rounded-md border bg-muted/40 px-2 text-sm">
          <span className="truncate">
            {valueName || <span className="text-muted-foreground">（無名稱）</span>}
            <span className="ml-1 font-mono text-xs text-muted-foreground">#{value}</span>
          </span>
          <button type="button" className="ml-auto rounded p-0.5 hover:bg-muted" onClick={() => onChange(null)} aria-label="清除">
            <X className="size-3.5" />
          </button>
        </div>
      ) : (
        <>
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input
            value={q}
            autoFocus={autoFocus}
            onChange={(e) => {
              setQ(e.target.value)
              setActive(0)
              setOpen(true)
            }}
            onFocus={() => setOpen(true)}
            onKeyDown={onKey}
            placeholder={placeholder ?? '輸入名稱或 id 搜尋'}
            className="pl-8"
            role="combobox"
            aria-expanded={open}
            aria-controls={listId}
            aria-autocomplete="list"
          />
          {hits.isFetching && <LoaderCircle className="absolute top-1/2 right-2.5 size-3.5 -translate-y-1/2 animate-spin text-muted-foreground" aria-hidden />}
          {open && q.trim() && (
            <ul id={listId} role="listbox" className="absolute z-20 mt-1 max-h-64 w-full overflow-y-auto rounded-md border bg-popover p-1 text-sm shadow-md">
              {list.length === 0 && !hits.isFetching && (
                <li className="px-2 py-1.5 text-xs text-muted-foreground">
                  沒有符合的名稱{allowRawId && /^\d+$/.test(q.trim()) ? '，按 Enter 直接使用這個 id' : ''}
                </li>
              )}
              {list.map((h, i) => (
                <li
                  key={h.id}
                  role="option"
                  aria-selected={i === active}
                  onMouseDown={(e) => {
                    e.preventDefault()
                    pick(h)
                  }}
                  onMouseEnter={() => setActive(i)}
                  className={cn('flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5', i === active && 'bg-accent text-accent-foreground')}
                >
                  <span className="truncate">{h.name}</span>
                  <span className="ml-auto font-mono text-xs text-muted-foreground">{h.id}</span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  )
}

function useDebounced(value: string, ms: number) {
  const [v, setV] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return v
}
