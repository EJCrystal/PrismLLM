# -*- coding: utf-8 -*-
import io

def eol(path):
    data = open(path, 'rb').read()
    return '\r\n' if data.count(b'\r\n') > (data.count(b'\n') - data.count(b'\r\n')) else '\n'

path = 'src/test/kotlin/dev/soven/llmgateway/GatewayIntegrationTest.kt'
s = io.open(path, encoding='utf-8', newline='').read()
nl = eol(path)

def rep(old, new, count=-1):
    global s
    old = old.replace('\n', nl); new = new.replace('\n', nl)
    assert old in s, 'NOT FOUND: ' + old[:80]
    s = s.replace(old, new) if count == -1 else s.replace(old, new, count)

# 删团队限流测试
start = s.find('fun `team limits are configured and enforce key and team request rates`')
assert start != -1
test_start = s.rfind('@Test', 0, start)
next_test = s.find('@Test', start)
s = s[:test_start] + s[next_test:]
print('dropped team limits test')

rep('fun `usage report aggregates requests tokens and costs by key and team`',
    'fun `usage report aggregates requests tokens and costs by key`')
rep('val key = createClientKey(team = "search-team")', 'val key = createClientKey()')
rep('                    team = "search-team",' + nl, '')  # 全部三处
assert 'team = "search-team"' not in s
rep('''            val team = json["byTeam"]!!.jsonArray.single().jsonObject
            assertEquals("search-team", team["id"]!!.jsonPrimitive.content)
            assertEquals("5", team["requests"]!!.jsonPrimitive.content)
            assertEquals("2", team["errors"]!!.jsonPrimitive.content)
''', '')
rep('''        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
        team: String? = null,
    ): String {
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create(
            name = "test-key",
            team = team,''',
    '''        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
    ): String {
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create(
            name = "test-key",''')
rep('"""{"name":"search-service","team":"search-team","dailyTokenLimit"', '"""{"name":"search-service","dailyTokenLimit"')
rep('            assertEquals("search-team", rotatedJson["team"]!!.jsonPrimitive.content)' + nl, '')
rep('''        clientKeyName = null,
        team = null,''', '''        clientKeyName = null,''')

io.open(path, 'w', encoding='utf-8', newline='').write(s)
print('ok integration test written')

for p in ['src/test/kotlin/dev/soven/llmgateway/proxy/BillingCalculatorTest.kt',
          'src/test/kotlin/dev/soven/llmgateway/proxy/RequestLogWriterTest.kt']:
    s2 = io.open(p, encoding='utf-8', newline='').read()
    nl2 = eol(p)
    s2 = s2.replace('        team = null,' + nl2, '', 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s2)
    print('ok', p)

key_helper = '''
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
'''
p = 'src/test/kotlin/dev/soven/llmgateway/quota/RequestRateLimiterTest.kt'
s3 = io.open(p, encoding='utf-8', newline='').read()
if 'private fun key(' not in s3:
    io.open(p, 'w', encoding='utf-8', newline='').write(s3.rstrip('\n') + '\n' + key_helper)
print('ok limiter helper')

p = 'src/test/kotlin/dev/soven/llmgateway/quota/RequestLimitServiceTest.kt'
s4 = io.open(p, encoding='utf-8', newline='').read()
if 'private fun quotaService(' not in s4:
    su = 'dev.soven.llmgateway.repo.StoredQuotaUsage'
    quota_helper = (
        '\nprivate fun quotaService(): ClientKeyQuotaService {\n'
        '    val usage = mutableMapOf<String, ' + su + '>()\n'
        '    return ClientKeyQuotaService(\n'
        '        usageReader = { id, _ -> usage[id] ?: ' + su + '() },\n'
        '        usageWriter = { id, _, tokens, costs ->\n'
        '            val previous = usage[id] ?: ' + su + '()\n'
        '            usage[id] = ' + su + '(\n'
        '                tokens = previous.tokens + tokens,\n'
        '                costs = previous.costs.mapValues { (currency, amount) -> amount + (costs[currency] ?: java.math.BigDecimal.ZERO) },\n'
        '            )\n'
        '        },\n'
        '        now = { 0L },\n'
        '    )\n'
        '}\n'
    )
    io.open(p, 'w', encoding='utf-8', newline='').write(s4.rstrip('\n') + '\n' + quota_helper + key_helper)
print('ok service helpers')
