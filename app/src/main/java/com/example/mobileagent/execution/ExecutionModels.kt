package com.example.mobileagent.execution

data class CommandProposal(
    val id: String,
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val timeoutSeconds: Long,
    val purpose: String
) {
    fun displayCommand(): String = (listOf(executable) + arguments).joinToString(" ") { quoteForDisplay(it) }

    private fun quoteForDisplay(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9_./:=+-]+"))) value
        else "'" + value.replace("'", "'\\''") + "'"
}

data class CommandApproval(
    val proposalId: String,
    val fingerprint: String,
    val nonce: String
)

data class ExecutionResult(
    val proposalId: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val durationMillis: Long
)
