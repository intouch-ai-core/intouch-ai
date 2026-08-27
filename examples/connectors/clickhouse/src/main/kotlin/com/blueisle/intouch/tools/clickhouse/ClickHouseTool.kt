package com.blueisle.intouch.tools.clickhouse

import com.blueisle.intouch.tool.IToolConnector
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64

/**
 * Apache ClickHouse connector.
 *
 * Speaks ClickHouse's HTTP interface (8123, or 8443 with TLS) rather than JDBC: the HTTP interface
 * is first-class and complete — queries, inserts, and native format handling — so the connector
 * needs no vendor driver and the plugin JAR stays a few MB instead of tens.
 *
 * Bulk paths deliberately let ClickHouse do the formatting rather than building delimited text here:
 *
 *  - export streams a `FORMAT CSVWithNames` / `TSVWithNames` / `CustomSeparated` response straight
 *    to disk, so a billion-row result never has to fit in memory, and quoting is ClickHouse's own.
 *  - import POSTs the file as the body of `INSERT ... FORMAT CSVWithNames`, so ClickHouse parses it
 *    server-side and, with input_format_with_names_use_header, matches columns BY NAME. A file whose
 *    column order differs from the table's still lands in the right columns.
 *
 * Only `execute` materializes rows, and it is capped — see DEFAULT_PUBLISHED_ROWS.
 */
class ClickHouseTool : IToolConnector {

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private var debug = false
    @Volatile private var killed = false
    override var messageCallback: ((String) -> Unit)? = null
    override var publishCallback: ((String, String) -> Unit)? = null

    // Credential properties
    private var server = ""
    private var port = 8123
    private var login = "default"
    private var password = ""
    private var database = "default"
    private var ssl = false

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    companion object {
        const val SUCCESS = 0
        const val WARNING = 1
        const val FAILED = 2

        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val REQUEST_TIMEOUT_SECONDS = 300L

        /** Rows `execute` publishes when maxRows isn't set. Bounded because published outputs are
         *  held in memory and written to the activity log; export handles bulk extraction. */
        const val DEFAULT_PUBLISHED_ROWS = 1000
    }

    // ── Metadata ──────────────────────────────────────────────

    override fun getName() = "clickhouse"
    override fun getDisplayName() = "ClickHouse"
    override fun getProvider() = "Blue Isle"
    override fun getVersion() = "1.0.0"

    override fun getPublishedOutputs(): List<String> = listOf(
        "columns", "count", "database", "databases", "operation", "outputFile", "inputFile",
        "rowCount", "rows", "rowsPublished", "table", "tables", "truncated"
    )

    override fun getModelNames() = listOf("execute", "export", "import", "list-databases", "list-tables", "describe")

    override fun getModel(name: String): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "operation": { "type": "string", "enum": ["execute", "export", "import", "list-databases", "list-tables", "describe"], "description": "ClickHouse operation to perform" },
                    "statement": { "type": "string", "description": "SQL statement to run (for execute/export operations)" },
                    "database": { "type": "string", "description": "Database name (defaults to the credential's database)" },
                    "table": { "type": "string", "description": "Table name (for describe, import)" },
                    "outputFile": { "type": "string", "format": "filepath", "description": "Output file path for export results" },
                    "inputFile": { "type": "string", "format": "filepath", "description": "Input file path for import" },
                    "delimiter": { "type": "string", "enum": ["comma", "tab", "pipe", "other"], "description": "Field delimiter", "default": "comma" },
                    "customDelimiter": { "type": "string", "description": "Custom delimiter character (when delimiter is 'other')" },
                    "hasHeader": { "type": "boolean", "description": "File has/should have a header row", "default": true },
                    "maxRows": { "type": "integer", "description": "Row limit: rows published by execute, or written by export (0 = default limit for execute, all for export)", "default": 0 },
                    "nullFormat": { "type": "string", "description": "String representation of null values", "default": "" },
                    "settings": { "type": "string", "description": "Extra ClickHouse settings as JSON, e.g. {\"max_execution_time\":\"60\"}" }
                },
                "required": ["operation"]
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "Control", "scope": "#/properties/operation" },
                    { "type": "Control", "scope": "#/properties/statement", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["execute", "export"] } } } },
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/database" },
                        { "type": "Control", "scope": "#/properties/table" }
                    ], "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["list-tables", "describe", "import"] } } } },
                    { "type": "Control", "scope": "#/properties/outputFile", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "export" } } } },
                    { "type": "Control", "scope": "#/properties/inputFile", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "import" } } } },
                    { "type": "Control", "scope": "#/properties/maxRows", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["execute", "export"] } } } },
                    { "type": "Group", "label": "Format", "elements": [
                        { "type": "HorizontalLayout", "elements": [
                            { "type": "Control", "scope": "#/properties/delimiter" },
                            { "type": "Control", "scope": "#/properties/customDelimiter", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/delimiter", "schema": { "const": "other" } } } }
                        ]},
                        { "type": "HorizontalLayout", "elements": [
                            { "type": "Control", "scope": "#/properties/hasHeader" },
                            { "type": "Control", "scope": "#/properties/nullFormat" }
                        ]}
                    ], "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["export", "import"] } } } },
                    { "type": "Control", "scope": "#/properties/settings" }
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultJson(name: String): String = mapper.writeValueAsString(
        mapOf("operation" to name, "delimiter" to "comma", "hasHeader" to true)
    )

    // ── Credential ────────────────────────────────────────────

    override fun isCredentialBased() = true

    override fun getCredentialModel(): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "database": { "type": "string", "description": "Default database for statements that don't name one", "default": "default" },
                    "ssl": { "type": "boolean", "description": "Connect over HTTPS (ClickHouse Cloud requires this)", "default": false }
                }
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "Control", "scope": "#/properties/database" },
                    { "type": "Control", "scope": "#/properties/ssl" }
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultCredentialJson(): String = mapper.writeValueAsString(
        mapOf("database" to "default", "ssl" to false)
    )

    /**
     * Runs a real query rather than only opening a socket. A reachable HTTP port proves nothing
     * about whether the credentials authenticate or the database exists, and a green test that
     * doesn't exercise the same path as the operations is worse than no test at all.
     */
    override fun testCredential(json: String): String {
        return try {
            parseCredentialJson(json)
            val version = query("SELECT version()", "TabSeparated").trim()
            postMessage("Connected: ClickHouse $version (${hostLabel()}, database=$database)")
            ""
        } catch (ex: Exception) {
            ex.message ?: "Connection failed"
        }
    }

    override fun setCredentialProperties(json: String) = parseCredentialJson(json)

    private fun parseCredentialJson(json: String) {
        val props = mapper.readValue(json, Map::class.java)
        server = props["server"]?.toString()?.trim().orEmpty()
        if (server.isBlank()) throw Exception("Server is required — the ClickHouse host")
        ssl = props["ssl"] as? Boolean ?: false
        // Port 0 means "not set" once the core credential fields are merged in, so fall back to
        // ClickHouse's documented default for the chosen scheme.
        port = (props["port"] as? Number)?.toInt()?.takeIf { it > 0 } ?: if (ssl) 8443 else 8123
        login = props["login"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "default"
        password = props["password"]?.toString() ?: ""
        database = props["database"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "default"
    }

    // ── Execution ─────────────────────────────────────────────

    override fun execute(taskJson: String): Triple<Int, String, String> {
        val config = mapper.readValue(taskJson, Map::class.java)
        val operation = config["operation"]?.toString()?.lowercase() ?: "execute"

        val published = mutableMapOf<String, String>()
        published["operation"] = operation

        return try {
            postMessage("Connecting to ClickHouse (${hostLabel()})...")
            when (operation) {
                "execute" -> doExecute(config, published)
                "export" -> doExport(config, published)
                "import" -> doImport(config, published)
                "list-databases" -> doListDatabases(published)
                "list-tables" -> doListTables(config, published)
                "describe" -> doDescribe(config, published)
                else -> return Triple(FAILED, "Unknown operation: $operation", mapper.writeValueAsString(published))
            }
            Triple(SUCCESS, "$operation completed successfully", mapper.writeValueAsString(published))
        } catch (ex: Exception) {
            Triple(FAILED, "$operation failed: ${ex.message}", mapper.writeValueAsString(published))
        }
    }

    private fun doExecute(config: Map<*, *>, published: MutableMap<String, String>) {
        val statement = config["statement"]?.toString()?.trim().orEmpty()
        if (statement.isBlank()) throw Exception("SQL statement is required")

        postMessage("Executing SQL...")
        // FORMAT JSON returns column metadata alongside the data, so a SELECT yields named rows
        // while a DDL/DML statement comes back with an empty body and nothing to parse.
        val body = query(statement, "JSON", config)
        if (body.isBlank()) {
            published["rowCount"] = "0"
            postMessage("Statement executed")
            return
        }

        val root: JsonNode = mapper.readTree(body)
        val data = root.path("data")
        if (!data.isArray) {
            published["rowCount"] = "0"
            postMessage("Statement executed")
            return
        }

        val colNames = root.path("meta").mapNotNull { it.path("name").asText(null) }
        val maxRows = (config["maxRows"] as? Number)?.toInt() ?: 0
        val publishLimit = if (maxRows > 0) maxRows else DEFAULT_PUBLISHED_ROWS

        // rows_before_limit_at_least is ClickHouse's own count and can exceed what it returned;
        // the number of returned rows is what `data` actually holds.
        val returned = data.size()
        val collected = ArrayList<Map<String, String?>>(minOf(returned, publishLimit))
        for (i in 0 until minOf(returned, publishLimit)) {
            checkKilled()
            val row = data[i]
            collected.add(colNames.associateWith { c ->
                row.path(c).let { if (it.isNull || it.isMissingNode) null else it.asText() }
            })
        }

        val truncated = returned > collected.size
        published["rowCount"] = returned.toString()
        published["rowsPublished"] = collected.size.toString()
        published["truncated"] = truncated.toString()
        published["columns"] = colNames.joinToString(",")
        published["rows"] = mapper.writeValueAsString(collected)

        postMessage("Query returned $returned row(s)")
        if (truncated) {
            postMessage(
                "Published the first ${collected.size} row(s) of $returned — set maxRows to raise " +
                    "the limit, or use the export operation to write them all to a file"
            )
        }
    }

    /**
     * Streams the response body straight to disk. ClickHouse does the formatting, so nothing is
     * buffered in memory and the quoting is the server's own — a text value containing the
     * delimiter, a quote or a newline is escaped correctly without this connector touching it.
     */
    private fun doExport(config: Map<*, *>, published: MutableMap<String, String>) {
        val statement = config["statement"]?.toString()?.trim().orEmpty()
        val outputFile = config["outputFile"]?.toString()?.trim().orEmpty()
        if (statement.isBlank()) throw Exception("SQL statement is required")
        if (outputFile.isBlank()) throw Exception("Output file is required")

        val maxRows = (config["maxRows"] as? Number)?.toInt() ?: 0
        val limited = if (maxRows > 0) "$statement LIMIT $maxRows" else statement

        postMessage("Executing query for export...")
        File(outputFile).parentFile?.mkdirs()
        val written = queryToFile(limited, outputFormat(config), Paths.get(outputFile), config)

        published["rowCount"] = written.toString()
        published["outputFile"] = outputFile
        postMessage("Exported $written row(s) to $outputFile")
    }

    /**
     * POSTs the file as the body of an INSERT. ClickHouse parses it server-side, and the WithNames
     * formats plus input_format_with_names_use_header match the file's columns to the table BY NAME
     * — so a file whose column order differs from the table's still lands in the right columns.
     */
    private fun doImport(config: Map<*, *>, published: MutableMap<String, String>) {
        val inputFile = config["inputFile"]?.toString()?.trim().orEmpty()
        val table = config["table"]?.toString()?.trim().orEmpty()
        val db = config["database"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: database
        if (inputFile.isBlank()) throw Exception("Input file is required")
        if (table.isBlank()) throw Exception("Table is required")

        val file = File(inputFile)
        if (!file.exists()) throw Exception("Input file not found: $inputFile")

        val hasHeader = config["hasHeader"] as? Boolean ?: true
        if (!hasHeader) {
            postMessage(
                "No header row — values are taken in the table's own column order. A header lets " +
                    "ClickHouse match columns by name instead."
            )
        }

        val before = countRows(db, table)
        postMessage("Importing $inputFile into $db.$table...")

        val sql = "INSERT INTO ${quoteIdent(db)}.${quoteIdent(table)} FORMAT ${inputFormat(config)}"
        val settings = LinkedHashMap<String, String>()
        if (hasHeader) settings["input_format_with_names_use_header"] = "1"
        applyFormatSettings(settings, config)
        postBody(sql, HttpRequest.BodyPublishers.ofFile(file.toPath()), settings, config)

        val after = countRows(db, table)
        val imported = (after - before).coerceAtLeast(0)
        published["rowCount"] = imported.toString()
        published["inputFile"] = inputFile
        published["table"] = table
        published["database"] = db
        postMessage("Imported $imported row(s) from $inputFile")
    }

    private fun doListDatabases(published: MutableMap<String, String>) {
        val names = queryLines("SHOW DATABASES")
        published["count"] = names.size.toString()
        published["databases"] = names.joinToString(",")
        postMessage("Databases (${names.size}):")
        names.forEach { postMessage("  $it") }
    }

    private fun doListTables(config: Map<*, *>, published: MutableMap<String, String>) {
        val db = config["database"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: database
        val names = queryLines("SHOW TABLES FROM ${quoteIdent(db)}")
        published["count"] = names.size.toString()
        published["database"] = db
        published["tables"] = names.joinToString(",")
        postMessage("Tables in $db (${names.size}):")
        names.forEach { postMessage("  $it") }
    }

    private fun doDescribe(config: Map<*, *>, published: MutableMap<String, String>) {
        val db = config["database"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: database
        val table = config["table"]?.toString()?.trim().orEmpty()
        if (table.isBlank()) throw Exception("Table is required")

        val body = query("DESCRIBE TABLE ${quoteIdent(db)}.${quoteIdent(table)}", "JSON")
        val data = mapper.readTree(body).path("data")
        val names = mutableListOf<String>()
        postMessage("$db.$table:")
        postMessage("  Columns (${data.size()}):")
        for (col in data) {
            val n = col.path("name").asText("")
            val t = col.path("type").asText("")
            names.add(n)
            postMessage("    $n $t")
        }
        published["database"] = db
        published["table"] = table
        published["columnCount"] = data.size().toString()
        published["columns"] = names.joinToString(",")
    }

    // ── HTTP plumbing ─────────────────────────────────────────

    private fun hostLabel() = "${if (ssl) "https" else "http"}://$server:$port"

    private fun baseUri(settings: Map<String, String>): URI {
        val params = StringBuilder("database=").append(enc(database))
        settings.forEach { (k, v) -> params.append('&').append(enc(k)).append('=').append(enc(v)) }
        return URI.create("${hostLabel()}/?$params")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun requestBuilder(uri: URI): HttpRequest.Builder {
        val basic = Base64.getEncoder()
            .encodeToString("$login:$password".toByteArray(StandardCharsets.UTF_8))
        return HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", "Basic $basic")
    }

    /** Extra per-task ClickHouse settings, supplied as a JSON object on the task. */
    private fun taskSettings(config: Map<*, *>?): Map<String, String> {
        val raw = config?.get("settings")?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return emptyMap()
        return try {
            mapper.readValue(raw, Map::class.java).entries
                .associate { (k, v) -> k.toString() to v.toString() }
        } catch (ex: Exception) {
            throw Exception("settings must be a JSON object: ${ex.message}")
        }
    }

    private fun query(sql: String, format: String, config: Map<*, *>? = null): String {
        val settings = LinkedHashMap<String, String>()
        settings.putAll(taskSettings(config))
        val uri = baseUri(settings)
        val statement = if (format.isBlank()) sql else "$sql FORMAT $format"
        val req = requestBuilder(uri)
            .POST(HttpRequest.BodyPublishers.ofString(statement, StandardCharsets.UTF_8))
            .build()
        val res = send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        return res.body()
    }

    private fun queryLines(sql: String): List<String> =
        query(sql, "TabSeparated").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** Streams a formatted result body to [target], returning the number of data rows written. */
    private fun queryToFile(sql: String, format: String, target: java.nio.file.Path, config: Map<*, *>): Long {
        val settings = LinkedHashMap<String, String>()
        applyFormatSettings(settings, config)
        settings.putAll(taskSettings(config))
        val uri = baseUri(settings)
        val req = requestBuilder(uri)
            .POST(HttpRequest.BodyPublishers.ofString("$sql FORMAT $format", StandardCharsets.UTF_8))
            .build()
        val res = send(req, HttpResponse.BodyHandlers.ofFile(target))
        checkKilled()
        // Count data lines rather than trusting a summary header: with a WithNames format the first
        // line is the header, so it must not be counted as a row.
        val hasHeader = config["hasHeader"] as? Boolean ?: true
        val lines = Files.lines(res.body()).use { it.count() }
        return (if (hasHeader) lines - 1 else lines).coerceAtLeast(0)
    }

    private fun postBody(
        sql: String,
        body: HttpRequest.BodyPublisher,
        settings: Map<String, String>,
        config: Map<*, *>,
    ) {
        val all = LinkedHashMap(settings)
        all["query"] = sql
        all.putAll(taskSettings(config))
        val uri = baseUri(all)
        val req = requestBuilder(uri).POST(body).build()
        send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    private fun <T> send(req: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        checkKilled()
        val res = try {
            http.send(req, handler)
        } catch (ex: Exception) {
            throw Exception("${hostLabel()}: ${ex.message}")
        }
        if (res.statusCode() !in 200..299) {
            // ClickHouse puts a readable diagnostic in the body ("Code: 60. DB::Exception: Table
            // x.y does not exist"); surfacing the status alone would throw that away.
            val detail = (res.body() as? String)?.trim()?.take(500).orEmpty()
            throw Exception("HTTP ${res.statusCode()}${if (detail.isNotEmpty()) ": $detail" else ""}")
        }
        return res
    }

    private fun countRows(db: String, table: String): Long =
        query("SELECT count() FROM ${quoteIdent(db)}.${quoteIdent(table)}", "TabSeparated")
            .trim().toLongOrNull() ?: 0L

    // ── Formats ───────────────────────────────────────────────

    private fun outputFormat(config: Map<*, *>) = resolveFormat(config, forInput = false)
    private fun inputFormat(config: Map<*, *>) = resolveFormat(config, forInput = true)

    /**
     * Maps the delimiter choice onto a ClickHouse format. Comma and tab have dedicated formats;
     * anything else uses CustomSeparated, whose delimiter is set through the settings applied by
     * [applyFormatSettings].
     */
    private fun resolveFormat(config: Map<*, *>, forInput: Boolean): String {
        val hasHeader = config["hasHeader"] as? Boolean ?: true
        return when (config["delimiter"]?.toString()?.lowercase() ?: "comma") {
            "comma" -> if (hasHeader) "CSVWithNames" else "CSV"
            "tab" -> if (hasHeader) "TSVWithNames" else "TSV"
            else -> if (hasHeader) "CustomSeparatedWithNames" else "CustomSeparated"
        }.also { if (forInput && debug) postMessage("Input format: $it") }
    }

    private fun applyFormatSettings(settings: MutableMap<String, String>, config: Map<*, *>) {
        val choice = config["delimiter"]?.toString()?.lowercase() ?: "comma"
        val nullFormat = config["nullFormat"]?.toString()
        if (choice != "comma" && choice != "tab") {
            val delim = when (choice) {
                "pipe" -> "|"
                else -> config["customDelimiter"]?.toString()?.takeIf { it.isNotEmpty() }
                    ?: throw Exception("A custom delimiter is required when delimiter is 'other'")
            }
            settings["format_custom_field_delimiter"] = delim
            settings["format_custom_escaping_rule"] = "CSV"
            if (nullFormat != null) settings["format_custom_null_representation"] = nullFormat
        } else if (nullFormat != null) {
            if (choice == "comma") settings["format_csv_null_representation"] = nullFormat
            else settings["format_tsv_null_representation"] = nullFormat
        }
    }

    /** Backtick-quote an identifier so a reserved word or unusual name can't break the statement. */
    private fun quoteIdent(name: String): String = "`" + name.replace("`", "``") + "`"

    // ── Lifecycle ─────────────────────────────────────────────

    override fun kill() { killed = true }
    override fun isKillable() = true
    override fun setDebug(enabled: Boolean) { debug = enabled }

    private fun checkKilled() {
        if (killed) throw Exception("Operation cancelled")
    }

    private fun postMessage(msg: String) { messageCallback?.invoke(msg) }
}
