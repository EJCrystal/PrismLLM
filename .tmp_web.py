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
        assert old in s, 'NOT FOUND in %s: %s' % (path, old[:80])
        s = s.replace(old, new, 1)
    io.open(path, 'w', encoding='utf-8', newline='').write(s)
    print('ok', path)

def drop_lines(path, keywords):
    lines = io.open(path, encoding='utf-8', newline='').readlines()
    kept, dropped = [], 0
    for l in lines:
        if any(k in l for k in keywords):
            dropped += 1
            continue
        kept.append(l)
    io.open(path, 'w', encoding='utf-8', newline='').writelines(kept)
    print('ok %s (dropped %d lines)' % (path, dropped))

# App.vue：导入/导航/区块
edit('web/src/App.vue', [
    ("import TeamLimitsView from './components/TeamLimitsView.vue'\n", ''),
    ("  { id: 'team-limits', label: '团队限流', icon: 'users' },\n", ''),
    ('''        <TeamLimitsView v-else-if="view === 'team-limits'" />
''', ''),
])
# views 配置块（40 行附近的 'team-limits': {...}）逐行定位删除
s = io.open('web/src/App.vue', encoding='utf-8', newline='').read()
i = s.find("  'team-limits': {")
if i != -1:
    j = s.find('},', i) + 2
    s = s[:i] + s[j:].lstrip('\r\n').replace('\n', '\n', 1) if False else s[:i] + s[j:]
    io.open('web/src/App.vue', 'w', encoding='utf-8', newline='').write(s)
    print('ok App.vue views block')

# api.js
drop_lines('web/src/api.js', [
    "listTeamLimits", "updateTeamLimit", "deleteTeamLimit",
])
edit('web/src/api.js', [
    ('''    if (team) params.set('team', 'team')
''', ''),
])
s = io.open('web/src/api.js', encoding='utf-8', newline='').read()
for frag in ["  if (team) params.set('team', team)\n", "    if (team) params.set('team', team)\n", ", team = null", ", clientKeyId = null, team = null"]:
    if frag in s:
        s = s.replace(frag, frag.replace(', team = null', '').replace(frag, '  ' if False else frag), 1) if False else s
# 直接精准处理 getUsageReport 签名与 team 行
s = s.replace("({ from, to, clientKeyId = null, team = null })", "({ from, to, clientKeyId = null })")
s = '\n'.join(l for l in s.split('\n') if "params.set('team'" not in l)
io.open('web/src/api.js', 'w', encoding='utf-8', newline='').write(s)
print('ok api.js')

# clientKeyQuota.js
edit('web/src/utils/clientKeyQuota.js', [
    ("    team: optionalText(form.team),\n", ''),
])

# ClientKeysView：form 字段、展示、编辑回填、payload、表单项
edit('web/src/components/ClientKeysView.vue', [
    ("  team: '',\n", ''),
    ("    team: '',\n", ''),
    ("    team: key.team || '',\n", ''),
    ("      team: key.team,\n", ''),
    ('''                <div class="muted small">{{ key.team || '未分配团队' }}</div>
''', ''),
    ('''        <label class="field" for="client-key-team">
          <span>团队</span>
          <input id="client-key-team" v-model="form.team" placeholder="例如 search-team" autocomplete="off" />
          <small>用于用量报表和团队限流；留空为未分配团队。修改后不影响历史请求日志中的团队快照。</small>
        </label>
''', ''),
])

# UsageReportsView：按团队面板整删
edit('web/src/components/UsageReportsView.vue', [
    ('''        <div class="panel report-panel">
          <div class="report-panel-head"><h3>按团队</h3><span class="muted small">{{ report.byTeam.length }} 个团队</span></div>
          <div class="table-scroll">
            <table class="data-table compact-table">
              <thead><tr><th>团队</th><th>请求</th><th>Token</th><th>费用</th></tr></thead>
              <tbody>
                <tr v-for="row in report.byTeam" :key="row.id">
                  <td>{{ row.name || (row.id === '__unassigned_team__' ? '未分配团队' : row.id) }}</td>
                  <td>{{ formatNumber(row.requests) }} <span class="muted small">/ {{ row.errors }} 错误</span></td>
                  <td>{{ formatNumber(row.totalTokens) }}</td>
                  <td class="small">{{ formatUsageCosts(row.costs) }}</td>
                </tr>
                <tr v-if="!report.byTeam.length"><td colspan="4" class="muted">该时间范围没有请求。</td></tr>
              </tbody>
            </table>
          </div>
        </div>
''', ''),
])

# adminAudit.js 标签
edit('web/src/utils/adminAudit.js', [
    ("  'team_limit.update': '更新团队限流',\n  'team_limit.delete': '清除团队限流',\n", ''),
])

# web 测试
edit('web/test/clientKeyQuota.test.js', [
    ("      team: null,\n", ''),
    ("    const key = { name: 'search', team: 'search-team', enabled: true, expiresAt: null }\n", "    const key = { name: 'search', enabled: true, expiresAt: null }\n"),
    ("    const payload = { name: key.name, team: key.team, enabled: !key.enabled, expiresAt: key.expiresAt }\n", "    const payload = { name: key.name, enabled: !key.enabled, expiresAt: key.expiresAt }\n"),
    ("    assert.equal(payload.team, 'search-team')\n", ''),
])
edit('web/test/adminAudit.test.js', [
    ("    assert.equal(auditActionLabel('team_limit.update'), '更新团队限流')\n    assert.equal(auditActionLabel('team_limit.delete'), '清除团队限流')\n", ''),
])
