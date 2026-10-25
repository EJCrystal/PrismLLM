# -*- coding: utf-8 -*-
import io

def eol(path):
    data = open(path, 'rb').read()
    return '\r\n' if data.count(b'\r\n') > (data.count(b'\n') - data.count(b'\r\n')) else '\n'

def edit(path, pairs):
    nl = eol(path)
    s = io.open(path, encoding='utf-8', newline='').read()
    for old, new in pairs:
        old = old.replace('\n', nl); new = new.replace('\n', nl)
        if old not in s:
            # 行尾混合时退化处理：把模式里 \r\n 与 \n 边界统一尝试
            alt = old.replace('\r\n', '\n')
            if alt in s:
                s = s.replace(alt, new.replace('\r\n', '\n'), 1)
                continue
            raise AssertionError('NOT FOUND in %s: %s' % (path, old[:90]))
        s = s.replace(old, new, 1)
    io.open(path, 'w', encoding='utf-8', newline='').write(s)
    print('ok', path)

def drop_lines(path, keyword):
    lines = io.open(path, encoding='utf-8', newline='').readlines()
    kept = [l for l in lines if keyword not in l]
    removed = len(lines) - len(kept)
    io.open(path, 'w', encoding='utf-8', newline='').writelines(kept)
    print('ok dropped %d lines in %s' % (removed, path))
    return removed

# AliasRepository（混合行尾，直接删行）
n = drop_lines('src/main/kotlin/dev/soven/llmgateway/repo/AliasRepository.kt', 'exposeUpstreamModels = this[Channels')
assert n == 1, n

# UpstreamModelCatalog
edit('src/main/kotlin/dev/soven/llmgateway/proxy/UpstreamModelCatalog.kt', [(
    '        channelRepository.list().filter { it.enabled && it.exposeUpstreamModels }',
    '        channelRepository.list().filter { it.enabled }',
)])

# AdminDtos
edit('src/main/kotlin/dev/soven/llmgateway/api/AdminDtos.kt', [
    (
        '''    val modelMappings: List<ChannelModelMappingRequest>? = null,
    val exposeUpstreamModels: Boolean? = null,
    val priority: Int? = null,''',
        '''    val modelMappings: List<ChannelModelMappingRequest>? = null,
    val priority: Int? = null,''',
    ),
    (
        '''    val modelMappings: List<ChannelModelMappingResponse>,
    val exposeUpstreamModels: Boolean,
    val priority: Int,''',
        '''    val modelMappings: List<ChannelModelMappingResponse>,
    val priority: Int,''',
    ),
    (
        '''    exposeUpstreamModels = exposeUpstreamModels,
    priority = priority,
)''',
        '''    priority = priority,
)''',
    ),
])

# AdminRoutes
edit('src/main/kotlin/dev/soven/llmgateway/api/AdminRoutes.kt', [
    (
        '''                modelMappings = normalizeModelMappings(request).orEmpty(),
                exposeUpstreamModels = request.exposeUpstreamModels ?: true,
                priority = request.priority ?: 1,''',
        '''                modelMappings = normalizeModelMappings(request).orEmpty(),
                priority = request.priority ?: 1,''',
    ),
    (
        '''                modelMappings = modelMappings,
                exposeUpstreamModels = request.exposeUpstreamModels,
                priority = request.priority,''',
        '''                modelMappings = modelMappings,
                priority = request.priority,''',
    ),
])

# TierSharingTest
edit('src/test/kotlin/dev/soven/llmgateway/resilience/TierSharingTest.kt', [(
    '''            enabled = true,
            exposeUpstreamModels = true,
            priority''',
    '''            enabled = true,
            priority''',
)])

# GatewayIntegrationTest：删“仅别名渠道”测试、helper 参数、迁移断言
path = 'src/test/kotlin/dev/soven/llmgateway/GatewayIntegrationTest.kt'
s = io.open(path, encoding='utf-8', newline='').read()
start = s.find('fun `alias only channel hides upstream model and disables direct routing`')
assert start != -1
test_start = s.rfind('@Test', 0, start)
next_test = s.find('@Test', start)
assert test_start != -1 and next_test != -1
s = s[:test_start] + s[next_test:]

pairs = [
    ('assertEquals("19", result.getString(1))', 'assertEquals("20", result.getString(1))'),
    ('''        protocols: Set<GatewayProtocol> = GatewayProtocol.default,
        exposeUpstreamModels: Boolean = true,
        modelMappings: List<ChannelModelRecord> = mappingsOf("gpt-4o-mini", "deepseek-chat", "m1", "m2"),''',
     '''        protocols: Set<GatewayProtocol> = GatewayProtocol.default,
        modelMappings: List<ChannelModelRecord> = mappingsOf("gpt-4o-mini", "deepseek-chat", "m1", "m2"),'''),
    ('''            modelMappings = modelMappings,
            exposeUpstreamModels = exposeUpstreamModels,
            priority = priority,''',
     '''            modelMappings = modelMappings,
            priority = priority,'''),
]
for old, new in pairs:
    old2 = old.replace('\n', '\r\n'); new2 = new.replace('\n', '\r\n')
    if old2 in s:
        s = s.replace(old2, new2, 1)
    else:
        assert old in s, 'NOT FOUND: ' + old[:80]
        s = s.replace(old, new, 1)
io.open(path, 'w', encoding='utf-8', newline='').write(s)
print('ok integration test')

# web: ChannelsView
edit('web/src/components/ChannelsView.vue', [
    (
        '''  enabled: true,
  exposeUpstreamModels: true,
  priority: 1,
})''',
        '''  enabled: true,
  priority: 1,
})''',
    ),
    (
        '''    enabled: true,
    exposeUpstreamModels: true,
    priority: 1,
  })''',
        '''    enabled: true,
    priority: 1,
  })''',
    ),
    (
        '''    enabled: channel.enabled,
    exposeUpstreamModels: channel.exposeUpstreamModels !== false,
    priority: channel.priority ?? 1,''',
        '''    enabled: channel.enabled,
    priority: channel.priority ?? 1,''',
    ),
    (
        '''              <span>对外模型 ID</span>
              <small v-if="form.exposeUpstreamModels !== false">
                调用方在请求体里使用的模型名；留空则直接使用上游模型名。
              </small>
              <small v-else>
                当前渠道仅通过别名访问；留空不会公开上游模型名，只能由别名引用。
              </small>''',
        '''              <span>对外模型 ID</span>
              <small>调用方在请求体里使用的模型名；留空则直接使用上游模型名。</small>''',
    ),
    (
        ":placeholder=" + chr(34) + "form.exposeUpstreamModels === false\n                    ? '可选；留空仅通过别名调用'\n                    : '留空沿用上游模型名（点击箭头填入）'" + chr(34),
        ":placeholder=" + chr(34) + "'留空沿用上游模型名（点击箭头填入）'" + chr(34),
    ),
    (
        '''        <label class="switch-row">
          <input v-model="form.exposeUpstreamModels" type="checkbox" />
          <span class="switch"></span>
          <span>
            <strong>允许上游模型名直连</strong>
            <small>开启后可直接使用上游模型名；关闭后只能通过配置的别名访问。</small>
          </span>
        </label>
        <label class="switch-row">
          <input v-model="form.enabled" type="checkbox" />''',
        '''        <label class="switch-row">
          <input v-model="form.enabled" type="checkbox" />''',
    ),
])
# 对外模型 ID 表头括号内三元（单独处理，避免引号冲突）
s = io.open('web/src/components/ChannelsView.vue', encoding='utf-8', newline='').read()
old_head = "对外模型 ID（{{ form.exposeUpstreamModels === false ? '可选；留空不公开上游模型' : '留空沿用上游模型名' }}）"
if old_head in s:
    s = s.replace(old_head, '对外模型 ID（留空沿用上游模型名）', 1)
    io.open('web/src/components/ChannelsView.vue', 'w', encoding='utf-8', newline='').write(s)
    print('ok header text')

# channelForm.js
edit('web/src/utils/channelForm.js', [(
    '''    exposeUpstreamModels: form.exposeUpstreamModels,
    priority:''',
    '''    priority:''',
)])

# channelForm.test.js
edit('web/test/channelForm.test.js', [(
    '''    enabled: true,
    exposeUpstreamModels: false,
  }''',
    '''    enabled: true,
  }''',
), (
    """  it('编辑仅别名渠道时保留 exposeUpstreamModels=false', () => {
    const payload = buildChannelPayload(baseForm, ['deepseek-v4.1-flash'], {})

    assert.equal(payload.exposeUpstreamModels, false)
    assert.equal(payload.name, 'deepseek')""",
    """  it('基础字段正确序列化', () => {
    const payload = buildChannelPayload(baseForm, ['deepseek-v4.1-flash'], {})

    assert.equal(payload.name, 'deepseek')""",
)])
