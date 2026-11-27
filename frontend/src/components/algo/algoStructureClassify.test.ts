import { describe, expect, it } from 'vitest'
import type { AlgoLegConfig } from '../../api/client'
import { classifyAlgoStructure, ladderRank } from './algoStructureClassify'

function leg(
  partial: Pick<AlgoLegConfig, 'position' | 'optionType' | 'strikeCriteria'>,
): AlgoLegConfig {
  return {
    segment: 'OPTIDX',
    lots: 1,
    position: partial.position,
    optionType: partial.optionType,
    expiry: 'WEEKLY',
    strikeCriteria: partial.strikeCriteria,
    strikeType: null,
    strikeParam1: null,
    strikeParam2: null,
    targetEnabled: false,
    targetUnit: null,
    targetValue: null,
    slEnabled: false,
    slUnit: null,
    slValue: null,
    trailSlEnabled: false,
    trailSlUnit: null,
    trailSlTrigger: null,
    trailSlMove: null,
    reentryTgtEnabled: false,
    reentryTgtMode: null,
    reentryTgtCount: null,
    reentrySlEnabled: false,
    reentrySlMode: null,
    reentrySlCount: null,
    rangeBreakoutEnabled: false,
    rangeBreakoutTime: null,
    rangeBreakoutDirection: null,
    rangeBreakoutOn: null,
    simpleMomentumEnabled: false,
    simpleMomentumUnit: null,
    simpleMomentumValue: null,
  }
}

describe('ladderRank', () => {
  it('maps ITM/OTM relative to call vs put', () => {
    expect(ladderRank('ATM', true)).toBe(0)
    expect(ladderRank('ATM', false)).toBe(0)
    expect(ladderRank('ITM1', true)).toBe(-1)
    expect(ladderRank('ITM1', false)).toBe(1)
    expect(ladderRank('OTM1', true)).toBe(1)
    expect(ladderRank('OTM1', false)).toBe(-1)
  })
})

describe('classifyAlgoStructure short CE+PE', () => {
  it('labels ATM+ATM as Short Straddle', () => {
    const s = classifyAlgoStructure([
      leg({ position: 'Sell', optionType: 'CE', strikeCriteria: 'ATM' }),
      leg({ position: 'Sell', optionType: 'PE', strikeCriteria: 'ATM' }),
    ])
    expect(s.label).toBe('Short Straddle')
  })

  it('labels ITM1+ITM1 as Inverted Short Strangle (different strikes)', () => {
    const s = classifyAlgoStructure([
      leg({ position: 'Sell', optionType: 'CE', strikeCriteria: 'ITM1' }),
      leg({ position: 'Sell', optionType: 'PE', strikeCriteria: 'ITM1' }),
    ])
    expect(s.label).toBe('Inverted Short Strangle')
  })

  it('labels OTM1+OTM1 as Short Strangle', () => {
    const s = classifyAlgoStructure([
      leg({ position: 'Sell', optionType: 'CE', strikeCriteria: 'OTM1' }),
      leg({ position: 'Sell', optionType: 'PE', strikeCriteria: 'OTM1' }),
    ])
    expect(s.label).toBe('Short Strangle')
  })

  it('labels CE ITM1 + PE OTM1 as Short Straddle (same ladder rank)', () => {
    const s = classifyAlgoStructure([
      leg({ position: 'Sell', optionType: 'CE', strikeCriteria: 'ITM1' }),
      leg({ position: 'Sell', optionType: 'PE', strikeCriteria: 'OTM1' }),
    ])
    expect(s.label).toBe('Short Straddle')
  })
})
