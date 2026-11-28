package dev.soven.prismllm.admin.domain

data class AntigravityAgySettingsRecord(
    val enabled: Boolean,
    val proxy: String,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val updatedAt: Long = 0,
    val proxyCredentialsUnreadable: Boolean = false,
) {
    override fun toString(): String =
        "AntigravityAgySettingsRecord(enabled=$enabled, proxy=$proxy, proxyUsername=$proxyUsername, proxyPassword=[REDACTED], updatedAt=$updatedAt, proxyCredentialsUnreadable=$proxyCredentialsUnreadable)"
}
