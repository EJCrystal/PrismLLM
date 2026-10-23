export async function copyText(
  value,
  env = {
    isSecure: typeof window !== 'undefined' ? window.isSecureContext : false,
    clipboard: typeof navigator !== 'undefined' ? navigator.clipboard : null,
    document: typeof document !== 'undefined' ? document : null,
  },
) {
  if (env.clipboard && env.isSecure) {
    await env.clipboard.writeText(value)
    return
  }

  if (!env.document) {
    throw new Error('No clipboard available')
  }

  const textarea = env.document.createElement('textarea')
  textarea.value = value
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '-9999px'
  textarea.style.opacity = '0'
  env.document.body.appendChild(textarea)
  textarea.select()
  const copied = env.document.execCommand('copy')
  env.document.body.removeChild(textarea)
  if (!copied) throw new Error('copy failed')
}
