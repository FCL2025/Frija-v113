import { Server } from 'lucide-react'
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from '@/components/ui/sidebar'
import { SECTION_GROUPS, SECTIONS, type SectionId } from '@/lib/sections'

export interface AppSidebarProps {
  active: SectionId
  onSelect: (id: SectionId) => void
  serverName?: string
  version?: string
}

export function AppSidebar({ active, onSelect, serverName, version }: AppSidebarProps) {
  const { isMobile, setOpenMobile } = useSidebar()

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size="lg" asChild tooltip="TMS113 管理面板">
              <a href="#dashboard" onClick={() => onSelect('dashboard')}>
                <div className="flex aspect-square size-8 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground">
                  <Server className="size-4" aria-hidden />
                </div>
                <div className="grid flex-1 text-left text-sm leading-tight">
                  <span className="truncate font-semibold">TMS113 管理面板</span>
                  <span className="truncate text-xs text-muted-foreground">
                    {serverName ?? '連線中…'}
                  </span>
                </div>
              </a>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        {SECTION_GROUPS.map((group) => (
          <SidebarGroup key={group}>
            <SidebarGroupLabel>{group}</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {SECTIONS.filter((s) => s.group === group).map((s) => (
                  <SidebarMenuItem key={s.id}>
                    <SidebarMenuButton asChild isActive={active === s.id} tooltip={s.label}>
                      <a
                        href={`#${s.id}`}
                        onClick={() => {
                          onSelect(s.id)
                          if (isMobile) setOpenMobile(false)
                        }}
                        aria-current={active === s.id ? 'page' : undefined}
                      >
                        <s.icon aria-hidden />
                        <span>{s.label}</span>
                      </a>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>
      <SidebarFooter>
        <div className="truncate px-2 py-1 text-xs text-muted-foreground group-data-[collapsible=icon]:hidden">
          {version ? `MapleStory v${version}` : ''}
        </div>
      </SidebarFooter>
    </Sidebar>
  )
}
