// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Blue Isle Software
//
// Published as an InTouch Tool example: https://github.com/intouch-ai-core/intouch-ai
// The tool is MIT. The InTouch server it plugs into is commercial and not published.

package com.blueisle.intouch.tools.git

import com.blueisle.intouch.tool.IToolConnector
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * InTouch Tool: Git
 *
 * Git repository operations via the Git CLI:
 * - Clone, pull, push, commit, checkout, status, log, diff, tag
 * - Credential support via environment variables (GIT_USERNAME, GIT_PASSWORD)
 * - Branch management
 * - Real-time output logging
 *
 * Requires `git` on the server PATH.
 */
class GitTool : IToolConnector {

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private var debug = false
    private var process: Process? = null
    override var messageCallback: ((String) -> Unit)? = null

    // Credential
    private var username = ""
    private var password = ""
    private var sshKeyPath = ""

    companion object {
        const val SUCCESS = 0
        const val WARNING = 1
        const val FAILED = 2
    }

    override fun getName() = "git"
    override fun getDisplayName() = "Git"
    override fun getProvider() = "Blue Isle"
    override fun getVersion() = "1.0.0"
    override fun getPublishedOutputs(): List<String> = listOf("operation", "output")
    override fun getModelNames() = listOf("clone", "pull", "push", "commit", "checkout", "status", "log", "tag")

    override fun getModel(name: String): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "operation": { "type": "string", "enum": ["clone", "pull", "push", "commit", "checkout", "status", "log", "diff", "tag"], "description": "Git operation" },
                    "repoUrl": { "type": "string", "description": "Repository URL (for clone)" },
                    "localPath": { "type": "string", "format": "directorypath", "description": "Local repository path" },
                    "branch": { "type": "string", "description": "Branch name (for checkout, push)", "default": "" },
                    "message": { "type": "string", "description": "Commit message (for commit)" },
                    "addAll": { "type": "boolean", "description": "Stage all changes before commit", "default": true },
                    "tag": { "type": "string", "description": "Tag name (for tag operation)" },
                    "maxEntries": { "type": "integer", "description": "Max log entries to return", "default": 10 },
                    "timeoutSeconds": { "type": "integer", "description": "Timeout in seconds", "default": 300 }
                },
                "required": ["operation", "localPath"]
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "Control", "scope": "#/properties/operation" },
                    { "type": "Control", "scope": "#/properties/repoUrl", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "clone" } } } },
                    { "type": "Control", "scope": "#/properties/localPath" },
                    { "type": "Control", "scope": "#/properties/branch", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["checkout", "push", "clone"] } } } },
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/message" },
                        { "type": "Control", "scope": "#/properties/addAll" }
                    ], "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "commit" } } } },
                    { "type": "Control", "scope": "#/properties/tag", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "tag" } } } },
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/maxEntries" },
                        { "type": "Control", "scope": "#/properties/timeoutSeconds" }
                    ]}
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultJson(name: String): String = mapper.writeValueAsString(mapOf(
        "operation" to "status", "repoUrl" to "", "localPath" to "", "branch" to "",
        "message" to "", "addAll" to true, "tag" to "", "maxEntries" to 10, "timeoutSeconds" to 300
    ))

    override fun isCredentialBased() = true

    override fun getCredentialModel(): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "username": { "type": "string", "description": "Git username (for HTTPS repos)" },
                    "sshKeyPath": { "type": "string", "format": "filepath", "description": "Path to SSH private key (for SSH repos)" }
                }
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "Control", "scope": "#/properties/sshKeyPath" }
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultCredentialJson(): String = mapper.writeValueAsString(mapOf(
        "username" to "", "sshKeyPath" to ""
    ))

    override fun testCredential(json: String): String {
        return try {
            parseCredentialJson(json)
            val pb = ProcessBuilder("git", "--version").redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (p.exitValue() == 0) "" else "Git not found: $output"
        } catch (ex: Exception) { ex.message ?: "Git not available" }
    }

    override fun setCredentialProperties(json: String) { parseCredentialJson(json) }

    private fun parseCredentialJson(json: String) {
        val p = mapper.readValue(json, Map::class.java)
        username = p["username"]?.toString() ?: ""
        password = p["password"]?.toString() ?: ""
        sshKeyPath = p["sshKeyPath"]?.toString() ?: ""
    }

    override fun execute(taskJson: String): Triple<Int, String, String> {
        val config = mapper.readValue(taskJson, Map::class.java)
        val operation = config["operation"]?.toString()?.lowercase() ?: "status"
        val repoUrl = config["repoUrl"]?.toString()?.trim() ?: ""
        val localPath = config["localPath"]?.toString()?.trim() ?: ""
        val branch = config["branch"]?.toString()?.trim() ?: ""
        val message = config["message"]?.toString() ?: ""
        val addAll = config["addAll"] as? Boolean ?: true
        val tag = config["tag"]?.toString()?.trim() ?: ""
        val maxEntries = (config["maxEntries"] as? Number)?.toInt() ?: 10
        val timeout = (config["timeoutSeconds"] as? Number)?.toLong() ?: 300L

        if (localPath.isBlank() && operation != "clone") return Triple(FAILED, "localPath is required", "{}")

        val published = mutableMapOf<String, String>()
        published["operation"] = operation

        try {
            val args: List<String> = when (operation) {
                "clone" -> {
                    if (repoUrl.isBlank()) return Triple(FAILED, "repoUrl required for clone", "{}")
                    listOf("git", "clone", repoUrl, localPath) + if (branch.isNotBlank()) listOf("-b", branch) else emptyList()
                }
                "pull" -> listOf("git", "-C", localPath, "pull")
                "push" -> listOf("git", "-C", localPath, "push") + if (branch.isNotBlank()) listOf("origin", branch) else emptyList()
                "commit" -> {
                    if (addAll) runGit(listOf("git", "-C", localPath, "add", "-A"), localPath, timeout)
                    if (message.isBlank()) return Triple(FAILED, "Commit message required", "{}")
                    listOf("git", "-C", localPath, "commit", "-m", message)
                }
                "checkout" -> {
                    if (branch.isBlank()) return Triple(FAILED, "Branch required for checkout", "{}")
                    listOf("git", "-C", localPath, "checkout", branch)
                }
                "status" -> listOf("git", "-C", localPath, "status", "--short")
                "log" -> listOf("git", "-C", localPath, "log", "--oneline", "-n", maxEntries.toString())
                "diff" -> listOf("git", "-C", localPath, "diff", "--stat")
                "tag" -> {
                    if (tag.isBlank()) listOf("git", "-C", localPath, "tag")
                    else listOf("git", "-C", localPath, "tag", tag)
                }
                else -> return Triple(FAILED, "Unknown operation: $operation", "{}")
            }

            val output = runGit(args, localPath, timeout)
            published["output"] = output
            postMessage("$operation completed")

            return Triple(SUCCESS, "$operation completed", mapper.writeValueAsString(published))
        } catch (ex: Exception) {
            return Triple(FAILED, "$operation failed: ${ex.message}", mapper.writeValueAsString(published))
        }
    }

    private fun runGit(args: List<String>, workDir: String, timeout: Long): String {
        postMessage("Executing: ${args.joinToString(" ")}")
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        if (username.isNotBlank()) pb.environment()["GIT_USERNAME"] = username
        if (password.isNotBlank()) pb.environment()["GIT_PASSWORD"] = password
        if (sshKeyPath.isNotBlank()) pb.environment()["GIT_SSH_COMMAND"] = "ssh -i $sshKeyPath -o StrictHostKeyChecking=no"
        if (workDir.isNotBlank() && File(workDir).isDirectory) pb.directory(File(workDir))

        process = pb.start()
        val output = process!!.inputStream.bufferedReader().readText()
        val completed = process!!.waitFor(timeout, TimeUnit.SECONDS)
        if (!completed) { process!!.destroyForcibly(); throw Exception("Timed out after ${timeout}s") }
        val exitCode = process!!.exitValue()
        if (output.isNotBlank() && debug) output.lines().filter { it.isNotBlank() }.forEach { postMessage("  > $it") }
        if (exitCode != 0) throw Exception("Git exit code $exitCode: ${output.take(300)}")
        return output
    }

    override fun kill() { process?.destroyForcibly() }
    override fun isKillable() = true
    override fun setDebug(enabled: Boolean) { debug = enabled }
    private fun postMessage(msg: String) { messageCallback?.invoke(msg) }
}
