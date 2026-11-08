import { AlertTriangle, CheckCircle2, RotateCcw, XCircle } from 'lucide-react'
import { StatusBadge, type StatusTone } from '@/components/status-badge'
import type { ApplyOutcome, SaveOutcome } from '@/lib/types'

export interface OutcomeProps {
  saved: SaveOutcome
  applied: ApplyOutcome
  /** 「套用」在這個情境的說法（預設「套用」；帳號類會是「生效」） */
  applyWord?: string
}

/**
 * 三態結果：「套用到伺服器」與「寫回檔案」是兩個各自可能失敗的動作，
 * 只做兩態的 UI 遲早會說謊。倍率、設定、公告、（之後的）帳號編輯都共用這一個。
 */
export function Outcome({ saved, applied, applyWord = '套用' }: OutcomeProps) {
  let tone: StatusTone
  let icon
  let label: string
  let detail: string | undefined

  if (saved.ok && applied.ok && applied.mode === 'none') {
    tone = 'neutral'
    icon = CheckCircle2
    label = '已保存（無作用）'
    detail = '沒有程式讀這個設定，改了不會有任何效果'
  } else if (saved.ok && applied.ok) {
    tone = 'good'
    icon = CheckCircle2
    label = `已${applyWord}且已保存`
    detail = applied.note ?? undefined
  } else if (!saved.ok && applied.ok) {
    tone = 'warning'
    icon = AlertTriangle
    label = `僅已${applyWord}`
    detail = `線上已生效，但沒寫進檔案，重啟會回到舊值${saved.error ? `：${saved.error}` : ''}`
  } else if (saved.ok && !applied.ok) {
    if (applied.mode === 'restart') {
      tone = 'warning'
      icon = RotateCcw
      label = '已保存，需重啟'
      detail = '這個設定沒有即時路徑；面板無法重啟伺服器，請手動重跑 Launcher.bat'
    } else if (applied.skipped) {
      tone = 'neutral'
      icon = CheckCircle2
      label = `已保存，未${applyWord}`
      detail = '線上仍是舊值'
    } else {
      tone = 'critical'
      icon = XCircle
      label = `已保存，${applyWord}失敗`
      detail = applied.error
    }
  } else {
    tone = 'critical'
    icon = XCircle
    label = '失敗'
    detail = [saved.error, applied.error].filter(Boolean).join('；') || undefined
  }

  if (saved.ok && saved.changed === false) {
    detail = [detail, '檔案內容原本就相同'].filter(Boolean).join('；')
  }

  return (
    <div className="flex flex-wrap items-center gap-2 text-xs">
      <StatusBadge tone={tone} icon={icon}>
        {label}
      </StatusBadge>
      {detail && <span className="text-muted-foreground">{detail}</span>}
    </div>
  )
}
