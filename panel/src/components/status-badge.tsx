import { Check, Minus, type LucideIcon } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

export type StatusTone = 'good' | 'warning' | 'critical' | 'neutral'

const toneClass: Record<StatusTone, string> = {
  good: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-400',
  warning: 'bg-amber-500/10 text-amber-700 dark:text-amber-400',
  critical: 'bg-red-500/10 text-red-700 dark:text-red-400',
  neutral: 'bg-muted text-muted-foreground',
}

export interface StatusBadgeProps {
  tone: StatusTone
  icon?: LucideIcon
  children: React.ReactNode
  className?: string
}

/** 狀態徽章：顏色 + 圖示 + 文字，不靠顏色單獨表意 */
export function StatusBadge({ tone, icon: Icon, children, className }: StatusBadgeProps) {
  return (
    <Badge variant="outline" className={cn('border-transparent', toneClass[tone], className)}>
      {Icon && <Icon data-icon="inline-start" aria-hidden />}
      {children}
    </Badge>
  )
}

/** 布林旗標：true 顯示 onLabel（onTone），false 顯示 offLabel（中性） */
export function FlagBadge({
  value,
  onLabel,
  offLabel,
  onTone = 'warning',
  onIcon,
}: {
  value: boolean
  onLabel: string
  offLabel: string
  onTone?: StatusTone
  onIcon?: LucideIcon
}) {
  return value ? (
    <StatusBadge tone={onTone} icon={onIcon ?? Check}>
      {onLabel}
    </StatusBadge>
  ) : (
    <StatusBadge tone="neutral" icon={Minus}>
      {offLabel}
    </StatusBadge>
  )
}
