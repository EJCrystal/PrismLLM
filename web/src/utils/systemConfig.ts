import type { UpstreamProtocolOverrides, UpstreamRequestOverrides } from '../types/api'

function countProtocolOverrides(overrides: UpstreamProtocolOverrides): number {
  return (
    (overrides.headerProfile ? 1 : 0) +
    (overrides.userAgent ? 1 : 0) +
    Object.keys(overrides.headers ?? {}).length +
    Object.keys(overrides.body ?? {}).length
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
    protocols.reduce((total, protocol) => total + countProtocolOverrides(protocol), 0)
  )
}
