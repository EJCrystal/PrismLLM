package dev.soven.prismllm.notification

import kotlinx.serialization.Serializable

/** Reports only whether notification credentials are configured, never their values. */
@Serializable
data class NotificationCredentialStatus(
    val urlConfigured: Boolean,
    val bearerTokenConfigured: Boolean,
    val signingSecretConfigured: Boolean,
)

/** Reads notification credentials from the supplied environment lookup only. */
class NotificationSecrets(
    private val readEnvironment: (String) -> String? = System::getenv,
) {
    fun status(prefix: String): NotificationCredentialStatus {
        if (!isValidPrefix(prefix)) return NotificationCredentialStatus(false, false, false)
        return NotificationCredentialStatus(
            urlConfigured = readEnvironment("${prefix}_URL").configured(),
            bearerTokenConfigured = readEnvironment("${prefix}_BEARER_TOKEN").configured(),
            signingSecretConfigured = readEnvironment("${prefix}_SIGNING_SECRET").configured(),
        )
    }

    internal fun resolve(prefix: String): NotificationEnvironment? {
        if (!isValidPrefix(prefix)) return null
        return NotificationEnvironment(
            url = readEnvironment("${prefix}_URL").cleaned(),
            bearerToken = readEnvironment("${prefix}_BEARER_TOKEN").cleaned(),
            signingSecret = readEnvironment("${prefix}_SIGNING_SECRET").cleaned(),
        )
    }

    companion object {
        private val PREFIX = Regex("^PRISM_NOTIFY_[A-Z][A-Z0-9_]{0,47}$")

        @JvmStatic
        fun isValidPrefix(prefix: String): Boolean = PREFIX.matches(prefix)
    }
}

internal class NotificationEnvironment(
    val url: String?,
    val bearerToken: String?,
    val signingSecret: String?,
) {
    override fun toString(): String = "NotificationEnvironment([redacted])"
}

private fun String?.configured(): Boolean = !this.isNullOrBlank()
private fun String?.cleaned(): String? = this?.takeIf { it.isNotBlank() }
