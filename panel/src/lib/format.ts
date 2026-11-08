/** 顯示用的格式化工具。時間一律以本機時區顯示。 */

const numberFormat = new Intl.NumberFormat('zh-TW')

export function formatNumber(n: number): string {
  return numberFormat.format(n)
}

/** 位元組 → 人類可讀（B / KB / MB / GB） */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let v = bytes / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(v >= 100 ? 0 : 1)} ${units[i]}`
}

/** 位元組 → 整數 MB（JVM heap 用） */
export function formatMB(bytes: number): string {
  return `${formatNumber(Math.round(bytes / 1048576))} MB`
}

/** 毫秒 → 「1 天 2 小時 3 分 4 秒」，省略前導的零單位 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—'
  let s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  s -= d * 86400
  const h = Math.floor(s / 3600)
  s -= h * 3600
  const m = Math.floor(s / 60)
  s -= m * 60
  const parts: string[] = []
  if (d > 0) parts.push(`${d} 天`)
  if (h > 0 || parts.length > 0) parts.push(`${h} 小時`)
  if (m > 0 || parts.length > 0) parts.push(`${m} 分`)
  parts.push(`${s} 秒`)
  return parts.join(' ')
}

/** 啟動耗時（毫秒）→ 「6.12 秒」 */
export function formatSeconds(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—'
  return `${(ms / 1000).toFixed(2)} 秒`
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/** epoch 毫秒 → HH:mm:ss */
export function formatTime(ts: number | Date): string {
  const d = ts instanceof Date ? ts : new Date(ts)
  if (Number.isNaN(d.getTime())) return '—'
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}

/** epoch 毫秒 → yyyy-MM-dd HH:mm:ss */
export function formatDateTime(ts: number | Date): string {
  const d = ts instanceof Date ? ts : new Date(ts)
  if (Number.isNaN(d.getTime())) return '—'
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${formatTime(d)}`
}

/** part / whole → 0–100 的百分比（夾在範圍內） */
export function percent(part: number, whole: number): number {
  if (!Number.isFinite(part) || !Number.isFinite(whole) || whole <= 0) return 0
  return Math.max(0, Math.min(100, (part / whole) * 100))
}
