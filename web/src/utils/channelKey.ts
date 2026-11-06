import type { ChannelKeyState } from '../types/api'

export type KeyStateTone = 'success' | 'warning' | 'danger' | 'neutral'

export function keyStateBadge(state: ChannelKeyState): { label: string; class: string } {
  switch (state) {
    case 'ACTIVE':
      return { label: '正常', class: 'success' }
    case 'MANUAL_DISABLED':
      return { label: '人工停用', class: 'neutral' }
    case 'AUTO_TEMPORARY':
      return { label: '临时停用', class: 'warning' }
    case 'AUTO_PERMANENT':
      return { label: '永久停用', class: 'danger' }
    default:
      return { label: state || '正常', class: 'neutral' }
  }
}

export function keyStateTone(state: ChannelKeyState): KeyStateTone {
  switch (state) {
    case 'ACTIVE':
      return 'success'
    case 'AUTO_TEMPORARY':
      return 'warning'
    case 'AUTO_PERMANENT':
      return 'danger'
    default:
      return 'neutral'
  }
}
