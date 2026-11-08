import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, apiSend } from '@/lib/api'
import { useRefetchInterval } from '@/lib/polling'
import type {
  AccountDetail,
  AccountPatch,
  AccountRow,
  AccountUpdateResponse,
  BanList,
  BroadcastResponse,
  CashItemPatch,
  CashItemRow,
  BroadcastType,
  ChannelInfo,
  CharacterPatch,
  CharacterRow,
  CharacterUpdateResponse,
  DropPatch,
  DropsResponse,
  GlobalDropPatch,
  GlobalDropRow,
  KickResponse,
  LogFileInfo,
  LogTailResponse,
  NameHit,
  Paged,
  PingResponse,
  PlayerInfo,
  PutSettingResponse,
  RatesRequest,
  RatesResponse,
  ReloadResponse,
  ReloadTarget,
  ScriptFileInfo,
  ScriptFileResponse,
  ScriptKind,
  ScriptKindInfo,
  ScriptSaveResponse,
  ScriptSearchResponse,
  ServerMessageResponse,
  SettingsResponse,
  ShopDetail,
  ShopItemPatch,
  ShopSummary,
  ShutdownResponse,
  StatusResponse,
  WriteResponse,
} from '@/lib/types'

/** 各端點的輪詢間隔（毫秒） */
export const INTERVALS = {
  status: 3_000,
  channels: 5_000,
  players: 3_000,
  logFiles: 10_000,
  logTail: 5_000,
} as const

export const queryKeys = {
  ping: ['ping'] as const,
  status: ['status'] as const,
  channels: ['channels'] as const,
  players: ['players'] as const,
  logFiles: ['logs'] as const,
  logTail: (name: string, lines: number) => ['logs', name, lines] as const,
}

export function usePing() {
  return useQuery({
    queryKey: queryKeys.ping,
    queryFn: () => apiFetch<PingResponse>('/api/ping'),
    staleTime: Infinity,
  })
}

export function useStatus() {
  return useQuery({
    queryKey: queryKeys.status,
    queryFn: () => apiFetch<StatusResponse>('/api/status'),
    refetchInterval: useRefetchInterval(INTERVALS.status),
  })
}

export function useChannels() {
  return useQuery({
    queryKey: queryKeys.channels,
    queryFn: () => apiFetch<ChannelInfo[]>('/api/channels'),
    refetchInterval: useRefetchInterval(INTERVALS.channels),
  })
}

export function usePlayers() {
  return useQuery({
    queryKey: queryKeys.players,
    queryFn: () => apiFetch<PlayerInfo[]>('/api/players'),
    refetchInterval: useRefetchInterval(INTERVALS.players),
  })
}

export function useLogFiles() {
  return useQuery({
    queryKey: queryKeys.logFiles,
    queryFn: () => apiFetch<LogFileInfo[]>('/api/logs'),
    refetchInterval: useRefetchInterval(INTERVALS.logFiles),
  })
}

export function useLogTail(name: string | null, lines: number) {
  return useQuery({
    queryKey: queryKeys.logTail(name ?? '', lines),
    queryFn: () =>
      apiFetch<LogTailResponse>(`/api/logs/${encodeURIComponent(name ?? '')}?lines=${lines}`),
    enabled: name !== null,
    refetchInterval: useRefetchInterval(INTERVALS.logTail),
  })
}

/* ---------------------------------------------------------------------- */
/* Phase 2–3：設定 / 動作                                                   */
/* ---------------------------------------------------------------------- */

export const settingsKeys = {
  settings: ['settings'] as const,
}

export function useSettings() {
  return useQuery({
    queryKey: settingsKeys.settings,
    queryFn: () => apiFetch<SettingsResponse>('/api/settings'),
    // 設定檔不會自己變；改完由 mutation 讓它失效即可
    staleTime: 30_000,
  })
}

/** 寫入一個設定並立即套用（後端一律套用）；成功後讓設定與頻道（倍率）失效 */
export function usePutSetting() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { key: string; value: string }) =>
      apiSend<PutSettingResponse>('PUT', `/api/settings/${encodeURIComponent(v.key)}`, {
        value: v.value,
        apply: true,
      }),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: settingsKeys.settings })
      void qc.invalidateQueries({ queryKey: queryKeys.channels })
      void qc.invalidateQueries({ queryKey: queryKeys.status })
    },
  })
}

export function useSetRates() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (rates: RatesRequest) => apiSend<RatesResponse>('POST', '/api/rates', rates),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: settingsKeys.settings })
      void qc.invalidateQueries({ queryKey: queryKeys.channels })
    },
  })
}

export function useServerMessage() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { message: string; save: boolean }) =>
      apiSend<ServerMessageResponse>('POST', '/api/server-message', v),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: settingsKeys.settings })
      void qc.invalidateQueries({ queryKey: queryKeys.channels })
    },
  })
}

export function useBroadcast() {
  return useMutation({
    mutationFn: (v: { type: BroadcastType; message: string }) =>
      apiSend<BroadcastResponse>('POST', '/api/broadcast', v),
  })
}

export function useReload() {
  return useMutation({
    mutationFn: (target: ReloadTarget) => apiSend<ReloadResponse>('POST', `/api/reload/${target}`),
  })
}

export function useShutdown() {
  return useMutation({
    mutationFn: () => apiSend<ShutdownResponse>('POST', '/api/shutdown', { confirm: true }),
  })
}

/* ---------------------------------------------------------------------- */
/* Phase 6：腳本                                                            */
/* ---------------------------------------------------------------------- */

export const scriptKeys = {
  kinds: ['scripts', 'kinds'] as const,
  list: (kind: ScriptKind) => ['scripts', 'list', kind] as const,
  file: (kind: ScriptKind, name: string) => ['scripts', 'file', kind, name] as const,
  search: (q: string, kind: ScriptKind | '') => ['scripts', 'search', q, kind] as const,
}

export function useScriptKinds() {
  return useQuery({
    queryKey: scriptKeys.kinds,
    queryFn: () => apiFetch<ScriptKindInfo[]>('/api/scripts/kinds'),
    staleTime: 60_000,
  })
}

export function useScriptList(kind: ScriptKind) {
  return useQuery({
    queryKey: scriptKeys.list(kind),
    queryFn: () => apiFetch<ScriptFileInfo[]>(`/api/scripts/list/${kind}`),
    staleTime: 30_000,
  })
}

export function useScriptFile(kind: ScriptKind, name: string | null) {
  return useQuery({
    queryKey: scriptKeys.file(kind, name ?? ''),
    queryFn: () =>
      apiFetch<ScriptFileResponse>(`/api/scripts/file/${kind}/${encodeURIComponent(name ?? '')}`),
    enabled: name !== null,
    // 編輯中絕不能被輪詢蓋掉：只在明確要求時重抓
    staleTime: Infinity,
    refetchOnMount: false,
  })
}

export function useScriptSearch(q: string, kind: ScriptKind | '') {
  const query = q.trim()
  return useQuery({
    queryKey: scriptKeys.search(query, kind),
    queryFn: () =>
      apiFetch<ScriptSearchResponse>(
        `/api/scripts/search?q=${encodeURIComponent(query)}${kind ? `&kind=${kind}` : ''}`,
      ),
    enabled: query.length >= 2,
    staleTime: 30_000,
  })
}

export function useSaveScript() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { kind: ScriptKind; name: string; content: string }) =>
      apiSend<ScriptSaveResponse>('PUT', `/api/scripts/file/${v.kind}/${encodeURIComponent(v.name)}`, {
        content: v.content,
      }),
    onSuccess: (res, v) => {
      // 存檔成功：把檔案快取更新成剛存的內容，並讓清單（size/modified）失效
      qc.setQueryData<ScriptFileResponse>(scriptKeys.file(v.kind, v.name), () => ({
        kind: v.kind,
        name: v.name,
        content: v.content,
        size: res.bytes,
        modified: res.modified,
        encodingOk: true,
        detectedEncoding: 'BIG5',
        afterSave: res.afterSave,
      }))
      void qc.invalidateQueries({ queryKey: scriptKeys.list(v.kind) })
      void qc.invalidateQueries({ queryKey: scriptKeys.kinds })
    },
  })
}

/* ---------------------------------------------------------------------- */
/* Phase 4：帳號 / 角色 / 封鎖                                               */
/* ---------------------------------------------------------------------- */

export const accountKeys = {
  list: (q: string, filter: string, page: number, size: number) => ['accounts', 'list', q, filter, page, size] as const,
  detail: (id: number) => ['accounts', 'detail', id] as const,
  characters: (q: string, page: number, size: number) => ['characters', 'list', q, page, size] as const,
  character: (id: number) => ['characters', 'detail', id] as const,
  bans: (kind: 'ip' | 'mac') => ['bans', kind] as const,
}

export function useAccounts(q: string, filter: string, page: number, size: number) {
  return useQuery({
    queryKey: accountKeys.list(q, filter, page, size),
    queryFn: () =>
      apiFetch<Paged<AccountRow>>(
        `/api/accounts?q=${encodeURIComponent(q)}&filter=${encodeURIComponent(filter)}&page=${page}&size=${size}`,
      ),
    placeholderData: keepPreviousData,
    refetchInterval: useRefetchInterval(10_000),
  })
}

export function useAccount(id: number | null) {
  return useQuery({
    queryKey: accountKeys.detail(id ?? 0),
    queryFn: () => apiFetch<AccountDetail>(`/api/accounts/${id}`),
    enabled: id !== null,
  })
}

export function useUpdateAccount() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { id: number; patch: AccountPatch }) =>
      apiSend<AccountUpdateResponse>('PUT', `/api/accounts/${v.id}`, v.patch),
    onSettled: (_r, _e, v) => {
      void qc.invalidateQueries({ queryKey: accountKeys.detail(v.id) })
      void qc.invalidateQueries({ queryKey: ['accounts', 'list'] })
      void qc.invalidateQueries({ queryKey: queryKeys.players })
    },
  })
}

export function useKickAccount() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => apiSend<KickResponse>('POST', `/api/accounts/${id}/kick`),
    onSettled: (_r, _e, id) => {
      // 斷線是非同步的（MINA 執行緒），稍等再重抓
      setTimeout(() => {
        void qc.invalidateQueries({ queryKey: accountKeys.detail(id) })
        void qc.invalidateQueries({ queryKey: ['accounts', 'list'] })
        void qc.invalidateQueries({ queryKey: queryKeys.players })
      }, 800)
    },
  })
}

export function useCharacters(q: string, page: number, size: number) {
  return useQuery({
    queryKey: accountKeys.characters(q, page, size),
    queryFn: () => apiFetch<Paged<CharacterRow>>(`/api/characters?q=${encodeURIComponent(q)}&page=${page}&size=${size}`),
    placeholderData: keepPreviousData,
    refetchInterval: useRefetchInterval(10_000),
  })
}

export function useCharacter(id: number | null) {
  return useQuery({
    queryKey: accountKeys.character(id ?? 0),
    queryFn: () => apiFetch<CharacterRow>(`/api/characters/${id}`),
    enabled: id !== null,
  })
}

export function useUpdateCharacter() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { id: number; patch: CharacterPatch }) =>
      apiSend<CharacterUpdateResponse>('PUT', `/api/characters/${v.id}`, v.patch),
    onSettled: (_r, _e, v) => {
      void qc.invalidateQueries({ queryKey: accountKeys.character(v.id) })
      void qc.invalidateQueries({ queryKey: ['characters', 'list'] })
      void qc.invalidateQueries({ queryKey: ['accounts', 'detail'] })
    },
  })
}

export function useKickCharacter() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => apiSend<KickResponse>('POST', `/api/characters/${id}/kick`),
    onSettled: (_r, _e, id) => {
      setTimeout(() => {
        void qc.invalidateQueries({ queryKey: accountKeys.character(id) })
        void qc.invalidateQueries({ queryKey: ['characters', 'list'] })
        void qc.invalidateQueries({ queryKey: ['accounts'] })
        void qc.invalidateQueries({ queryKey: queryKeys.players })
      }, 800)
    },
  })
}

export function useBans(kind: 'ip' | 'mac') {
  return useQuery({
    queryKey: accountKeys.bans(kind),
    queryFn: () => apiFetch<BanList>(`/api/bans/${kind}`),
    refetchInterval: useRefetchInterval(15_000),
  })
}

export function useAddBan(kind: 'ip' | 'mac') {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (value: string) =>
      apiSend<{ ok: boolean; id: number; value: string }>('POST', `/api/bans/${kind}`, { [kind]: value }),
    onSettled: () => void qc.invalidateQueries({ queryKey: accountKeys.bans(kind) }),
  })
}

export function useRemoveBan(kind: 'ip' | 'mac') {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => apiSend<{ ok: boolean; id: number }>('DELETE', `/api/bans/${kind}/${id}`),
    onSettled: () => void qc.invalidateQueries({ queryKey: accountKeys.bans(kind) }),
  })
}

/* ---------------------------------------------------------------------- */
/* Phase 5：掉寶 / 商店 / 商城                                               */
/* ---------------------------------------------------------------------- */

export const dataKeys = {
  names: (kind: 'items' | 'mobs' | 'npcs', q: string) => ['names', kind, q] as const,
  drops: (mob: number, item: number, page: number, size: number) => ['drops', mob, item, page, size] as const,
  globalDrops: ['drops', 'global'] as const,
  shops: ['shops'] as const,
  shop: (id: number) => ['shops', id] as const,
  cash: (q: string, filter: string, page: number, size: number) => ['cashshop', q, filter, page, size] as const,
}

export function useNames(kind: 'items' | 'mobs' | 'npcs', q: string) {
  const query = q.trim()
  return useQuery({
    queryKey: dataKeys.names(kind, query),
    queryFn: () => apiFetch<NameHit[]>(`/api/names/${kind}?q=${encodeURIComponent(query)}&limit=30`),
    enabled: query.length > 0,
    staleTime: 5 * 60_000,
    placeholderData: keepPreviousData,
  })
}

export function useDrops(mob: number, item: number, page: number, size: number) {
  return useQuery({
    queryKey: dataKeys.drops(mob, item, page, size),
    queryFn: () => apiFetch<DropsResponse>(`/api/drops?mob=${mob}&item=${item}&page=${page}&size=${size}`),
    enabled: mob > 0 || item > 0,
    placeholderData: keepPreviousData,
  })
}

function invalidateDrops(qc: ReturnType<typeof useQueryClient>) {
  void qc.invalidateQueries({ queryKey: ['drops'] })
}

export function useSaveDrop() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { id?: number; patch: DropPatch }) =>
      v.id ? apiSend<WriteResponse>('PUT', `/api/drops/${v.id}`, v.patch) : apiSend<WriteResponse>('POST', '/api/drops', v.patch),
    onSettled: () => invalidateDrops(qc),
  })
}

export function useDeleteDrop() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => apiSend<WriteResponse>('DELETE', `/api/drops/${id}`),
    onSettled: () => invalidateDrops(qc),
  })
}

export function useGlobalDrops() {
  return useQuery({ queryKey: dataKeys.globalDrops, queryFn: () => apiFetch<GlobalDropRow[]>('/api/drops/global') })
}

export function useSaveGlobalDrop() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { id?: number; patch: GlobalDropPatch }) =>
      v.id
        ? apiSend<WriteResponse>('PUT', `/api/drops/global/${v.id}`, v.patch)
        : apiSend<WriteResponse>('POST', '/api/drops/global', v.patch),
    onSettled: () => invalidateDrops(qc),
  })
}

export function useDeleteGlobalDrop() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => apiSend<WriteResponse>('DELETE', `/api/drops/global/${id}`),
    onSettled: () => invalidateDrops(qc),
  })
}

export function useShops() {
  return useQuery({ queryKey: dataKeys.shops, queryFn: () => apiFetch<ShopSummary[]>('/api/shops') })
}

export function useShop(id: number | null) {
  return useQuery({
    queryKey: dataKeys.shop(id ?? 0),
    queryFn: () => apiFetch<ShopDetail>(`/api/shops/${id}`),
    enabled: id !== null,
  })
}

function invalidateShops(qc: ReturnType<typeof useQueryClient>) {
  void qc.invalidateQueries({ queryKey: ['shops'] })
}

export function useCreateShop() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (npcid: number) => apiSend<WriteResponse>('POST', '/api/shops', { npcid }),
    onSettled: () => invalidateShops(qc),
  })
}

export function useDeleteShop() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (shopid: number) => apiSend<WriteResponse>('DELETE', `/api/shops/${shopid}`),
    onSettled: () => invalidateShops(qc),
  })
}

export function useAddShopItem() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { shopid: number; patch: ShopItemPatch }) => apiSend<WriteResponse>('POST', `/api/shops/${v.shopid}/items`, v.patch),
    onSettled: () => invalidateShops(qc),
  })
}

export function useUpdateShopItem() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { shopitemid: number; patch: ShopItemPatch }) => apiSend<WriteResponse>('PUT', `/api/shopitems/${v.shopitemid}`, v.patch),
    onSettled: () => invalidateShops(qc),
  })
}

export function useDeleteShopItem() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (shopitemid: number) => apiSend<WriteResponse>('DELETE', `/api/shopitems/${shopitemid}`),
    onSettled: () => invalidateShops(qc),
  })
}

export function useReorderShop() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { shopid: number; ids: number[] }) => apiSend<WriteResponse>('POST', `/api/shops/${v.shopid}/reorder`, { ids: v.ids }),
    onSettled: () => invalidateShops(qc),
  })
}

export function useCashItems(q: string, filter: string, page: number, size: number) {
  return useQuery({
    queryKey: dataKeys.cash(q, filter, page, size),
    queryFn: () =>
      apiFetch<Paged<CashItemRow>>(`/api/cashshop?q=${encodeURIComponent(q)}&filter=${encodeURIComponent(filter)}&page=${page}&size=${size}`),
    placeholderData: keepPreviousData,
  })
}

export function useSaveCashItem() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { serial: number; create: boolean; patch: CashItemPatch }) =>
      v.create
        ? apiSend<WriteResponse>('POST', '/api/cashshop', { ...v.patch, serial: v.serial })
        : apiSend<WriteResponse>('PUT', `/api/cashshop/${v.serial}`, v.patch),
    onSettled: () => void qc.invalidateQueries({ queryKey: ['cashshop'] }),
  })
}

export function useDeleteCashItem() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (serial: number) => apiSend<WriteResponse>('DELETE', `/api/cashshop/${serial}`),
    onSettled: () => void qc.invalidateQueries({ queryKey: ['cashshop'] }),
  })
}
