package com.blueisle.intouch.tools.ldap

import com.blueisle.intouch.tool.IToolConnector
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Hashtable
import javax.naming.Context
import javax.naming.NamingEnumeration
import javax.naming.directory.*
import javax.naming.ldap.InitialLdapContext

/**
 * InTouch Tool: LDAP
 *
 * LDAP/Active Directory operations:
 * - Search: find entries with filter, base DN, scope, attributes
 * - Get: retrieve a specific entry by DN
 * - Modify: update attributes on an entry
 * - Count: count entries matching a filter
 * - Test: verify LDAP connectivity and binding
 *
 * Uses javax.naming (JNDI) — built into the JDK. Supports LDAP and LDAPS.
 */
class LdapTool : IToolConnector {

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private var debug = false
    private var ctx: InitialLdapContext? = null
    override var messageCallback: ((String) -> Unit)? = null

    private var ldapUrl = ""
    private var bindDn = ""
    private var bindPassword = ""
    private var baseDn = ""

    companion object {
        const val SUCCESS = 0
        const val WARNING = 1
        const val FAILED = 2
    }

    override fun getName() = "ldap"
    override fun getDisplayName() = "LDAP"
    override fun getProvider() = "Blue Isle"
    override fun getVersion() = "1.0.0"
    override fun getPublishedOutputs(): List<String> = listOf("count", "entries", "entry", "entryCount", "modificationCount", "modifiedDn", "operation")
    override fun getModelNames() = listOf("search", "get", "modify", "count")

    override fun getModel(name: String): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "operation": { "type": "string", "enum": ["search", "get", "modify", "count"] },
                    "baseDn": { "type": "string", "description": "Base DN to search from (overrides connection default)" },
                    "filter": { "type": "string", "description": "LDAP search filter (e.g. (objectClass=person))", "default": "(objectClass=*)" },
                    "dn": { "type": "string", "description": "Distinguished name of entry (for get/modify)" },
                    "attributes": { "type": "string", "description": "Comma-separated attribute names to return", "default": "" },
                    "scope": { "type": "string", "enum": ["subtree", "onelevel", "base"], "default": "subtree" },
                    "maxResults": { "type": "integer", "description": "Maximum entries to return", "default": 100 },
                    "modifications": { "type": "array", "description": "Attribute modifications [{attribute, value, operation}]" }
                },
                "required": ["operation"]
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "Control", "scope": "#/properties/operation" },
                    { "type": "Control", "scope": "#/properties/baseDn" },
                    { "type": "Control", "scope": "#/properties/filter", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["search", "count"] } } } },
                    { "type": "Control", "scope": "#/properties/dn", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["get", "modify"] } } } },
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/scope" },
                        { "type": "Control", "scope": "#/properties/maxResults" }
                    ], "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "enum": ["search", "count"] } } } },
                    { "type": "Control", "scope": "#/properties/attributes" },
                    { "type": "Control", "scope": "#/properties/modifications", "rule": { "effect": "SHOW", "condition": { "scope": "#/properties/operation", "schema": { "const": "modify" } } } }
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultJson(name: String): String = mapper.writeValueAsString(mapOf(
        "operation" to "search", "baseDn" to "", "filter" to "(objectClass=*)",
        "dn" to "", "attributes" to "", "scope" to "subtree", "maxResults" to 100
    ))

    override fun isCredentialBased() = true

    override fun getCredentialModel(): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "ldapUrl": { "type": "string", "description": "LDAP URL (e.g. ldap://server:389 or ldaps://server:636)" },
                    "bindDn": { "type": "string", "description": "Bind DN (e.g. cn=admin,dc=company,dc=com)" },
                    "bindPassword": { "type": "string", "description": "Bind password", "secret": true },
                    "baseDn": { "type": "string", "description": "Default base DN for searches" }
                },
                "required": ["ldapUrl", "bindDn", "bindPassword"]
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "Control", "scope": "#/properties/ldapUrl" },
                    { "type": "Control", "scope": "#/properties/bindDn" },
                    { "type": "Control", "scope": "#/properties/baseDn" }
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultCredentialJson(): String = mapper.writeValueAsString(mapOf(
        "ldapUrl" to "ldap://localhost:389", "bindDn" to "", "bindPassword" to "", "baseDn" to ""
    ))

    override fun testCredential(json: String): String {
        return try {
            parseCredentialJson(json)
            connect()
            disconnect()
            ""
        } catch (ex: Exception) { disconnect(); ex.message ?: "Connection failed" }
    }

    override fun setCredentialProperties(json: String) { parseCredentialJson(json) }

    private fun parseCredentialJson(json: String) {
        val p = mapper.readValue(json, Map::class.java)
        ldapUrl = p["ldapUrl"]?.toString() ?: ""
        bindDn = p["bindDn"]?.toString() ?: ""
        bindPassword = p["bindPassword"]?.toString() ?: ""
        baseDn = p["baseDn"]?.toString() ?: ""
    }

    @Suppress("UNCHECKED_CAST")
    override fun execute(taskJson: String): Triple<Int, String, String> {
        val config = mapper.readValue(taskJson, Map::class.java)
        val operation = config["operation"]?.toString()?.lowercase() ?: "search"
        val searchBaseDn = config["baseDn"]?.toString()?.ifBlank { baseDn } ?: baseDn
        val filter = config["filter"]?.toString() ?: "(objectClass=*)"
        val dn = config["dn"]?.toString()?.trim() ?: ""
        val attrsStr = config["attributes"]?.toString()?.trim() ?: ""
        val scope = config["scope"]?.toString()?.lowercase() ?: "subtree"
        val maxResults = (config["maxResults"] as? Number)?.toInt() ?: 100

        val published = mutableMapOf<String, String>()
        published["operation"] = operation

        try {
            connect()

            when (operation) {
                "search" -> {
                    val controls = SearchControls()
                    controls.searchScope = when (scope) {
                        "subtree" -> SearchControls.SUBTREE_SCOPE
                        "onelevel" -> SearchControls.ONELEVEL_SCOPE
                        "base" -> SearchControls.OBJECT_SCOPE
                        else -> SearchControls.SUBTREE_SCOPE
                    }
                    controls.countLimit = maxResults.toLong()
                    if (attrsStr.isNotBlank()) controls.returningAttributes = attrsStr.split(",").map { it.trim() }.toTypedArray()

                    postMessage("Searching: base=$searchBaseDn, filter=$filter, scope=$scope")
                    val results = ctx!!.search(searchBaseDn, filter, controls)
                    val entries = mutableListOf<Map<String, Any>>()
                    while (results.hasMore()) {
                        val entry = results.next()
                        val attrs = mutableMapOf<String, Any>("dn" to entry.nameInNamespace)
                        val entryAttrs = entry.attributes
                        val attrEnum = entryAttrs.all
                        while (attrEnum.hasMore()) {
                            val attr = attrEnum.next()
                            attrs[attr.id] = if (attr.size() == 1) attr.get().toString() else (0 until attr.size()).map { attr.get(it).toString() }
                        }
                        entries.add(attrs)
                    }
                    published["entryCount"] = entries.size.toString()
                    published["entries"] = mapper.writeValueAsString(entries)
                    postMessage("Found ${entries.size} entries")
                }
                "get" -> {
                    if (dn.isBlank()) { disconnect(); return Triple(FAILED, "DN required for get", "{}") }
                    val attrs = ctx!!.getAttributes(dn)
                    val entry = mutableMapOf<String, Any>("dn" to dn)
                    val attrEnum = attrs.all
                    while (attrEnum.hasMore()) {
                        val attr = attrEnum.next()
                        entry[attr.id] = if (attr.size() == 1) attr.get().toString() else (0 until attr.size()).map { attr.get(it).toString() }
                    }
                    published["entry"] = mapper.writeValueAsString(entry)
                    postMessage("Retrieved entry: $dn")
                }
                "count" -> {
                    val controls = SearchControls()
                    controls.searchScope = SearchControls.SUBTREE_SCOPE
                    controls.countLimit = 0
                    controls.returningAttributes = arrayOf("1.1") // return no attributes, just count
                    val results = ctx!!.search(searchBaseDn, filter, controls)
                    var count = 0
                    while (results.hasMore()) { results.next(); count++ }
                    published["count"] = count.toString()
                    postMessage("Count: $count entries match $filter")
                }
                "modify" -> {
                    if (dn.isBlank()) { disconnect(); return Triple(FAILED, "DN required for modify", "{}") }
                    val mods = config["modifications"] as? List<Map<String, String>> ?: emptyList()
                    if (mods.isEmpty()) { disconnect(); return Triple(FAILED, "Modifications required", "{}") }

                    val modItems = mods.map { mod ->
                        val attrName = mod["attribute"] ?: ""
                        val value = mod["value"] ?: ""
                        val op = when (mod["operation"]?.lowercase()) {
                            "add" -> DirContext.ADD_ATTRIBUTE
                            "remove" -> DirContext.REMOVE_ATTRIBUTE
                            else -> DirContext.REPLACE_ATTRIBUTE
                        }
                        ModificationItem(op, BasicAttribute(attrName, value))
                    }.toTypedArray()

                    ctx!!.modifyAttributes(dn, modItems)
                    published["modifiedDn"] = dn
                    published["modificationCount"] = mods.size.toString()
                    postMessage("Modified $dn (${mods.size} attributes)")
                }
                else -> { disconnect(); return Triple(FAILED, "Unknown operation: $operation", "{}") }
            }

            disconnect()
            return Triple(SUCCESS, "$operation completed", mapper.writeValueAsString(published))
        } catch (ex: Exception) {
            disconnect()
            return Triple(FAILED, "$operation failed: ${ex.message}", mapper.writeValueAsString(published))
        }
    }

    private fun connect() {
        val env = Hashtable<String, String>()
        env[Context.INITIAL_CONTEXT_FACTORY] = "com.sun.jndi.ldap.LdapCtxFactory"
        env[Context.PROVIDER_URL] = ldapUrl
        env[Context.SECURITY_AUTHENTICATION] = "simple"
        env[Context.SECURITY_PRINCIPAL] = bindDn
        env[Context.SECURITY_CREDENTIALS] = bindPassword
        if (ldapUrl.startsWith("ldaps")) env["java.naming.ldap.factory.socket"] = "javax.net.ssl.SSLSocketFactory"
        ctx = InitialLdapContext(env, null)
    }

    private fun disconnect() { try { ctx?.close() } catch (_: Exception) {}; ctx = null }

    override fun kill() { disconnect() }
    override fun isKillable() = true
    override fun setDebug(enabled: Boolean) { debug = enabled }
    private fun postMessage(msg: String) { messageCallback?.invoke(msg) }
}
