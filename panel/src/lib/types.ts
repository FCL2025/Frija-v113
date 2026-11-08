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

/** 設定項的生效方式：live 存檔即生效（面板或直接編輯 Settings.ini 皆同）；restart 只能重啟；none 沒有程式讀它 */
export type ApplyMode = 'live' | 'restart' | 'none'

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
  /** 成功時伺服器附帶的說明（例如「已開啟頻道 [4]」「管理面板將移到 …」） */
  note?: string | null
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
  applied: { ok: boolean; channels: number; error?: string }
  saved: SaveOutcome
  rates: RatesRequest
}

/** POST /api/server-message */
export interface ServerMessageResponse {
  applied: { ok: boolean; channels: number; error?: string }
  saved: SaveOutcome
}

export type BroadcastType = 'notice' | 'popup' | 'scroll' | 'pink' | 'blue'

/** POST /api/broadcast */
export interface BroadcastResponse {
  ok: boolean
  type: BroadcastType
  recipients: number
}

export type ReloadTarget = 'ops' | 'drops' | 'portal' | 'shops' | 'quests' | 'events' | 'ipmonitor' | 'settings'

/** POST /api/reload/{target} */
export interface ReloadResponse {
  ok: boolean
  target: ReloadTarget
  elapsedMs: number
  errors: string[]
  /** 只有 target = settings：每個有變更的設定的處理結果 */
  applied?: string[]
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

/* ---------------------------------------------------------------------- */
/* Phase 4：帳號 / 角色 / 封鎖                                               */
/* ---------------------------------------------------------------------- */

export interface Paged<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** accounts 的一列（永遠不含 password / salt / 2ndpassword / salt2） */
export interface AccountRow {
  id: number
  name: string
  loggedin: number
  /** 此刻有角色在記憶體裡（比 loggedin 準） */
  online: boolean
  onlineCharacter: string | null
  onlineChannel: number | null
  lastlogin: number | null
  createdat: number | null
  birthday: string | null
  banned: number
  banreason: string | null
  gm: number
  email: string | null
  macs: string | null
  /** 尚未到期的暫時封鎖時間（本機時間字串），沒有則 null */
  tempban: string | null
  greason: number | null
  ACash: number
  mPoints: number
  points: number
  vpoints: number
  gender: number
  sessionIP: string | null
  lastlogon: number | null
  expiration: number | null
}

export interface CharacterRow {
  id: number
  accountId: number
  name: string
  level: number
  exp: number
  job: number
  jobName: string
  str: number
  dex: number
  int: number
  luk: number
  hp: number
  mp: number
  maxhp: number
  maxmp: number
  meso: number
  fame: number
  ap: number
  sp: string
  map: number
  spawnpoint: number
  gm: number
  hair: number
  face: number
  skincolor: number
  gender: number
  party: number
  guildid: number
  guildrank: number
  familyid: number
  marriageId: number
  beans: number
  gachexp: number
  createdate: number | null
  online: boolean
  onlineChannel: number | null
  onlineMap: number | null
  /** 只有 GET /api/characters/{id} 才有 */
  editable?: string[]
}

export interface AccountDetail {
  account: AccountRow
  characters: CharacterRow[]
}

/** PUT /api/accounts/{id} 可寫欄位 */
export interface AccountPatch {
  banned?: number
  banreason?: string
  tempban?: string | null
  gm?: number
  greason?: number
  email?: string
  ACash?: number
  mPoints?: number
  points?: number
  vpoints?: number
  gender?: number
  birthday?: string | null
}

export interface AccountUpdateResponse {
  id: number
  saved: { ok: boolean; columns: string[] }
  applied: { ok: boolean; online: boolean; live: string[]; nextLogin: string[] }
  kicked: boolean
}

export type CharacterPatch = Partial<
  Pick<
    CharacterRow,
    | 'level' | 'exp' | 'str' | 'dex' | 'luk' | 'int' | 'hp' | 'mp' | 'maxhp' | 'maxmp' | 'meso' | 'job'
    | 'fame' | 'ap' | 'map' | 'spawnpoint' | 'gm' | 'hair' | 'face' | 'skincolor' | 'gender' | 'beans' | 'sp'
  >
>

export interface CharacterUpdateResponse {
  id: number
  saved: { ok: boolean; columns: string[] }
  /** 線上角色：live 是已即時生效的欄位，nextLogin 是要重新登入才看得到的欄位 */
  applied: { ok: boolean; online: boolean; error: string | null; live?: string[]; nextLogin?: string[] }
}

export interface KickResponse {
  ok: boolean
  character: string
  channel: number
}

export interface BanRow {
  id: number
  value: string
}

export interface BanList {
  items: BanRow[]
  /** macbans 是 MEMORY 引擎，MySQL 重啟就清空 */
  volatile: boolean
}

/* ---------------------------------------------------------------------- */
/* Phase 5：掉寶 / 商店 / 商城                                               */
/* ---------------------------------------------------------------------- */

export interface NameHit {
  id: number
  name: string
}

export interface DropRow {
  id: number
  dropperid: number
  mobName: string | null
  /** 0 表示楓幣 */
  itemid: number
  itemName: string | null
  minimum_quantity: number
  maximum_quantity: number
  questid: number
  /** 0–999999，分母 1,000,000 */
  chance: number
}

export interface DropsResponse extends Paged<DropRow> {
  mob: NameHit | null
  item: NameHit | null
}

export type DropPatch = Partial<Pick<DropRow, 'dropperid' | 'itemid' | 'minimum_quantity' | 'maximum_quantity' | 'questid' | 'chance'>>

export interface GlobalDropRow {
  id: number
  /** -1 = 全部；1 位數比對大洲、2 位數比對區域、3 位數比對地圖前綴 */
  continent: number
  dropType: number
  itemid: number
  itemName: string | null
  minimum_quantity: number
  maximum_quantity: number
  questid: number
  chance: number
  comments: string | null
}

export type GlobalDropPatch = Partial<Omit<GlobalDropRow, 'id' | 'itemName'>>

export interface ShopSummary {
  shopid: number
  npcid: number
  npcName: string | null
  items: number
}

export interface ShopItemRow {
  shopitemid: number
  itemid: number
  itemName: string | null
  price: number
  pitch: number
  position: number
  reqitem: number
  reqitemName: string | null
  reqitemq: number
}

export interface ShopDetail {
  shopid: number
  npcid: number
  npcName: string | null
  items: ShopItemRow[]
}

export type ShopItemPatch = Partial<Pick<ShopItemRow, 'itemid' | 'price' | 'pitch' | 'position' | 'reqitem' | 'reqitemq'>>

export interface CashItemRow {
  name: string
  serial: number
  discount_price: number
  mark: number
  showup: number
  itemid: number
  priority: number
  package: number
  period: number
  gender: number
  count: number
  meso: number
  unk_1: number
  unk_2: number
  unk_3: number
  extra_flags: number
  /** itemid 為 0 時由 Commodity.img 解析出的道具 */
  resolvedItemId: number
  itemName: string | null
  /** 客戶端實際看到的價格（折扣 / 楓幣價 / 原價） */
  effectivePrice: number | null
}

export type CashItemPatch = Partial<Omit<CashItemRow, 'serial' | 'resolvedItemId' | 'itemName' | 'effectivePrice'>>

export interface WriteResponse {
  ok: boolean
  id?: number
  shopid?: number
  shopitemid?: number
  serial?: number
  position?: number
  reloaded?: 'drops' | 'shops' | 'cashshop'
}
