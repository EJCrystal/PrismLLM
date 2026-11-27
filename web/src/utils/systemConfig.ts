import type { UpstreamProtocolOverrides, UpstreamRequestOverrides } from '../types/api'

function countProtocolOverrides(overrides: UpstreamProtocolOverrides): number {
  return (
    (overrides.headerProfile ? 1 : 0) +
    (overrides.userAgent ? 1 : 0) +
    Object.keys(overrides.headers ?? {}).length +
    Object.keys(overrides.body ?? {}).length +
    Object.keys(overrides.extraBody ?? {}).length
  )
}

export function countUpstreamOverrides(overrides: UpstreamRequestOverrides): number {
  const protocols = Object.values(overrides.protocols ?? {}).filter(
    (value): value is UpstreamProtocolOverrides => Boolean(value),
  )
  return (
    (overrides.headerProfile ? 1 : 0) +
    (overrides.userAgent ? 1 : 0) +
    Object.keys(overrides.headers ?? {}).length +
    Object.keys(overrides.body ?? {}).length +
    Object.keys(overrides.extraBody ?? {}).length +
    protocols.reduce((total, protocol) => total + countProtocolOverrides(protocol), 0)
  )
}
export function agyEnvironmentStatus(status: string): string {
  return ({
    DISABLED: '已关闭',
    CHECKING: '正在检查服务运行环境',
    INSTALLING: '正在下载安装缺失依赖',
    PREPARING: '正在下载校验 agy 并检查沙盒',
    STOPPING: '等待当前准备步骤结束后关闭',
    READY: '运行环境已就绪',
    FAILED: '准备失败',
  } as Record<string, string>)[status] || '状态未知'
}
