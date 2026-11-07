import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  baseUrlAfterProviderChange,
  baseUrlForSave,
  isLoopbackUrl,
} from '../src/utils/webSearchSettings'

describe('webSearchSettings: 搜索地址继承与校验', () => {
  it('未改动的环境变量地址不会保存为显式覆盖', () => {
    assert.equal(
      baseUrlForSave({
        baseUrl: 'https://env.example.com',
        inheritedBaseUrl: 'https://env.example.com',
        baseUrlSource: 'environment',
      }),
      '',
    )
  })

  it('编辑后又改回原值仍视为继承', () => {
    assert.equal(
      baseUrlForSave({
        baseUrl: '  https://env.example.com  ',
        inheritedBaseUrl: 'https://env.example.com',
        baseUrlSource: 'environment',
      }),
      '',
    )
  })

  it('显式地址和用户修改后的地址会保存', () => {
    assert.equal(
      baseUrlForSave({
        baseUrl: 'https://own.example.com',
        inheritedBaseUrl: 'https://own.example.com',
        baseUrlSource: 'settings',
      }),
      'https://own.example.com',
    )
    assert.equal(
      baseUrlForSave({
        baseUrl: 'https://new.example.com',
        inheritedBaseUrl: 'https://env.example.com',
        baseUrlSource: 'environment',
      }),
      'https://new.example.com',
    )
  })

  it('切换后端时清空未改动的地址', () => {
    assert.equal(baseUrlAfterProviderChange('https://env.example.com', 'https://env.example.com'), '')
    assert.equal(
      baseUrlAfterProviderChange('https://new.example.com', 'https://env.example.com'),
      'https://new.example.com',
    )
  })

  it('识别本机回环地址', () => {
    assert.equal(isLoopbackUrl('http://localhost:8080'), true)
    assert.equal(isLoopbackUrl('http://127.0.0.1:8080'), true)
    assert.equal(isLoopbackUrl('http://[::1]:8080'), true)
    assert.equal(isLoopbackUrl('http://search.example.com'), false)
    assert.equal(isLoopbackUrl('not a url'), false)
  })
})
