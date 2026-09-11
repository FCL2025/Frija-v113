import type { ReactNode } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'

export interface StatCardProps {
  label: string
  /** 主數值 */
  value: ReactNode
  /** 主數值旁的小字（例如「/ 600」） */
  suffix?: ReactNode
  /** 數值底下的補充說明 */
  detail?: ReactNode
  /** 底部區域（例如進度條、徽章） */
  footer?: ReactNode
  icon?: ReactNode
  className?: string
}

/** 儀表板的統計卡片：標籤 + 大數字 + 補充說明 */
export function StatCard({ label, value, suffix, detail, footer, icon, className }: StatCardProps) {
  return (
    <Card className={cn('gap-2 py-4', className)}>
      <CardHeader className="px-4">
        <CardTitle className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
          {icon}
          {label}
        </CardTitle>
      </CardHeader>
      <CardContent className="px-4">
        <div className="flex items-baseline gap-1.5">
          <span className="text-2xl font-semibold">{value}</span>
          {suffix && <span className="text-sm text-muted-foreground">{suffix}</span>}
        </div>
        {detail && <div className="mt-1 text-xs text-muted-foreground">{detail}</div>}
        {footer && <div className="mt-3">{footer}</div>}
      </CardContent>
    </Card>
  )
}

/**
 * 用量計量條：填色依比例變色（一般 → 偏高 → 危險），底色是同一色系較淡的一階。
 * 只用在「已用 / 上限」這種單一數值。
 */
export function Meter({
  value,
  max,
  warnAt = 0.75,
  dangerAt = 0.9,
  className,
  label,
}: {
  value: number
  max: number
  warnAt?: number
  dangerAt?: number
  className?: string
  label?: string
}) {
  const ratio = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0
  const tone = ratio >= dangerAt ? 'danger' : ratio >= warnAt ? 'warn' : 'ok'
  const fill =
    tone === 'danger'
      ? 'bg-red-600 dark:bg-red-500'
      : tone === 'warn'
        ? 'bg-amber-500 dark:bg-amber-400'
        : 'bg-sky-600 dark:bg-sky-400'
  const track =
    tone === 'danger'
      ? 'bg-red-500/15'
      : tone === 'warn'
        ? 'bg-amber-500/15'
        : 'bg-sky-500/15'
  return (
    <div
      role="meter"
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value}
      aria-label={label}
      className={cn('h-1.5 w-full overflow-hidden rounded-full', track, className)}
    >
      <div
        className={cn('h-full rounded-full transition-[width] duration-500', fill)}
        style={{ width: `${ratio * 100}%` }}
      />
    </div>
  )
}
