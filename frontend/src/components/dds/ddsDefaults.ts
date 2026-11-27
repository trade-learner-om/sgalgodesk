import type { DdsSettings } from '../../api/client'

export function defaultDdsSettings(): DdsSettings {
  return {
    initialDelta: 25,
    maxLossType: 'POINTS',
    maxLossValue: 50,
    exitTime: '15:00',
    lots: 1,
  }
}
