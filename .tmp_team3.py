# -*- coding: utf-8 -*-
import io, re

def eol(path):
    data = open(path, 'rb').read()
    return '\r\n' if data.count(b'\r\n') > (data.count(b'\n') - data.count(b'\r\n')) else '\n'

path = 'src/test/kotlin/dev/soven/llmgateway/GatewayIntegrationTest.kt'
s = io.open(path, encoding='utf-8', newline='').read()
nl = eol(path)

def rep(old, new, count=1):
    global s
    old = old.replace('\n', nl); new = new.replace('\n', nl)
    assert old in s, 'NOT FOUND: ' + old[:80]
    s = s.replace(old, new, count)

def drop_test(name):
    global s
    start = s.find('fun `' + name + '`')
    assert start != -1, name
    test_start = s.rfind('@Test', 0, start)
    next_test = s.find('@Test', start)
    assert test_start != -1 and next_test != -1
    s = s[:test_start] + s[next_test:]
    print('dropped test', name)

# 1. 删除团队限流大测试
drop_test('team limits are configured and enforce key and team request rates')

# 2. 用量报表测试去团队：改名、去 team 参数与断言
rep('fun `usage report aggregates requests tokens and costs by key and team`',
    'fun `usage report aggregates requests tokens and costs by key`')
rep('val key = createClientKey(team = "search-team")', 'val key = createClientKey()')
rep('                    team = "search-team",' + nl, '')
assert s.count('team = "search-team",' + nl) == 0
rep('''            val team = json["byTeam"]!!.jsonArray.single().jsonObject
            assertEquals("search-team", team["id"]!!.jsonPrimitive.content)
            assertEquals("5", team["requests"]!!.jsonPrimitive.content)
            assertEquals("2", team["errors"]!!.jsonPrimitive.content)
''', '')

# 3. createClientKey 助手 team 参数与轮换测试断言
rep('''    private suspend fun createClientKey(
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
        team: String? = null,
    ): String {
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create(
            name = "test-key",
            team = team,''',
    '''    private suspend fun createClientKey(
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
    ): String {
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create(
            name = "test-key",''')
rep('"""{"name":"search-service","team":"search-team","dailyTokenLimit"', '"""{"name":"search-service","dailyTokenLimit"')
rep('            assertEquals("search-team", rotatedJson["team"]!!.jsonPrimitive.content)' + nl, '')

# 4. requestLog 助手 team 字段
rep('''        clientKeyName = null,
        team = null,''', '''        clientKeyName = null,''')

io.open(path, 'w', encoding='utf-8', newline='').write(s)
print('ok integration test')

# BillingCalculatorTest / RequestLogWriterTest 助手
for p in ['src/test/kotlin/dev/soven/llmgateway/proxy/BillingCalculatorTest.kt',
          'src/test/kotlin/dev/soven/llmgateway/proxy/RequestLogWriterTest.kt']:
    s2 = io.open(p, encoding='utf-8', newline='').read()
    nl2 = eol(p)
    old = '        team = null,' + nl2
    assert old in s2, p
    s2 = s2.replace(old, '', 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s2)
    print('ok', p)

# 我重写的两个配额测试补本地助手
helpers = '''
private fun key(id: String, quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits()) = ClientKeyRecord(
    id = id,
    name = id,
    keyHash = "hash-$id",
    keyPrefix = "sk-gw-test",
    enabled = true,
    expiresAt = null,
    quotas = quotas,
    createdAt = 0,
    rotatedAt = null,
    revokedAt = null,
)

private fun quotaService(): ClientKeyQuotaService = ClientKeyQuotaService(FakeQuotaRepository())
'''
# FakeQuotaRepository 是否存在？
import os
print('FakeQuotaRepository exists:', any('FakeQuotaRepository' in f for f in os.listdir('src/test/kotlin/dev/soven/llmgateway/quota')) or 'TODO')
