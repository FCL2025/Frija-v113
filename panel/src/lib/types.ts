/**
 * 管理面板 API 回應型別，對應 docs/panel-api.md（Phase 0–1）。
 * 時間欄位一律是 epoch 毫秒。
 */

/** GET /api/ping */
export interface PingResponse {
  ok: boolean
  version: number
  patch: string
  serverName: string
  uptimeMs: number
  /** 伺服器從 JVM 啟動到 Loaded Complete 的耗時 */
  startupMs: number
  /** JVM 啟動時刻 */
  startedAt: number
}

export interface DbStatus {
  active: number
  idle: number
  poolSize: number
}

export interface JvmStatus {
  uptimeMs: number
  heapUsed: number
  heapCommitted: number
  heapMax: number
  threads: number
  cores: number
  javaVersion: string
}

/** GET /api/status */
export interface StatusResponse {
  serverName: string
  eventMessage: string
  /** 來自 World.getConnected()，即時值 */
  online: number
  userLimit: number
  channelCount: number
  /** key 是頻道編號（字串），value 是該頻道人數 */
  perChannel: Record<string, number>
  loginSessions: number
  adminOnly: boolean
  shutdown: boolean
  db: DbStatus
  jvm: JvmStatus
}

/** GET /api/channels 的單一元素，依 channel 遞增排序 */
export interface ChannelInfo {
  channel: number
  port: number
  ip: string
  loadedMaps: number
  connected: number
  expRate: number
  mesoRate: number
  dropRate: number
  cashRate: number
  serverMessage: string
  adminOnly: boolean
  shutdown: boolean
  megaphoneMuted: boolean
}

/** GET /api/players 的單一元素 */
export interface PlayerInfo {
  id: number
  name: string
  level: number
  job: number
  jobName: string
  channel: number
  mapId: number
  mapName: string
  accountId: number
  accountName: string
  gmLevel: number
  hidden: boolean
  ip: string
}

/** GET /api/logs 的單一元素 */
export interface LogFileInfo {
  name: string
  size: number
  modified: number
}

/** GET /api/logs/{name}?lines=N */
export interface LogTailResponse extends LogFileInfo {
  lines: string[]
  /** true 表示檔案還有更早的內容沒回傳 */
  truncated: boolean
}

/** 錯誤回應 */
export interface ErrorBody {
  error: string
  /** 部分 400 會附上結構化資料（例如無法以 BIG5 儲存的字元清單） */
  details?: unknown
}

/* ---------------------------------------------------------------------- */
/* Phase 2–3：設定 / 動作                                                   */
/* ---------------------------------------------------------------------- */

/** 設定項的生效方式：live 有 setter 立即生效；memory 只改記憶體、下次讀取生效；restart 只能重啟 */
export type ApplyMode = 'live' | 'memory' | 'restart'

/** GET /api/settings 的單一項目 */
export interface SettingEntry {
  key: string
  /** secret 時為 null；active=false（只有註解掉的預設值）時也是 null */
  value: string | null
  /** false 表示檔案裡只有 `#key = default` 這種註解掉的預設值 */
  active: boolean
  secret: boolean
  commentedDefault: string | null
  apply: ApplyMode
  description: string
  /** 伺服器現在實際用的值（倍率取頻道 1）；secret 時為 null */
  runtimeValue: string | null
  /** 後端認得這個 key（有分類與說明） */
  known: boolean
}

/** GET /api/settings */
export interface SettingsResponse {
  path: string
  bom: boolean
  entries: SettingEntry[]
}

export interface SaveOutcome {
  ok: boolean
  changed?: boolean
  error?: string
}

export interface ApplyOutcome {
  ok: boolean
  mode?: ApplyMode
  skipped?: boolean
  error?: string
}

/** PUT /api/settings/{key} */
export interface PutSettingResponse {
  key: string
  value: string | null
  apply: ApplyMode
  saved: SaveOutcome
  applied: ApplyOutcome
}

export interface RatesRequest {
  exp?: number
  meso?: number
  drop?: number
  cash?: number
}

/** POST /api/rates */
export interface RatesResponse {
  applied: { ok: boolean; channels: number }
  saved: SaveOutcome
  rates: RatesRequest
}

/** POST /api/server-message */
export interface ServerMessageResponse {
  applied: { ok: boolean; channels: number }
  saved: SaveOutcome
}

export type BroadcastType = 'notice' | 'popup' | 'scroll' | 'pink' | 'blue'

/** POST /api/broadcast */
export interface BroadcastResponse {
  ok: boolean
  type: BroadcastType
  recipients: number
}

export type ReloadTarget = 'ops' | 'drops' | 'portal' | 'shops' | 'quests' | 'events' | 'ipmonitor'

/** POST /api/reload/{target} */
export interface ReloadResponse {
  ok: boolean
  target: ReloadTarget
  elapsedMs: number
  errors: string[]
}

/** POST /api/shutdown */
export interface ShutdownResponse {
  ok: boolean
  alreadyRunning: boolean
}

/* ---------------------------------------------------------------------- */
/* Phase 6：腳本                                                            */
/* ---------------------------------------------------------------------- */

export type ScriptKind = 'npc' | 'portal' | 'quest' | 'reactor' | 'event'

/** GET /api/scripts/kinds 的單一元素 */
export interface ScriptKindInfo {
  kind: ScriptKind
  count: number
}

/** GET /api/scripts/list/{kind} 的單一元素 */
export interface ScriptFileInfo {
  name: string
  size: number
  modified: number
}

/** GET /api/scripts/search 的單一命中 */
export interface ScriptSearchHit {
  kind: ScriptKind
  name: string
  line: number
  text: string
}

export interface ScriptSearchResponse {
  q: string
  hits: ScriptSearchHit[]
  filesMatched: number
  capped: boolean
}

/** GET /api/scripts/file/{kind}/{name} */
export interface ScriptFileResponse {
  kind: ScriptKind
  name: string
  /** 已轉成 UTF-8 的內容 */
  content: string
  size: number
  modified: number
  /** false 表示檔案不是合法 BIG5，遊戲讀不到它（存檔即修復） */
  encodingOk: boolean
  detectedEncoding: 'BIG5' | 'UTF-8' | 'unknown'
  /** 存檔後對執行中伺服器的影響說明 */
  afterSave: string
}

/** PUT /api/scripts/file/{kind}/{name} */
export interface ScriptSaveResponse {
  ok: boolean
  kind: ScriptKind
  name: string
  bytes: number
  created: boolean
  modified: number
  reloaded: 'portal' | null
  afterSave: string
}

/** 400 時 details.unencodable 的元素 */
export interface UnencodableChar {
  line: number
  col: number
  char: string
  codePoint: string
}
