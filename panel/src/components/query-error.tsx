import { AlertCircle, WifiOff } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { ApiError, errorMessage } from '@/lib/api'
import { cn } from '@/lib/utils'

export interface QueryErrorProps {
  error: unknown
  className?: string
}

/**
 * 非 401 的錯誤提示。401 由 QueryClient 統一處理（回到登入畫面），這裡不顯示。
 * 網路失敗（伺服器沒開）顯示「無法連線到伺服器」。
 */
export function QueryError({ error, className }: QueryErrorProps) {
  if (!error) return null
  if (error instanceof ApiError && error.isUnauthorized) return null

  const network = error instanceof ApiError && error.isNetworkError
  return (
    <Alert variant="destructive" className={cn(className)}>
      {network ? <WifiOff /> : <AlertCircle />}
      <AlertTitle>{network ? '無法連線到伺服器' : '讀取失敗'}</AlertTitle>
      <AlertDescription>
        {network
          ? '伺服器可能尚未啟動或已關閉；畫面保留最後一次取得的資料，連線恢復後會自動更新。'
          : errorMessage(error)}
      </AlertDescription>
    </Alert>
  )
}
