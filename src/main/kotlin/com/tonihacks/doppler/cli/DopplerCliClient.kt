package com.tonihacks.doppler.cli

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

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
            } catch (_: IllegalArgumentException) {
                DopplerResult.Failure("Failed to parse me JSON")
            }
        }

    fun listProjects(): DopplerResult<List<DopplerProject>> =
        execute(listOf("projects", "--json")).flatMap { json ->
            parseArray(json) {
                DopplerProject(
                    id = it.string("id") ?: "",
                    name = it.string("name") ?: "",
                    slug = it.string("slug") ?: it.string("id") ?: "",
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
            // Value via stdin, never argv — keeps it out of `ps` / process accounting.
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
        PathEnvironmentVariableUtil.findInPath("doppler")?.absolutePath?.let { return it }
        // IDEs often launch without the user's shell PATH, so Homebrew / system bins are invisible.
        return FALLBACK_PATHS.firstOrNull { File(it).canExecute() }
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

    @Suppress("TooGenericExceptionCaught")
    private fun runProcess(process: Process, stdin: String?): DopplerResult<String> {
        // Per-call executor (not ForkJoinPool.commonPool): pump threads must die
        // deterministically before return — commonPool keeps workers alive ~60s and
        // trips IntelliJ's ThreadLeakTracker.
        val streamPool = Executors.newFixedThreadPool(STREAM_POOL_SIZE) { r ->
            Thread(r, "doppler-cli-stream-pump").apply { isDaemon = true }
        }
        return try {
            if (stdin != null) {
                process.outputStream.bufferedWriter().use { it.write(stdin) }
            } else {
                process.outputStream.close()
            }
            val stdoutFuture = streamPool.submit<String> { process.inputStream.bufferedReader().readText() }
            val stderrFuture = streamPool.submit<String> { process.errorStream.bufferedReader().readText() }

            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                killProcessTree(process)
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
            killProcessTree(process)
            DopplerResult.Failure(e.message ?: "Doppler process error")
        } finally {
            // Close read pipes so any pump still blocked in read() exits with IOException.
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            streamPool.shutdownNow()
            val drained = streamPool.awaitTermination(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!drained) {
                LOG.warning("doppler-cli stream pump did not terminate within ${STREAM_DRAIN_TIMEOUT_MS}ms")
            }
        }
    }

    private fun execute(args: List<String>, stdin: String? = null): DopplerResult<String> =
        startProcess(args).flatMap { runProcess(it, stdin) }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val STREAM_DRAIN_TIMEOUT_MS = 2_000L
        private const val STREAM_POOL_SIZE = 2
        private val LOG: Logger = Logger.getLogger(DopplerCliClient::class.java.name)

        private val FALLBACK_PATHS: List<String> = buildList {
            if (SystemInfo.isMac) {
                add("/opt/homebrew/bin/doppler")  // Apple Silicon
                add("/usr/local/bin/doppler")      // Intel Mac
            }
            if (SystemInfo.isLinux) {
                add("/usr/local/bin/doppler")
                add("/usr/bin/doppler")
            }
            if (SystemInfo.isWindows) {
                add("""C:\Program Files\Doppler\bin\doppler.exe""")
            }
        }
    }
}

// Snapshot descendants before destroying parent: once parent dies, descendants are
// reparented to init/launchd and `process.descendants()` no longer returns them, leaving
// orphaned grandchildren holding our stdout/stderr pipes.
private fun killProcessTree(process: Process) {
    val descendants = process.descendants().toList()
    process.destroyForcibly()
    descendants.forEach { it.destroyForcibly() }
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
    } catch (_: IllegalArgumentException) {
        // Drop parser message — could include a content snippet of the secrets JSON.
        DopplerResult.Failure("Failed to parse secrets JSON")
    }

private fun <T> parseArray(json: String, build: (JsonObject) -> T): DopplerResult<List<T>> =
    try {
        DopplerResult.Success(
            Json.parseToJsonElement(json).jsonArray.map { build(it.jsonObject) }
        )
    } catch (_: IllegalArgumentException) {
        DopplerResult.Failure("Failed to parse JSON")
    }
