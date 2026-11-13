package com.example.mobileagent.security

object SensitiveFilePolicy {
    private val exactNames = setOf(
        ".env", ".npmrc", ".pypirc", "credentials", "credentials.json",
        "secrets.json", "id_rsa", "id_ed25519", ".git-credentials"
    )
    private val blockedSuffixes = listOf(".pem", ".key", ".p12", ".pfx", ".keystore")

    fun isSensitive(relativePath: String): Boolean {
        val normalized = relativePath.replace('\\\\', '/').trimStart('/').lowercase()
        if (normalized == ".git/config" || normalized.startsWith(".git/config/")) return true
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.any { it == ".ssh" }) return true
        return parts.any { part ->
            val name = part.lowercase()
            name in exactNames || name.startsWith(".env.") ||
                blockedSuffixes.any { name.endsWith(it) }
        }
    }

    fun requireAllowed(path: String) {
        require(!isSensitive(path)) { "Access blocked: this path may contain credentials or secrets." }
    }
}
