import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/lib/api'
import { clearToken } from '@/lib/auth'

function handleUnauthorized(err: unknown) {
  if (err instanceof ApiError && err.isUnauthorized) {
    // apiFetch 已經清過了，這裡是保險：任何一次 401 都回到登入畫面。
    clearToken()
  }
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: handleUnauthorized }),
  mutationCache: new MutationCache({ onError: handleUnauthorized }),
  defaultOptions: {
    queries: {
      // 輪詢本身就是重試，額外 retry 只會拖慢錯誤顯示。
      retry: false,
      refetchOnWindowFocus: false,
      // 失敗時保留上一次的資料（TanStack Query 預設行為），畫面不清空。
      staleTime: 0,
      gcTime: 5 * 60 * 1000,
    },
  },
})
