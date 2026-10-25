# -*- coding: utf-8 -*-
import io, re

def eol(path):
    data = open(path, 'rb').read()
    return '\r\n' if data.count(b'\r\n') > (data.count(b'\n') - data.count(b'\r\n')) else '\n'

def edit(path, pairs):
    nl = eol(path)
    s = io.open(path, encoding='utf-8', newline='').read()
    for old, new in pairs:
        old = old.replace('\n', nl); new = new.replace('\n', nl)
        assert old in s, 'NOT FOUND in %s: %s' % (path, old[:90])
        s = s.replace(old, new, 1)
    io.open(path, 'w', encoding='utf-8', newline='').write(s)
    print('ok', path)

# 1. V20 迁移
io.open('src/main/resources/db/migration/V20__remove_channel_expose_toggle.sql', 'w', encoding='utf-8', newline='\n').write(
    '-- 渠道级直连开关移除：是否参与直连只由渠道启用状态与全局 EXPOSE_UPSTREAM_MODELS 决定。\n'
    '-- 原关闭直连的渠道转为停用，保持“不可达”行为不变。\n'
    'update channels\n'
    '    set enabled = false\n'
    '    where expose_upstream_models = false;\n'
    '\n'
    'alter table channels\n'
    '    drop column if exists expose_upstream_models;\n'
)

# 2. Tables.kt
edit('src/main/kotlin/dev/soven/llmgateway/db/Tables.kt', [(
    '    val exposeUpstreamModels = bool("expose_upstream_models").default(true)\n    val priority',
    '    val priority',
)])

# 3. Records.kt
edit('src/main/kotlin/dev/soven/llmgateway/domain/Records.kt', [(
    '''    val modelMappings: List<ChannelModelRecord> = emptyList(),
    val exposeUpstreamModels: Boolean = true,
    val priority: Int = 0,''',
    '''    val modelMappings: List<ChannelModelRecord> = emptyList(),
    val priority: Int = 0,''',
)])

# 4. ChannelRepository
edit('src/main/kotlin/dev/soven/llmgateway/repo/ChannelRepository.kt', [
    (
        '''        modelMappings: List<ChannelModelRecord> = emptyList(),
        exposeUpstreamModels: Boolean = true,
        priority: Int = 1,''',
        '''        modelMappings: List<ChannelModelRecord> = emptyList(),
        priority: Int = 1,''',
    ),
    (
        '''            it[Channels.enabled] = enabled
            it[Channels.exposeUpstreamModels] = exposeUpstreamModels
            it[Channels.priority] = priority''',
        '''            it[Channels.enabled] = enabled
            it[Channels.priority] = priority''',
    ),
    (
        '''        modelMappings: List<ChannelModelRecord>? = null,
        exposeUpstreamModels: Boolean? = null,
        priority: Int? = null,''',
        '''        modelMappings: List<ChannelModelRecord>? = null,
        priority: Int? = null,''',
    ),
    (
        '''            it[Channels.enabled] = enabled
            if (exposeUpstreamModels != null) it[Channels.exposeUpstreamModels] = exposeUpstreamModels
            if (priority != null) it[Channels.priority] = priority''',
        '''            it[Channels.enabled] = enabled
            if (priority != null) it[Channels.priority] = priority''',
    ),
    (
        '''        enabled = row[Channels.enabled],
        exposeUpstreamModels = row[Channels.exposeUpstreamModels],
        priority = row[Channels.priority],''',
        '''        enabled = row[Channels.enabled],
        priority = row[Channels.priority],''',
    ),
])

# 5. AliasRepository
edit('src/main/kotlin/dev/soven/llmgateway/repo/AliasRepository.kt', [(
    '''        enabled = this[Channels.enabled],
        exposeUpstreamModels = this[Channels.exposeUpstreamModels],
        priority = this[Channels.priority],''',
    '''        enabled = this[Channels.enabled],
        priority = this[Channels.priority],''',
)])

# 6. UpstreamModelCatalog
edit('src/main/kotlin/dev/soven/llmgateway/proxy/UpstreamModelCatalog.kt', [(
    '        channelRepository.list().filter { it.enabled && it.exposeUpstreamModels }',
    '        channelRepository.list().filter { it.enabled }',
)])

# 7. AdminDtos
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

# 8. AdminRoutes
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

# 9. TierSharingTest
edit('src/test/kotlin/dev/soven/llmgateway/resilience/TierSharingTest.kt', [(
    '''            enabled = true,
            exposeUpstreamModels = true,
            priority''',
    '''            enabled = true,
            priority''',
)])

# 10. GatewayIntegrationTest：删“仅别名渠道”测试、helper 参数、迁移断言
path = 'src/test/kotlin/dev/soven/llmgateway/GatewayIntegrationTest.kt'
nl = eol(path)
s = io.open(path, encoding='utf-8', newline='').read()

# 删除 alias-only 测试（从 @Test 到下一个 @Test 前）
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
    old2 = old.replace('\n', nl); new2 = new.replace('\n', nl)
    assert old2 in s, 'NOT FOUND: ' + old[:80]
    s = s.replace(old2, new2, 1)
io.open(path, 'w', encoding='utf-8', newline='').write(s)
print('ok integration test')

# 11. web: ChannelsView
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
        """                对外模型 ID（{{ form.exposeUpstreamModels === false ? '可选；留空不公开上游模型' : '留空沿用上游模型名' }}）""",
        """                对外模型 ID（留空沿用上游模型名）""",
    ),
    (
        ':placeholder="form.exposeUpstreamModels === false\n                    ? \'可选；留空仅通过别名调用\'\n                    : \'留空沿用上游模型名（点击箭头填入）\'"',
        ':placeholder="\'留空沿用上游模型名（点击箭头填入）\'"',
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

# 12. channelForm.js
edit('web/src/utils/channelForm.js', [(
    '''    exposeUpstreamModels: form.exposeUpstreamModels,
    priority:''',
    '''    priority:''',
)])

# 13. channelForm.test.js
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
