import type { ChannelModelTestUsage } from './api'

export type PluginContextKind = 'channel' | 'oauth-account'
export type PluginFieldKind = 'text' | 'textarea' | 'select' | 'boolean' | 'number' | 'model' | 'credential' | 'protocol' | 'scope'
export type PluginValue = string | number | boolean | null
export type PluginValues = Record<string, PluginValue>

export interface PluginChoice {
  value: string
  label: string
}

export interface PluginField {
  key: string
  label: string
  kind: PluginFieldKind
  required: boolean
  defaultValue: PluginValue
  help: string
  maxLength: number | null
  min: number | null
  max: number | null
  options: PluginChoice[]
}

export interface PluginManifest {
  id: string
  version: string
  displayName: string
  description: string
  schemaVersion: number
  apiVersion: number
  permissions: string[]
  configuration: PluginField[]
}

export interface PluginAction {
  id: string
  label: string
  fields: PluginField[]
  resource: 'model-test-targets'
  supportsBatch: boolean
  resultKind: 'probe-v1'
  notes: string[]
}

export interface PluginUiEntry {
  id: string
  label: string
  placement: 'channel-tests' | 'oauth-account-tests' | 'plugin-page'
  contexts: PluginContextKind[]
  action: PluginAction
  icon: string
}

export interface PluginUiDescriptor {
  schemaVersion: number
  entries: PluginUiEntry[]
}

export interface PluginCatalogItem {
  manifest: PluginManifest
  ui: PluginUiDescriptor
  sha256: string
  source: string
  compatible: boolean
  compatibilityReason: string | null
}

export interface PluginRuntimeState {
  revision: number
  id: string
  displayName: string
  description: string
  source: string
  installed: boolean
  installedVersion: string | null
  installedSha256: string | null
  activeVersion: string | null
  desiredEnabled: boolean
  admissionEnabled: boolean
  requiresRestart: boolean
  status: 'active' | 'pending-restart' | 'disabled' | 'uninstalled' | 'error'
  loadError: string | null
  permissions: string[]
  approvedPermissions: string[]
  configuration: PluginValues
  activeConfiguration: PluginValues | null
  configurationFields: PluginField[]
  activeUi: PluginUiDescriptor | null
}

export interface PluginUiContribution {
  pluginId: string
  activeVersion: string
  entry: PluginUiEntry
}

export interface PluginInvocationContext {
  kind: PluginContextKind
  id: string
}

export interface PluginInvocationRequest {
  actionId: string
  context: PluginInvocationContext
  values: PluginValues
  expectedActiveVersion: string
}

export interface PluginExecution {
  pluginId: string
  activeVersion: string
  target: PluginInvocationContext & {
    model: string
    protocol: string
    displayName: string
    credentialId: string | null
    credentialName: string | null
  }
  plan: { prompt: string; maxOutputTokens: number; timeoutMs: number }
  outcome: {
    success: boolean
    statusCode: number | null
    latencyMs: number
    responseText: string | null
    errorMessage: string | null
    usage: ChannelModelTestUsage | null
    upstreamReportedModel: string | null
  }
  report: {
    success: boolean
    title: string
    metrics: Array<{ label: string; value: string }>
    notices: string[]
    text: string | null
  }
}
