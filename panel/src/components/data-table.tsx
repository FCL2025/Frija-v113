import { useState, type ReactNode } from 'react'
import {
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type Row,
  type RowData,
  type SortingState,
  type TableOptions,
} from '@tanstack/react-table'
import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react'
import { cn } from '@/lib/utils'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'

declare module '@tanstack/react-table' {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface ColumnMeta<TData extends RowData, TValue> {
    /** 表頭與儲存格的額外 class（例如 `text-right`、`font-mono`） */
    className?: string
    /** 表頭專用的額外 class */
    headerClassName?: string
    /** 儲存格專用的額外 class */
    cellClassName?: string
  }
}

export interface DataTableProps<TData> {
  columns: ColumnDef<TData, unknown>[]
  data: TData[]
  /** 列的穩定 id（避免輪詢時整表重繪） */
  getRowId?: (row: TData, index: number) => string
  /** 沒有資料時顯示的內容 */
  emptyMessage?: ReactNode
  /** 全域文字篩選（受控）；配合 globalFilterFn */
  globalFilter?: string
  /** 自訂全域篩選；預設對所有欄位值做包含比對 */
  globalFilterFn?: TableOptions<TData>['globalFilterFn']
  /** 預設排序 */
  initialSorting?: SortingState
  /** 每一列的額外 class */
  rowClassName?: (row: Row<TData>) => string | undefined
  className?: string
  /** 載入中且沒有舊資料 */
  loading?: boolean
}

/**
 * 可重用的 TanStack Table 包裝：點表頭排序、全域篩選、空狀態。
 * 欄位定義直接用 TanStack 的 ColumnDef；`meta.className` 控制對齊等樣式。
 */
export function DataTable<TData>({
  columns,
  data,
  getRowId,
  emptyMessage = '沒有資料',
  globalFilter,
  globalFilterFn,
  initialSorting = [],
  rowClassName,
  className,
  loading = false,
}: DataTableProps<TData>) {
  const [sorting, setSorting] = useState<SortingState>(initialSorting)

  const table = useReactTable<TData>({
    data,
    columns,
    state: { sorting, globalFilter: globalFilter ?? '' },
    onSortingChange: setSorting,
    getRowId,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    globalFilterFn: globalFilterFn ?? 'includesString',
    enableSortingRemoval: true,
  })

  const rows = table.getRowModel().rows

  return (
    <div className={cn('overflow-x-auto rounded-lg border', className)}>
      <Table>
        <TableHeader>
          {table.getHeaderGroups().map((hg) => (
            <TableRow key={hg.id} className="hover:bg-transparent">
              {hg.headers.map((header) => {
                const canSort = header.column.getCanSort()
                const sorted = header.column.getIsSorted()
                const meta = header.column.columnDef.meta
                return (
                  <TableHead
                    key={header.id}
                    className={cn(
                      'whitespace-nowrap',
                      meta?.className,
                      meta?.headerClassName,
                      canSort && 'cursor-pointer select-none',
                    )}
                    aria-sort={
                      sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : 'none'
                    }
                    onClick={canSort ? header.column.getToggleSortingHandler() : undefined}
                  >
                    {header.isPlaceholder ? null : (
                      <span className="inline-flex items-center gap-1">
                        {flexRender(header.column.columnDef.header, header.getContext())}
                        {canSort &&
                          (sorted === 'asc' ? (
                            <ArrowUp className="size-3.5" aria-hidden />
                          ) : sorted === 'desc' ? (
                            <ArrowDown className="size-3.5" aria-hidden />
                          ) : (
                            <ArrowUpDown className="size-3.5 opacity-40" aria-hidden />
                          ))}
                      </span>
                    )}
                  </TableHead>
                )
              })}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>
          {rows.length === 0 ? (
            <TableRow className="hover:bg-transparent">
              <TableCell
                colSpan={columns.length}
                className="h-24 text-center text-muted-foreground"
              >
                {loading ? '載入中…' : emptyMessage}
              </TableCell>
            </TableRow>
          ) : (
            rows.map((row) => (
              <TableRow key={row.id} className={rowClassName?.(row)}>
                {row.getVisibleCells().map((cell) => {
                  const meta = cell.column.columnDef.meta
                  return (
                    <TableCell key={cell.id} className={cn(meta?.className, meta?.cellClassName)}>
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </TableCell>
                  )
                })}
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
    </div>
  )
}
