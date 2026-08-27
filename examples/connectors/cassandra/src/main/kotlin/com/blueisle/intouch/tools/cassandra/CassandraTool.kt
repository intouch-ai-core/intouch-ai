// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Blue Isle Software
//
// Published as an InTouch Tool example: https://github.com/intouch-ai-core/intouch-ai
// The tool is MIT. The InTouch server it plugs into is commercial and not published.

package com.blueisle.intouch.tools.cassandra

import com.blueisle.intouch.tool.IToolConnector
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.CqlSessionBuilder
import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.*
import com.datastax.oss.driver.api.core.type.DataType
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.*
import java.net.InetSocketAddress
import java.nio.file.Paths
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

/**
 * InTouch Tool: Apache Cassandra
 *
 * Apache Cassandra NoSQL database operations via the DataStax Java Driver v4:
 * - Execute: run CQL statements (DDL, DML, queries)
 * - Export: query results to delimited file (CSV, TSV, pipe, custom)
 * - Import: load delimited file into a table with batched inserts
 * - List Keyspaces: enumerate all keyspaces
 * - List Tables: enumerate tables in a keyspace
 * - Describe: get table schema (columns, types, partition/clustering keys)
 *
 * Features:
 * - Multi-node contact points
 * - SSL/TLS with truststore support
 * - Configurable consistency level
 * - Configurable datacenter for token-aware routing
 * - Batched imports with configurable batch size
 * - Real-time progress logging via messageCallback
 * - Graceful kill support
 *
 * Uses DataStax Java Driver 4.17 — production-grade, async-capable,
 * automatic node discovery, token-aware routing, speculative execution.
 */
class CassandraTool : IToolConnector {

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private var debug = false
    @Volatile private var killed = false
    private var session: CqlSession? = null
    override var messageCallback: ((String) -> Unit)? = null

    // Credential properties
    private var contactPoints = ""
    private var port = 9042
    private var datacenter = "datacenter1"
    private var login = ""
    private var password = ""
    private var ssl = false
    private var truststorePath = ""
    private var truststorePassword = ""
    private var consistencyLevel = "LOCAL_QUORUM"

    companion object {
        const val SUCCESS = 0
        const val WARNING = 1
        const val FAILED = 2

        /** Rows `execute` publishes when maxRows isn't set. Bounded because published outputs are
         *  held in memory and written to the activity log; export handles bulk extraction. */
        const val DEFAULT_PUBLISHED_ROWS = 1000
    }

    // ── Metadata ──────────────────────────────────────────────

    override fun getName() = "cassandra"
    override fun getDisplayName() = "Apache Cassandra"
    override fun getProvider() = "Blue Isle"
    override fun getVersion() = "1.0.0"
    override fun getPublishedOutputs(): List<String> = listOf("applied", "clusteringKeys", "columnCount", "columns", "count", "errors", "inputFile", "keyspace", "keyspaces", "operation", "outputFile", "partitionKeys", "rowCount", "rows", "rowsPublished", "table", "tables", "truncated")

    // ── Task Models ───────────────────────────────────────────

    override fun getModelNames() = listOf("execute", "export", "import", "list-keyspaces", "list-tables", "describe")

    override fun getModel(name: String): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "operation": { "type": "string", "enum": ["execute", "export", "import", "list-keyspaces", "list-tables", "describe"], "description": "Cassandra operation to perform" },
                    "statement": { "type": "string", "description": "CQL statement to execute (for execute/export operations)" },
                    "keyspace": { "type": "string", "description": "Keyspace name (for list-tables, describe, import)" },
                    "table": { "type": "string", "description": "Table name (for describe, import)" },
                    "outputFile": { "type": "string", "format": "filepath", "description": "Output file path for export results" },
                    "inputFile": { "type": "string", "format": "filepath", "description": "Input file path for import" },
                    "delimiter": { "type": "string", "enum": ["comma", "tab", "pipe", "other"], "description": "Field delimiter", "default": "comma" },
                    "customDelimiter": { "type": "string", "description": "Custom delimiter character (when delimiter is 'other')" },
                    "hasHeader": { "type": "boolean", "description": "File has/should have a header row", "default": true },
                    "batchSize": { "type": "integer", "description": "Rows per batch for import", "default": 50 },
                    "maxErrors": { "type": "integer", "description": "Maximum errors before stopping import (0 = unlimited)", "default": 0 },
                    "skipRows": { "type": "integer", "description": "Number of data rows to skip during import", "default": 0 },
                    "maxRows": { "type": "integer", "description": "Row limit: rows published by execute, written by export, or read by import (0 = default limit for execute, all for export/import)", "default": 0 },
                    "nullFormat": { "type": "string", "description": "String representation of null values", "default": "" },
                    "timestampFormat": { "type": "string", "description": "Timestamp format pattern (Java SimpleDateFormat)", "default": "yyyy-MM-dd HH:mm:ss.SSSZ" }
                },
                "required": ["operation"]
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "Control", "scope": "#/properties/operation" },
                    { "type": "Control", "scope": "#/properties/statement", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["execute", "export"] } } } },
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/keyspace" },
                        { "type": "Control", "scope": "#/properties/table" }
                    ], "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["list-tables", "describe", "import"] } } } },
                    { "type": "Control", "scope": "#/properties/outputFile", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "export" } } } },
                    { "type": "Control", "scope": "#/properties/inputFile", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "import" } } } },
                    { "type": "Group", "label": "Format", "elements": [
                        { "type": "HorizontalLayout", "elements": [
                            { "type": "Control", "scope": "#/properties/delimiter" },
                            { "type": "Control", "scope": "#/properties/customDelimiter", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/delimiter", "schema": { "const": "other" } } } }
                        ]},
                        { "type": "HorizontalLayout", "elements": [
                            { "type": "Control", "scope": "#/properties/hasHeader" },
                            { "type": "Control", "scope": "#/properties/nullFormat" },
                            { "type": "Control", "scope": "#/properties/timestampFormat" }
                        ]}
                    ], "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["export", "import"] } } } },
                    { "type": "Control", "scope": "#/properties/maxRows", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["execute", "export", "import"] } } } },
                    { "type": "Group", "label": "Import Limits", "elements": [
                        { "type": "HorizontalLayout", "elements": [
                            { "type": "Control", "scope": "#/properties/batchSize" },
                            { "type": "Control", "scope": "#/properties/maxErrors" }
                        ]},
                        { "type": "Control", "scope": "#/properties/skipRows" }
                    ], "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "import" } } } }
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultJson(name: String): String = mapper.writeValueAsString(mapOf(
        "operation" to "execute",
        "statement" to "",
        "keyspace" to "",
        "table" to "",
        "outputFile" to "",
        "inputFile" to "",
        "delimiter" to "comma",
        "customDelimiter" to "",
        "hasHeader" to true,
        "batchSize" to 50,
        "maxErrors" to 0,
        "skipRows" to 0,
        "maxRows" to 0,
        "nullFormat" to "",
        "timestampFormat" to "yyyy-MM-dd HH:mm:ss.SSSZ"
    ))

    // ── Credential ────────────────────────────────────────────

    override fun isCredentialBased() = true

    override fun getCredentialModel(): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "contactPoints": { "type": "string", "description": "Extra cluster nodes, comma-separated (e.g. node2,node3). Leave blank to use the Server field above." },
                    "datacenter": { "type": "string", "description": "Local datacenter name for token-aware routing", "default": "datacenter1" },
                    "ssl": { "type": "boolean", "description": "Enable SSL/TLS encryption", "default": false },
                    "truststorePath": { "type": "string", "format": "filepath", "description": "Path to Java truststore file (.jks)" },
                    "truststorePassword": { "type": "string", "description": "Truststore password" },
                    "consistencyLevel": { "type": "string", "enum": ["ANY", "ONE", "TWO", "THREE", "QUORUM", "ALL", "LOCAL_QUORUM", "EACH_QUORUM", "SERIAL", "LOCAL_SERIAL", "LOCAL_ONE"], "description": "Default consistency level", "default": "LOCAL_QUORUM" }
                }
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/contactPoints" },
                        { "type": "Control", "scope": "#/properties/datacenter" }
                    ]},
                    { "type": "Control", "scope": "#/properties/consistencyLevel" },
                    { "type": "Control", "scope": "#/properties/ssl" },
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/truststorePath", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/ssl", "schema": { "const": true } } } },
                        { "type": "Control", "scope": "#/properties/truststorePassword", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/ssl", "schema": { "const": true } } } }
                    ]}
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultCredentialJson(): String = mapper.writeValueAsString(mapOf(
        "contactPoints" to "",
        "datacenter" to "datacenter1",
        "ssl" to false,
        "truststorePath" to "",
        "truststorePassword" to "",
        "consistencyLevel" to "LOCAL_QUORUM"
    ))

    override fun testCredential(json: String): String {
        return try {
            parseCredentialJson(json)
            connect()
            // Actually run a statement rather than only reading driver metadata. Metadata is
            // populated from the control connection and succeeds even when no node is ROUTABLE for
            // queries — e.g. when `datacenter` names a DC the cluster doesn't have, every real
            // operation then fails with "No node was available to execute the query" while the
            // credential test reports success. A trivial query exercises the same routing and
            // consistency path the tool's operations use, so a green test means something.
            val row = session!!.execute("SELECT release_version FROM system.local").one()
            val version = row?.getString("release_version") ?: "unknown"
            val nodes = session!!.metadata.nodes.size
            disconnect()
            postMessage("Connected: Cassandra $version ($nodes node(s), dc=$datacenter)")
            ""
        } catch (ex: Exception) {
            disconnect()
            ex.message ?: "Connection failed"
        }
    }

    override fun setCredentialProperties(json: String) {
        parseCredentialJson(json)
    }

    private fun parseCredentialJson(json: String) {
        val props = mapper.readValue(json, Map::class.java)
        // Contact points come from the standard top-level Server field that every other credential
        // type uses (ExternalConnectorAdapter.mergeCredentialJson injects it as "server"), plus any
        // extra nodes listed in the type-specific `contactPoints` property. Reading only
        // `contactPoints` — as this did originally — meant filling in Server, the obvious thing to
        // do, produced "No contact points provided", with no form field that mapped onto it.
        contactPoints = listOf(props["server"]?.toString(), props["contactPoints"]?.toString())
            .flatMap { it.orEmpty().split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")
        port = (props["port"] as? Number)?.toInt() ?: 9042
        datacenter = props["datacenter"]?.toString()?.ifBlank { "datacenter1" } ?: "datacenter1"
        login = props["login"]?.toString() ?: ""
        password = props["password"]?.toString() ?: ""
        ssl = props["ssl"] as? Boolean ?: false
        truststorePath = props["truststorePath"]?.toString() ?: ""
        truststorePassword = props["truststorePassword"]?.toString() ?: ""
        consistencyLevel = props["consistencyLevel"]?.toString() ?: "LOCAL_QUORUM"
    }

    // ── Execution ─────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    override fun execute(taskJson: String): Triple<Int, String, String> {
        val config = mapper.readValue(taskJson, Map::class.java)
        val operation = config["operation"]?.toString()?.lowercase() ?: "execute"

        val published = mutableMapOf<String, String>()
        published["operation"] = operation

        try {
            postMessage("Connecting to Cassandra ($contactPoints:$port)...")
            connect()
            val nodes = session!!.metadata.nodes.size
            postMessage("Connected ($nodes node(s), dc=$datacenter)")

            when (operation) {
                "execute" -> doExecute(config, published)
                "export" -> doExport(config, published)
                "import" -> doImport(config, published)
                "list-keyspaces" -> doListKeyspaces(published)
                "list-tables" -> doListTables(config, published)
                "describe" -> doDescribe(config, published)
                else -> {
                    disconnect()
                    return Triple(FAILED, "Unknown operation: $operation", mapper.writeValueAsString(published))
                }
            }

            disconnect()
            return Triple(SUCCESS, "$operation completed successfully", mapper.writeValueAsString(published))

        } catch (ex: Exception) {
            disconnect()
            return Triple(FAILED, "$operation failed: ${ex.message}", mapper.writeValueAsString(published))
        }
    }

    // ── Operations ────────────────────────────────────────────

    private fun doExecute(config: Map<*, *>, published: MutableMap<String, String>) {
        val statement = config["statement"]?.toString()?.trim() ?: ""
        if (statement.isBlank()) throw Exception("CQL statement is required")

        postMessage("Executing CQL...")
        val rs = session!!.execute(SimpleStatement.newInstance(statement)
            .setConsistencyLevel(parseConsistency()))

        if (rs.columnDefinitions.size() > 0) {
            val colDefs = rs.columnDefinitions
            val colCount = colDefs.size()
            val colNames = (0 until colCount).map { colDefs[it].name.asInternal() }

            // How many rows to materialize into `rows`. An explicit maxRows wins; otherwise cap it,
            // because published outputs are held in memory and written to the activity log, and a
            // SELECT with no LIMIT can return millions.
            val maxRows = (config["maxRows"] as? Number)?.toInt() ?: 0
            val publishLimit = if (maxRows > 0) maxRows else DEFAULT_PUBLISHED_ROWS

            // rowCount stays the TRUE total even when fewer rows are published, so it keeps meaning
            // the same thing it always did. `truncated` says whether `rows` is the whole story.
            var count = 0
            val collected = mutableListOf<Map<String, String?>>()
            for (row in rs) {
                checkKilled()
                count++
                if (collected.size < publishLimit) {
                    collected.add(
                        (0 until colCount).associate { i ->
                            colNames[i] to row.getObject(i)?.toString()
                        }
                    )
                }
            }

            val truncated = count > collected.size
            published["rowCount"] = count.toString()
            published["rowsPublished"] = collected.size.toString()
            published["truncated"] = truncated.toString()
            published["columns"] = colNames.joinToString(",")
            // Values are stringified (null stays null): every InTouch task output is text, and
            // Cassandra types like inet/blob/uuid have no natural JSON form.
            published["rows"] = mapper.writeValueAsString(collected)

            postMessage("Query returned $count row(s)")
            if (truncated) {
                postMessage(
                    "Published the first ${collected.size} row(s) of $count — set maxRows to raise " +
                        "the limit, or use the export operation to write them all to a file"
                )
            }
        } else {
            published["applied"] = (rs.wasApplied()).toString()
            postMessage("Statement executed (applied=${rs.wasApplied()})")
        }
    }

    private fun doExport(config: Map<*, *>, published: MutableMap<String, String>) {
        val statement = config["statement"]?.toString()?.trim() ?: ""
        val outputFile = config["outputFile"]?.toString()?.trim() ?: ""
        val hasHeader = config["hasHeader"] as? Boolean ?: true
        val maxRows = (config["maxRows"] as? Number)?.toInt() ?: 0
        val nullFormat = config["nullFormat"]?.toString() ?: ""
        val delim = resolveDelimiter(config)

        if (statement.isBlank()) throw Exception("CQL SELECT statement is required for export")
        if (outputFile.isBlank()) throw Exception("Output file is required for export")

        postMessage("Executing query for export...")
        val rs = session!!.execute(SimpleStatement.newInstance(statement)
            .setConsistencyLevel(parseConsistency()))

        val colDefs = rs.columnDefinitions
        val colCount = colDefs.size()

        File(outputFile).parentFile?.mkdirs()
        BufferedWriter(FileWriter(outputFile)).use { writer ->
            // Header
            if (hasHeader) {
                // asInternal(), not asCql(): the CQL form wraps the name in double quotes, which
                // escapeDelimited would then escape again, yielding a header field that parses back
                // as a quoted name and no longer matches the column on import.
                val header = (0 until colCount).joinToString(delim) {
                    escapeDelimited(colDefs[it].name.asInternal(), delim)
                }
                writer.write(header)
                writer.newLine()
            }

            // Data
            var count = 0
            for (row in rs) {
                checkKilled()
                val line = (0 until colCount).joinToString(delim) { i ->
                    val value = row.getObject(i)
                    escapeDelimited(value?.toString() ?: nullFormat, delim)
                }
                writer.write(line)
                writer.newLine()
                count++

                if (count % 10000 == 0) postMessage("Exported $count rows...")
                if (maxRows > 0 && count >= maxRows) {
                    postMessage("Reached maxRows limit ($maxRows)")
                    break
                }
            }

            published["rowCount"] = count.toString()
            published["outputFile"] = outputFile
            postMessage("Exported $count row(s) to $outputFile")
        }
    }

    private fun doImport(config: Map<*, *>, published: MutableMap<String, String>) {
        val inputFile = config["inputFile"]?.toString()?.trim() ?: ""
        val keyspace = config["keyspace"]?.toString()?.trim() ?: ""
        val table = config["table"]?.toString()?.trim() ?: ""
        val hasHeader = config["hasHeader"] as? Boolean ?: true
        val batchSize = (config["batchSize"] as? Number)?.toInt() ?: 50
        val maxErrors = (config["maxErrors"] as? Number)?.toInt() ?: 0
        val skipRows = (config["skipRows"] as? Number)?.toInt() ?: 0
        val maxRows = (config["maxRows"] as? Number)?.toInt() ?: 0
        val nullFormat = config["nullFormat"]?.toString() ?: ""
        val delim = resolveDelimiter(config)

        if (inputFile.isBlank()) throw Exception("Input file is required for import")
        if (keyspace.isBlank()) throw Exception("Keyspace is required for import")
        if (table.isBlank()) throw Exception("Table is required for import")

        val file = File(inputFile)
        if (!file.exists()) throw Exception("Input file not found: $inputFile")

        // Get table metadata for column types
        val tableMeta = session!!.metadata
            .getKeyspace(keyspace).orElseThrow { Exception("Keyspace '$keyspace' not found") }
            .getTable(table).orElseThrow { Exception("Table '$table' not found in keyspace '$keyspace'") }

        val columns = tableMeta.columns.values.toList()
        val columnNames = columns.map { it.name.asCql(false) }

        postMessage("Importing into $keyspace.$table (${columns.size} columns)...")

        // Build prepared INSERT
        val placeholders = columnNames.joinToString(", ") { "?" }
        val colList = columnNames.joinToString(", ")
        val insertCql = "INSERT INTO $keyspace.$table ($colList) VALUES ($placeholders)"
        val prepared = session!!.prepare(insertCql)

        var imported = 0
        var errors = 0
        var skipped = 0
        var lineNum = 0

        var firstError = ""

        BufferedReader(FileReader(file)).use { reader ->
            // fileIndexForColumn[i] is the field in each line that feeds table column i, or -1 when
            // the file has no column for it.
            //
            // Cassandra reports columns as partition key, clustering keys, then the rest
            // ALPHABETICALLY — an order the file has no reason to share. Binding by position (what
            // this did originally, ignoring the header it had already parsed) therefore fed the
            // wrong field to each column: a plain id/name/age export failed every row, and where two
            // columns share a type it would have silently written values into the wrong ones.
            var fileIndexForColumn = columnNames.indices.toList()

            // Read header
            if (hasHeader) {
                val headerLine = reader.readLine() ?: throw Exception("File is empty")
                val headerColumns = parseDelimitedLine(headerLine, delim).map { it.trim() }
                fileIndexForColumn = columns.map { col ->
                    val internal = col.name.asInternal()
                    headerColumns.indexOfFirst { it.equals(internal, ignoreCase = true) }
                }
                val matched = columns.indices.filter { fileIndexForColumn[it] >= 0 }
                if (matched.isEmpty()) {
                    throw Exception(
                        "No column in the file matches ${keyspace}.${table}. " +
                            "File header: ${headerColumns.joinToString(", ")}; " +
                            "table columns: ${columns.joinToString(", ") { it.name.asInternal() }}"
                    )
                }
                val unmatched = columns.indices.filter { fileIndexForColumn[it] < 0 }
                if (unmatched.isNotEmpty()) {
                    postMessage(
                        "No column in the file for: " +
                            unmatched.joinToString(", ") { columns[it].name.asInternal() } +
                            " — these will be left null"
                    )
                }
                lineNum++
            }

            var line: String?
            val batch = mutableListOf<BoundStatement>()

            while (reader.readLine().also { line = it } != null) {
                checkKilled()
                lineNum++

                // Skip rows
                if (skipped < skipRows) {
                    skipped++
                    continue
                }

                val values = parseDelimitedLine(line!!, delim)
                try {
                    // Reassign on every bind — see bindValue(): BoundStatement is immutable in the
                    // 4.x driver, so a discarded return value means the column stays unset.
                    var bound = prepared.bind()
                    for (i in columnNames.indices) {
                        val src = fileIndexForColumn[i]
                        val raw = if (src in 0 until values.size) values[src].trim() else null
                        bound = if (raw == null || raw == nullFormat || raw.isEmpty()) {
                            bound.setToNull(i)
                        } else {
                            bindValue(bound, i, columns[i].type, raw)
                        }
                    }
                    batch.add(bound.setConsistencyLevel(parseConsistency()))

                    if (batch.size >= batchSize) {
                        executeBatch(batch)
                        imported += batch.size
                        batch.clear()
                        if (imported % 5000 == 0) postMessage("Imported $imported rows...")
                    }

                } catch (ex: Exception) {
                    errors++
                    if (firstError.isEmpty()) firstError = "line $lineNum: ${ex.message}"
                    if (debug) postMessage("Line $lineNum error: ${ex.message}")
                    if (maxErrors > 0 && errors >= maxErrors) {
                        throw Exception("Maximum errors ($maxErrors) reached at line $lineNum: ${ex.message}")
                    }
                }

                if (maxRows > 0 && (imported + batch.size) >= maxRows) {
                    postMessage("Reached maxRows limit ($maxRows)")
                    break
                }
            }

            // Flush remaining batch
            if (batch.isNotEmpty()) {
                executeBatch(batch)
                imported += batch.size
            }
        }

        published["rowCount"] = imported.toString()
        published["errors"] = errors.toString()
        published["inputFile"] = inputFile
        postMessage("Imported $imported row(s) ($errors error(s)) from $inputFile")

        // An import that loaded nothing while rejecting every row is a failed load, not a completed
        // one. Reporting success there let a scheduled job go green having moved no data at all,
        // with the reason buried behind the debug flag — so surface the first error and fail.
        if (imported == 0 && errors > 0) {
            throw Exception("Import failed: all $errors row(s) rejected — $firstError")
        }
    }

    private fun doListKeyspaces(published: MutableMap<String, String>) {
        val keyspaces = session!!.metadata.keyspaces.keys.map { it.asCql(false) }.sorted()
        published["keyspaces"] = keyspaces.joinToString(",")
        published["count"] = keyspaces.size.toString()
        postMessage("Keyspaces (${keyspaces.size}):")
        keyspaces.forEach { postMessage("  $it") }
    }

    private fun doListTables(config: Map<*, *>, published: MutableMap<String, String>) {
        val keyspace = config["keyspace"]?.toString()?.trim() ?: ""
        if (keyspace.isBlank()) throw Exception("Keyspace is required")

        val ks = session!!.metadata.getKeyspace(keyspace)
            .orElseThrow { Exception("Keyspace '$keyspace' not found") }

        val tables = ks.tables.keys.map { it.asCql(false) }.sorted()
        published["keyspace"] = keyspace
        published["tables"] = tables.joinToString(",")
        published["count"] = tables.size.toString()
        postMessage("Tables in $keyspace (${tables.size}):")
        tables.forEach { postMessage("  $it") }
    }

    private fun doDescribe(config: Map<*, *>, published: MutableMap<String, String>) {
        val keyspace = config["keyspace"]?.toString()?.trim() ?: ""
        val table = config["table"]?.toString()?.trim() ?: ""
        if (keyspace.isBlank()) throw Exception("Keyspace is required")
        if (table.isBlank()) throw Exception("Table is required")

        val tableMeta = session!!.metadata
            .getKeyspace(keyspace).orElseThrow { Exception("Keyspace '$keyspace' not found") }
            .getTable(table).orElseThrow { Exception("Table '$table' not found") }

        val partitionKeys = tableMeta.partitionKey.map { it.name.asCql(false) }
        val clusteringKeys = tableMeta.clusteringColumns.keys.map { it.name.asCql(false) }
        val columns = tableMeta.columns.values.map { "${it.name.asCql(false)} ${it.type.asCql(false, false)}" }

        published["keyspace"] = keyspace
        published["table"] = table
        published["partitionKeys"] = partitionKeys.joinToString(",")
        published["clusteringKeys"] = clusteringKeys.joinToString(",")
        published["columnCount"] = columns.size.toString()

        postMessage("$keyspace.$table:")
        postMessage("  Partition keys: ${partitionKeys.joinToString(", ")}")
        postMessage("  Clustering keys: ${clusteringKeys.joinToString(", ")}")
        postMessage("  Columns (${columns.size}):")
        columns.forEach { postMessage("    $it") }
    }

    // ── Lifecycle ─────────────────────────────────────────────

    override fun kill() {
        killed = true
        disconnect()
    }

    override fun isKillable() = true
    override fun setDebug(enabled: Boolean) { debug = enabled }

    // ── Connection Management ─────────────────────────────────

    private fun connect() {
        val builder: CqlSessionBuilder = CqlSession.builder()

        // Contact points
        val points = contactPoints.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (points.isEmpty()) throw Exception("No contact points provided")
        for (point in points) {
            builder.addContactPoint(InetSocketAddress(point, port))
        }

        builder.withLocalDatacenter(datacenter)

        // Authentication
        if (login.isNotBlank()) {
            builder.withAuthCredentials(login, password)
        }

        // SSL
        if (ssl) {
            val sslContext = buildSslContext()
            builder.withSslContext(sslContext)
        }

        session = builder.build()
    }

    private fun buildSslContext(): SSLContext {
        val sslContext = SSLContext.getInstance("TLS")

        if (truststorePath.isNotBlank()) {
            val trustStore = KeyStore.getInstance("JKS")
            FileInputStream(truststorePath).use { fis ->
                trustStore.load(fis, truststorePassword.toCharArray())
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(trustStore)
            sslContext.init(null, tmf.trustManagers, java.security.SecureRandom())
        } else {
            sslContext.init(null, null, java.security.SecureRandom())
        }

        return sslContext
    }

    private fun disconnect() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun executeBatch(statements: List<BoundStatement>) {
        // Use unlogged batch for performance (no cross-partition atomicity needed for bulk import)
        val batchBuilder = BatchStatement.builder(DefaultBatchType.UNLOGGED)
        for (stmt in statements) {
            batchBuilder.addStatement(stmt)
        }
        session!!.execute(batchBuilder.build())
    }

    /**
     * Bind one value and RETURN the resulting statement.
     *
     * BoundStatement is immutable in the 4.x driver: every setter returns a new instance rather than
     * mutating the receiver. This discarded those return values, so nothing was ever bound and every
     * row failed with "Unset value at index 0" — counted as a per-row error and still reported as a
     * successful import. The caller must assign the result.
     */
    private fun bindValue(bound: BoundStatement, index: Int, type: DataType, raw: String): BoundStatement {
        val typeName = type.asCql(false, false).lowercase()
        return try {
            when {
                typeName == "text" || typeName == "varchar" || typeName == "ascii" -> bound.setString(index, raw)
                typeName == "int" -> bound.setInt(index, raw.toInt())
                typeName == "bigint" || typeName == "counter" -> bound.setLong(index, raw.toLong())
                typeName == "float" -> bound.setFloat(index, raw.toFloat())
                typeName == "double" -> bound.setDouble(index, raw.toDouble())
                typeName == "boolean" -> bound.setBoolean(index, raw.toBoolean())
                typeName == "uuid" || typeName == "timeuuid" -> bound.setUuid(index, java.util.UUID.fromString(raw))
                typeName == "decimal" -> bound.setBigDecimal(index, java.math.BigDecimal(raw))
                typeName == "varint" -> bound.setBigInteger(index, java.math.BigInteger(raw))
                typeName == "inet" -> bound.set(index, java.net.InetAddress.getByName(raw), java.net.InetAddress::class.java)
                typeName == "smallint" -> bound.setShort(index, raw.toShort())
                typeName == "tinyint" -> bound.setByte(index, raw.toByte())
                typeName == "blob" -> bound.setByteBuffer(index, java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(raw)))
                else -> bound.setString(index, raw) // Fallback: set as string
            }
        } catch (ex: Exception) {
            throw Exception("Column $index ($typeName): cannot parse '$raw': ${ex.message}")
        }
    }

    /**
     * Quote a field for delimited output, RFC 4180 style: wrap in double quotes when the value
     * contains the delimiter, a double quote, or a line break, and double up any embedded quotes.
     *
     * Export previously wrote every value raw, so a single comma inside a text column — a person's
     * name, an address, a description — silently produced a row with more fields than the header,
     * which every downstream parser then misreads.
     */
    private fun escapeDelimited(value: String, delim: String): String {
        val needsQuoting = value.contains(delim) || value.contains('"') ||
            value.contains('\n') || value.contains('\r')
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /**
     * Split one line of a delimited file, honouring RFC 4180 quoting so the values written by
     * escapeDelimited() survive the round trip. A naive split() would tear a quoted field apart at
     * the first delimiter inside it.
     *
     * Operates on a single line: a quoted value containing a line break is not reassembled, because
     * the import loop reads line by line. Such a value exports correctly for other consumers but
     * cannot be re-imported by this tool.
     */
    private fun parseDelimitedLine(line: String, delim: String): List<String> {
        // Multi-character delimiters can't be scanned char-by-char; nothing to quote-parse either,
        // since only single-char delimiters are produced by resolveDelimiter's presets.
        if (delim.length != 1) return line.split(delim)
        val sep = delim[0]
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++          // escaped quote inside a quoted field
                }
                c == '"' -> inQuotes = !inQuotes
                c == sep && !inQuotes -> {
                    fields.add(current.toString()); current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    private fun resolveDelimiter(config: Map<*, *>): String {
        return when (config["delimiter"]?.toString()?.lowercase() ?: "comma") {
            "comma" -> ","
            "tab" -> "\t"
            "pipe" -> "|"
            "other" -> config["customDelimiter"]?.toString() ?: ","
            else -> ","
        }
    }

    private fun parseConsistency(): ConsistencyLevel {
        return try {
            DefaultConsistencyLevel.valueOf(consistencyLevel.uppercase())
        } catch (_: Exception) {
            DefaultConsistencyLevel.LOCAL_QUORUM
        }
    }

    private fun checkKilled() {
        if (killed) throw Exception("Operation cancelled")
    }

    private fun postMessage(msg: String) {
        messageCallback?.invoke(msg)
    }
}
