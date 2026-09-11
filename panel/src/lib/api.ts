import { clearToken, getToken } from '@/lib/auth'
import type { ErrorBody } from '@/lib/types'

/** 網路層失敗（伺服器沒開、連線被拒）時的 status */
export const NETWORK_ERROR_STATUS = 0
export const NETWORK_ERROR_MESSAGE = '無法連線到伺服器'

export class ApiError extends Error {
  readonly status: number
  /** 伺服器附在 `{"error", "details"}` 裡的結構化資料（例如無法編碼的字元清單） */
  readonly details: unknown

  constructor(status: number, message: string, details?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.details = details
  }

  get isNetworkError(): boolean {
    return this.status === NETWORK_ERROR_STATUS
  }

  get isUnauthorized(): boolean {
    return this.status === 401
  }
}

export interface ApiFetchOptions extends RequestInit {
  /** 指定 token（登入畫面驗證用）；預設用已儲存的 token */
  token?: string | null
  /** true 時 401 不會清掉已儲存的 token（登入畫面驗證用） */
  keepTokenOn401?: boolean
}

/**
 * API 一律走同源的絕對路徑 `/api/...`（契約如此；靜態資源才用相對路徑）。
 * 若日後面板被掛在某個前綴底下、API 也跟著搬，只需要改這裡的 API_ROOT。
 */
export const API_ROOT = ''

export function apiUrl(path: string): string {
  return API_ROOT + (path.startsWith('/') ? path : `/${path}`)
}

function isErrorBody(v: unknown): v is ErrorBody {
  return typeof v === 'object' && v !== null && typeof (v as ErrorBody).error === 'string'
}

/**
 * 共用的 API 呼叫：加上 Authorization header、解析 JSON，
 * 失敗時丟出 ApiError（status + 伺服器的 `{"error": ...}` 訊息）。
 */
export async function apiFetch<T>(path: string, init: ApiFetchOptions = {}): Promise<T> {
  const { token: tokenOverride, keepTokenOn401 = false, headers: initHeaders, ...rest } = init
  const token = tokenOverride === undefined ? getToken() : tokenOverride

  const headers = new Headers(initHeaders)
  headers.set('Accept', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let res: Response
  try {
    res = await fetch(apiUrl(path), { ...rest, headers, credentials: 'same-origin' })
  } catch {
    throw new ApiError(NETWORK_ERROR_STATUS, NETWORK_ERROR_MESSAGE)
  }

  if (!res.ok) {
    let message = `HTTP ${res.status}`
    let details: unknown
    try {
      const body: unknown = await res.json()
      if (isErrorBody(body)) {
        message = body.error
        details = body.details
      }
    } catch {
      /* 非 JSON 的錯誤頁（例如靜態檔的 404），沿用 HTTP 狀態碼 */
    }
    if (res.status === 401 && !keepTokenOn401) clearToken()
    throw new ApiError(res.status, message, details)
  }

  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

/** 帶 JSON body 的呼叫（POST / PUT） */
export function apiSend<T>(method: 'POST' | 'PUT' | 'DELETE', path: string, body?: unknown): Promise<T> {
  return apiFetch<T>(path, {
    method,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

/** 把任何錯誤轉成可顯示的訊息 */
export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message
  if (err instanceof Error) return err.message
  return String(err)
}
