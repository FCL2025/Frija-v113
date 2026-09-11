import type { LucideIcon } from 'lucide-react'
import { Code2, Database, FileText, LayoutDashboard, Radio, Settings2, UserCog, Users, Zap } from 'lucide-react'

export type SectionId =
  | 'dashboard'
  | 'channels'
  | 'players'
  | 'logs'
  | 'settings'
  | 'actions'
  | 'scripts'
  | 'accounts'
  | 'data'

export type SectionGroup = '監控' | '管理'

export interface SectionMeta {
  id: SectionId
  label: string
  icon: LucideIcon
  group: SectionGroup
}

export const SECTIONS: readonly SectionMeta[] = [
  { id: 'dashboard', label: '儀表板', icon: LayoutDashboard, group: '監控' },
  { id: 'channels', label: '頻道', icon: Radio, group: '監控' },
  { id: 'players', label: '線上玩家', icon: Users, group: '監控' },
  { id: 'logs', label: '日誌', icon: FileText, group: '監控' },
  { id: 'actions', label: '即時操作', icon: Zap, group: '管理' },
  { id: 'settings', label: '設定檔', icon: Settings2, group: '管理' },
  { id: 'accounts', label: '帳號與角色', icon: UserCog, group: '管理' },
  { id: 'data', label: '掉寶與商店', icon: Database, group: '管理' },
  { id: 'scripts', label: '腳本', icon: Code2, group: '管理' },
]

export const SECTION_GROUPS: readonly SectionGroup[] = ['監控', '管理']

export const DEFAULT_SECTION: SectionId = 'dashboard'

export function isSectionId(v: string): v is SectionId {
  return SECTIONS.some((s) => s.id === v)
}

/** 從 location.hash 讀目前的分頁（`#players` → 'players'） */
export function sectionFromHash(hash: string = location.hash): SectionId {
  const id = hash.replace(/^#\/?/, '')
  return isSectionId(id) ? id : DEFAULT_SECTION
}
