package com.blueisle.intouch.tools.mongodb

import com.blueisle.intouch.tool.IToolConnector
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.bson.Document
import java.io.File

/**
 * InTouch Tool: MongoDB
 *
 * MongoDB NoSQL database operations:
 * - Find: query documents with filter, projection, sort, limit
 * - Insert: insert one or many documents
 * - Update: update documents matching a filter
 * - Delete: delete documents matching a filter
 * - Count: count documents matching a filter
 * - Aggregate: run aggregation pipelines
 * - Export: query results to JSON file
 *
 * Uses the official MongoDB Java Driver.
 */
class MongoDbTool : IToolConnector {

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private var debug = false
    private var mongoClient: MongoClient? = null
    override var messageCallback: ((String) -> Unit)? = null

    private var connectionString = ""
    private var defaultDatabase = ""

    companion object {
        const val SUCCESS = 0
        const val WARNING = 1
        const val FAILED = 2
    }

    override fun getName() = "mongodb"
    override fun getDisplayName() = "MongoDB"
    override fun getProvider() = "Blue Isle"
    override fun getVersion() = "1.0.0"
    override fun getPublishedOutputs(): List<String> = listOf("count", "deletedCount", "documentCount", "documents", "insertedCount", "matchedCount", "modifiedCount", "operation", "outputFile")
    override fun getModelNames() = listOf("find", "insert", "update", "delete", "count", "aggregate", "export")

    override fun getModel(name: String): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "operation": { "type": "string", "enum": ["find", "insert", "update", "delete", "count", "aggregate", "export"] },
                    "database": { "type": "string", "description": "Database name (overrides connection default)" },
                    "collection": { "type": "string", "description": "Collection name" },
                    "filter": { "type": "string", "description": "Query filter as JSON (e.g. {\"status\": \"active\"})", "default": "{}" },
                    "document": { "type": "string", "description": "Document(s) to insert as JSON or JSON array" },
                    "update": { "type": "string", "description": "Update operations as JSON (e.g. {\"${'$'}set\": {\"status\": \"done\"}})" },
                    "projection": { "type": "string", "description": "Fields to include/exclude as JSON" },
                    "sort": { "type": "string", "description": "Sort order as JSON (e.g. {\"date\": -1})" },
                    "limit": { "type": "integer", "description": "Maximum documents to return", "default": 0 },
                    "pipeline": { "type": "string", "description": "Aggregation pipeline as JSON array" },
                    "outputFile": { "type": "string", "format": "filepath", "description": "Output file path for export" }
                },
                "required": ["operation", "collection"]
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "Control", "scope": "#/properties/operation" },
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/database" },
                        { "type": "Control", "scope": "#/properties/collection" }
                    ]},
                    { "type": "Control", "scope": "#/properties/filter", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["find", "update", "delete", "count", "export"] } } } },
                    { "type": "Control", "scope": "#/properties/document", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "insert" } } } },
                    { "type": "Control", "scope": "#/properties/update", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "update" } } } },
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/projection" },
                        { "type": "Control", "scope": "#/properties/sort" },
                        { "type": "Control", "scope": "#/properties/limit" }
                    ], "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["find", "export"] } } } },
                    { "type": "Control", "scope": "#/properties/pipeline", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "aggregate" } } } },
                    { "type": "Control", "scope": "#/properties/outputFile", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "export" } } } }
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultJson(name: String): String = mapper.writeValueAsString(mapOf(
        "operation" to "find", "database" to "", "collection" to "", "filter" to "{}",
        "document" to "", "update" to "", "projection" to "", "sort" to "",
        "limit" to 0, "pipeline" to "", "outputFile" to ""
    ))

    override fun isCredentialBased() = true

    override fun getCredentialModel(): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "connectionString": { "type": "string", "description": "MongoDB connection string (e.g. mongodb://user:pass@host:27017)" },
                    "database": { "type": "string", "description": "Default database name" }
                },
                "required": ["connectionString"]
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "Control", "scope": "#/properties/connectionString" },
                    { "type": "Control", "scope": "#/properties/database" }
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultCredentialJson(): String = mapper.writeValueAsString(mapOf(
        "connectionString" to "mongodb://localhost:27017", "database" to ""
    ))

    override fun testCredential(json: String): String {
        return try {
            parseCredentialJson(json)
            val client = MongoClients.create(connectionString)
            val db = client.getDatabase(defaultDatabase.ifBlank { "admin" })
            db.runCommand(Document("ping", 1))
            client.close()
            ""
        } catch (ex: Exception) { ex.message ?: "Connection failed" }
    }

    override fun setCredentialProperties(json: String) { parseCredentialJson(json) }

    private fun parseCredentialJson(json: String) {
        val p = mapper.readValue(json, Map::class.java)
        connectionString = p["connectionString"]?.toString() ?: "mongodb://localhost:27017"
        defaultDatabase = p["database"]?.toString() ?: ""
    }

    override fun execute(taskJson: String): Triple<Int, String, String> {
        val config = mapper.readValue(taskJson, Map::class.java)
        val operation = config["operation"]?.toString()?.lowercase() ?: "find"
        val dbName = config["database"]?.toString()?.ifBlank { defaultDatabase } ?: defaultDatabase
        val collName = config["collection"]?.toString()?.trim() ?: ""
        val filterJson = config["filter"]?.toString() ?: "{}"
        val documentJson = config["document"]?.toString() ?: ""
        val updateJson = config["update"]?.toString() ?: ""
        val projectionJson = config["projection"]?.toString() ?: ""
        val sortJson = config["sort"]?.toString() ?: ""
        val limit = (config["limit"] as? Number)?.toInt() ?: 0
        val pipelineJson = config["pipeline"]?.toString() ?: ""
        val outputFile = config["outputFile"]?.toString()?.trim() ?: ""

        if (collName.isBlank()) return Triple(FAILED, "Collection name is required", "{}")
        if (dbName.isBlank()) return Triple(FAILED, "Database name is required", "{}")

        val published = mutableMapOf<String, String>()
        published["operation"] = operation

        try {
            mongoClient = MongoClients.create(connectionString)
            val db = mongoClient!!.getDatabase(dbName)
            val coll = db.getCollection(collName)

            when (operation) {
                "find" -> {
                    val filter = Document.parse(filterJson)
                    var cursor = coll.find(filter)
                    if (projectionJson.isNotBlank()) cursor = cursor.projection(Document.parse(projectionJson))
                    if (sortJson.isNotBlank()) cursor = cursor.sort(Document.parse(sortJson))
                    if (limit > 0) cursor = cursor.limit(limit)
                    val docs = cursor.map { it.toJson() }.toList()
                    published["documentCount"] = docs.size.toString()
                    published["documents"] = "[${docs.joinToString(",")}]"
                    postMessage("Found ${docs.size} document(s)")
                }
                "insert" -> {
                    if (documentJson.isBlank()) return fail("Document required for insert", published)
                    val trimmed = documentJson.trim()
                    if (trimmed.startsWith("[")) {
                        val docs = mapper.readValue(trimmed, List::class.java).map { Document.parse(mapper.writeValueAsString(it)) }
                        coll.insertMany(docs)
                        published["insertedCount"] = docs.size.toString()
                        postMessage("Inserted ${docs.size} document(s)")
                    } else {
                        coll.insertOne(Document.parse(trimmed))
                        published["insertedCount"] = "1"
                        postMessage("Inserted 1 document")
                    }
                }
                "update" -> {
                    if (updateJson.isBlank()) return fail("Update expression required", published)
                    val filter = Document.parse(filterJson)
                    val update = Document.parse(updateJson)
                    val result = coll.updateMany(filter, update)
                    published["matchedCount"] = result.matchedCount.toString()
                    published["modifiedCount"] = result.modifiedCount.toString()
                    postMessage("Matched ${result.matchedCount}, modified ${result.modifiedCount}")
                }
                "delete" -> {
                    val filter = Document.parse(filterJson)
                    val result = coll.deleteMany(filter)
                    published["deletedCount"] = result.deletedCount.toString()
                    postMessage("Deleted ${result.deletedCount} document(s)")
                }
                "count" -> {
                    val filter = Document.parse(filterJson)
                    val count = coll.countDocuments(filter)
                    published["count"] = count.toString()
                    postMessage("Count: $count")
                }
                "aggregate" -> {
                    if (pipelineJson.isBlank()) return fail("Pipeline required for aggregate", published)
                    val stages = mapper.readValue(pipelineJson, List::class.java).map { Document.parse(mapper.writeValueAsString(it)) }
                    val docs = coll.aggregate(stages).map { it.toJson() }.toList()
                    published["documentCount"] = docs.size.toString()
                    published["documents"] = "[${docs.joinToString(",")}]"
                    postMessage("Aggregation returned ${docs.size} document(s)")
                }
                "export" -> {
                    if (outputFile.isBlank()) return fail("Output file required for export", published)
                    val filter = Document.parse(filterJson)
                    var cursor = coll.find(filter)
                    if (limit > 0) cursor = cursor.limit(limit)
                    File(outputFile).parentFile?.mkdirs()
                    val docs = cursor.map { it.toJson() }.toList()
                    File(outputFile).writeText("[${docs.joinToString(",\n")}]")
                    published["documentCount"] = docs.size.toString()
                    published["outputFile"] = outputFile
                    postMessage("Exported ${docs.size} document(s) to $outputFile")
                }
                else -> return fail("Unknown operation: $operation", published)
            }

            mongoClient?.close()
            return Triple(SUCCESS, "$operation completed", mapper.writeValueAsString(published))
        } catch (ex: Exception) {
            mongoClient?.close()
            return Triple(FAILED, "$operation failed: ${ex.message}", mapper.writeValueAsString(published))
        }
    }

    private fun fail(msg: String, p: Map<String, String>): Triple<Int, String, String> {
        mongoClient?.close()
        return Triple(FAILED, msg, mapper.writeValueAsString(p))
    }

    override fun kill() { mongoClient?.close() }
    override fun isKillable() = true
    override fun setDebug(enabled: Boolean) { debug = enabled }
    private fun postMessage(msg: String) { messageCallback?.invoke(msg) }
}
