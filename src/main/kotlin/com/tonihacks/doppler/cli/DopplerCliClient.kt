package com.tonihacks.doppler.cli

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class DopplerCliClient(
    private val cliPath: String? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    fun version(): DopplerResult<String> =
        execute(listOf("--version")).map { it.trim() }

    fun me(): DopplerResult<DopplerUser> =
        execute(listOf("me", "--json")).flatMap { json ->
            try {
                val obj = Json.parseToJsonElement(json).jsonObject
                DopplerResult.Success(
                    DopplerUser(
                        email = obj.string("email") ?: "",
                        name = obj.string("name") ?: obj.string("slug") ?: "",
                    )
                )
            } catch (e: IllegalArgumentException) {
                DopplerResult.Failure("Failed to parse me JSON: ${e.message}")
            }
        }

    fun listProjects(): DopplerResult<List<DopplerProject>> =
        execute(listOf("projects", "--json")).flatMap { json ->
            parseArray(json) {
                DopplerProject(
                    id = it.string("id") ?: "",
                    name = it.string("name") ?: "",
                    slug = it.string("slug") ?: "",
                )
            }
        }

    fun listConfigs(project: String): DopplerResult<List<DopplerConfig>> =
        execute(listOf("configs", "--project", project, "--json")).flatMap { json ->
            parseArray(json) {
                DopplerConfig(
                    name = it.string("name") ?: "",
                    project = it.string("project") ?: "",
                    environment = it.string("environment") ?: "",
                )
            }
        }

    fun downloadSecrets(project: String, config: String): DopplerResult<Map<String, String>> =
        execute(
            listOf(
                "secrets", "download",
                "--project", project,
                "--config", config,
                "--no-file",
                "--format", "json",
            )
        ).flatMap { json -> parseSecretsMap(json) }

    fun setSecret(
        project: String,
        config: String,
        key: String,
        value: String,
    ): DopplerResult<Unit> =
        execute(
            args = listOf(
                "secrets", "set", key,
                "--project", project,
                "--config", config,
                "--silent",
            ),
            stdin = value,
        ).map { }

    fun deleteSecret(
        project: String,
        config: String,
        key: String,
    ): DopplerResult<Unit> =
        execute(
            listOf(
                "secrets", "delete", key,
                "--project", project,
                "--config", config,
                "--silent", "--yes",
            )
        ).map { }

    private fun resolveExe(): String? {
        if (!cliPath.isNullOrBlank()) {
            return cliPath.takeIf { File(it).canExecute() }
        }
        return PathEnvironmentVariableUtil.findInPath("doppler")?.absolutePath
    }

    private fun startProcess(args: List<String>): DopplerResult<Process> {
        val exe = resolveExe() ?: return DopplerResult.Failure("doppler CLI not found on PATH")
        val cmd = GeneralCommandLine(listOf(exe) + args).withRedirectErrorStream(false)
        return try {
            DopplerResult.Success(cmd.createProcess())
        } catch (e: ExecutionException) {
            DopplerResult.Failure(e.message ?: "Failed to start doppler process")
        }
    }

    // Boundary translator. Process I/O legitimately throws IOException, InterruptedException,
    // ExecutionException, TimeoutException and CancellationException — Kotlin has no multi-catch
    // and the contract here is "never throw across the cli/ boundary". Any failure ⇒ Result.Failure.
    @Suppress("TooGenericExceptionCaught")
    private fun runProcess(process: Process, stdin: String?): DopplerResult<String> =
        try {
            if (stdin != null) {
                process.outputStream.bufferedWriter().use { it.write(stdin) }
            } else {
                process.outputStream.close()
            }
            val stdoutFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().readText()
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().readText()
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                // Wait briefly for the stream readers to finish — `destroyForcibly` closes
                // the child's stdout/stderr, so `readText()` on those streams returns quickly.
                // Without this drain, the readers keep running on the common ForkJoinPool
                // after the test method exits and IntelliJ's ThreadLeakTracker flags them.
                // `cancel(true)` on a `supplyAsync` future is a no-op: ForkJoinPool tasks
                // are not interrupted by future cancellation.
                runCatching { stdoutFuture.get(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                runCatching { stderrFuture.get(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                DopplerResult.Failure("doppler command timed out after ${timeoutMs}ms")
            } else {
                val stdout = stdoutFuture.get(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                val stderr = stderrFuture.get(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                val exit = process.exitValue()
                if (exit != 0) {
                    DopplerResult.Failure(
                        error = stderr.trim().ifEmpty { "doppler exited with code $exit" },
                        exitCode = exit,
                    )
                } else {
                    DopplerResult.Success(stdout)
                }
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            DopplerResult.Failure(e.message ?: "Doppler process error")
        }

    private fun execute(args: List<String>, stdin: String? = null): DopplerResult<String> =
        startProcess(args).flatMap { runProcess(it, stdin) }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val STREAM_DRAIN_TIMEOUT_MS = 2_000L
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun parseSecretsMap(json: String): DopplerResult<Map<String, String>> =
    try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val map = obj.entries
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
            .toMap()
        DopplerResult.Success(map)
    } catch (e: IllegalArgumentException) {
        DopplerResult.Failure("Failed to parse secrets JSON: ${e.message}")
    }

private fun <T> parseArray(json: String, build: (JsonObject) -> T): DopplerResult<List<T>> =
    try {
        DopplerResult.Success(
            Json.parseToJsonElement(json).jsonArray.map { build(it.jsonObject) }
        )
    } catch (e: IllegalArgumentException) {
        DopplerResult.Failure("Failed to parse JSON: ${e.message}")
    }
