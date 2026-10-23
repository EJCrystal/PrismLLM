import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { copyText } from '../src/clipboard.js'

describe('components & utils: 基础交互组件逻辑', () => {
  describe('clipboard: copyText 复制通用能力', () => {
    it('在安全上下文且提供 clipboard 时调用 writeText', async () => {
      let copiedText = ''
      const mockEnv = {
        isSecure: true,
        clipboard: {
          writeText: async (t) => {
            copiedText = t
          },
        },
        document: null,
      }

      await copyText('test-token-123', mockEnv)
      assert.equal(copiedText, 'test-token-123')
    })

    it('在非安全上下文降级使用 textarea 选区并执行 execCommand', async () => {
      let textareaValue = ''
      let execCommandCalled = false

      const mockEnv = {
        isSecure: false,
        clipboard: null,
        document: {
          createElement: (tag) => {
            assert.equal(tag, 'textarea')
            return {
              value: '',
              style: {},
              setAttribute: () => {},
              select: function () {
                textareaValue = this.value
              },
            }
          },
          body: {
            appendChild: () => {},
            removeChild: () => {},
          },
          execCommand: (cmd) => {
            if (cmd === 'copy') {
              execCommandCalled = true
              return true
            }
            return false
          },
        },
      }

      await copyText('fallback-token-456', mockEnv)
      assert.equal(textareaValue, 'fallback-token-456')
      assert.equal(execCommandCalled, true)
    })
  })

  describe('SecretInput & CopyButton 规格约定', () => {
    it('密码输入框默认应该为密文 password，可受控切换为明文 text', () => {
      const computeInputType = (show) => (show ? 'text' : 'password')
      assert.equal(computeInputType(false), 'password')
      assert.equal(computeInputType(true), 'text')
    })

    it('CopyButton 成功文案自动推导规则', () => {
      const computeSuccessTip = (title, customTip) => {
        const subject = title.replace(/^复制\s*/, '')
        return customTip || (subject ? `${subject}已复制` : '复制成功')
      }

      assert.equal(computeSuccessTip('复制', ''), '复制成功')
      assert.equal(computeSuccessTip('复制渠道 ID', ''), '渠道 ID已复制')
      assert.equal(computeSuccessTip('渠道 ID', ''), '渠道 ID已复制')
      assert.equal(computeSuccessTip('自定义操作', '搞定！'), '搞定！')
    })
  })
})
