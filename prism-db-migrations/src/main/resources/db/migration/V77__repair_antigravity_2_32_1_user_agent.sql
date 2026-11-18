-- 早期内置的 Antigravity 请求头把 Windows 客户端 UA 当作默认值写进了目录。
-- 同版本同 profile 的 headers 不会被刷新覆盖（这是保护自定义版本的通用规则），
-- 因此这里只修正这一条已知的旧默认记录；自定义 headers、名称或来源都不动。
update upstream_header_profile_versions
set headers = '{"User-Agent":"antigravity/hub/2.32.1 (aidev_client; os_type=linux; arch=amd64; cl=1021296516)"}'
where profile_id = 'antigravity'
  and version = '2.32.1'
  and name = 'Antigravity'
  and source = 'antigravity:hub'
  and headers = '{"User-Agent":"antigravity/hub/2.32.1 windows/amd64"}';
