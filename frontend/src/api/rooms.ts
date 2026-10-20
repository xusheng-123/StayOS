import { api } from './client'
import type { RoomType, RoomFilters, PageResult, Floor, Availability, CleaningStatus } from '../types/room'

export const availabilityNames: Record<Availability, string> = {
  VACANT_CLEAN: '空闲可入住', OCCUPIED: '已入住', DIRTY: '待打扫', CLEANING: '正在打扫',
  INSPECTION: '待检查', OUT_OF_SERVICE: '维修停用', DISABLED: '档案停用', UNAVAILABLE: '暂不可分配',
}
export const cleaningNames: Record<CleaningStatus, string> = {
  CLEAN: '已清洁', DIRTY: '待打扫', CLEANING: '正在打扫', INSPECTION: '待检查',
}
export function floorName(floor: number) { return floor < 0 ? `地下 ${Math.abs(floor)} 层` : `${floor} 楼` }
export function roomQuery(filters: RoomFilters, page = 1, pageSize = 20) {
  const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') query.set(key, String(value))
  }
  return query.toString()
}
export async function roomOptions(): Promise<{ types: RoomType[]; floors: Floor[] }> {
  const types: RoomType[] = []
  let page = 1
  while (true) {
    const result = await api<PageResult<RoomType>>(`/api/room-types?page=${page++}&pageSize=100`)
    types.push(...result.items)
    if (types.length >= result.total || result.items.length === 0) break
  }
  return { types, floors: await api<Floor[]>('/api/floors') }
}
export function localTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}
