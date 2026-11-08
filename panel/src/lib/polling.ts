import { createContext, useContext } from 'react'

export interface PollingState {
  paused: boolean
  setPaused: (paused: boolean) => void
}

export const PollingContext = createContext<PollingState>({
  paused: false,
  setPaused: () => {},
})

export function usePolling(): PollingState {
  return useContext(PollingContext)
}

/** 給 useQuery 的 refetchInterval：暫停時回傳 false */
export function useRefetchInterval(ms: number): number | false {
  const { paused } = usePolling()
  return paused ? false : ms
}
