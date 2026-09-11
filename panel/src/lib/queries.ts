import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, apiSend } from '@/lib/api'
import { useRefetchInterval } from '@/lib/polling'
import type {
  BroadcastResponse,
  BroadcastType,
  ChannelInfo,
  LogFileInfo,
  LogTailResponse,
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
  ShutdownResponse,
  StatusResponse,
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

/** 寫入一個設定；成功後讓設定與頻道（倍率）失效 */
export function usePutSetting() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { key: string; value: string; apply: boolean }) =>
      apiSend<PutSettingResponse>('PUT', `/api/settings/${encodeURIComponent(v.key)}`, {
        value: v.value,
        apply: v.apply,
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
