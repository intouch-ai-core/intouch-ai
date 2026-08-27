package com.blueisle.intouch.tool

/**
 * InTouch Tool plugin interface.
 *
 * Implement this interface to create an InTouch Tool — a custom task type
 * that can be installed into InTouch Server as an external plugin.
 *
 * Tools are loaded at runtime via URLClassLoader. All data exchange uses
 * plain JSON strings to ensure zero compile-time coupling with InTouch internals.
 *
 * The only dependency a tool needs is this intouch-tool-api JAR.
 */
interface IToolConnector {

    // ── Metadata ──────────────────────────────────────────────

    /** Unique tool identifier (lowercase, no spaces). */
    fun getName(): String

    /** Human-readable display name shown in the UI. */
    fun getDisplayName(): String

    /** Provider/author name. */
    fun getProvider(): String

    /** Semantic version (e.g., "1.0.0"). */
    fun getVersion(): String

    // ── Published Outputs ─────────────────────────────────────

    /**
     * The output keys this tool publishes — i.e. the keys it places in the `outputJson` returned
     * from [execute] (or emitted via [publishCallback]). A downstream task references them as
     * `{{<task-name>.<key>}}`.
     *
     * REQUIRED. Declaring outputs lets the InTouch assistant wire task chains to real output names
     * instead of guessing `{{task.output}}` (which fails at runtime). A plugin that does not
     * implement this method is rejected at load time. List every key the tool may publish; omit
     * purely dynamic/per-row keys it cannot know in advance.
     */
    fun getPublishedOutputs(): List<String>

    // ── Task Models ───────────────────────────────────────────

    /** List of operation/sub-type names this tool supports. */
    fun getModelNames(): List<String>

    /** Get the JSONForms UI schema for a given operation. */
    fun getModel(name: String): String

    /** Get default task configuration JSON for a given operation. */
    fun getDefaultJson(name: String): String

    // ── Credential ────────────────────────────────────────────

    /** Whether this tool requires a credential. */
    fun isCredentialBased(): Boolean

    /**
     * FAMILY KEY — the credential type this tool binds to. Default: its own [getName].
     *
     * Override to join a family: several tools that authenticate against ONE app registration
     * and should therefore share ONE credential. Every `msgraph-*` connector returns "msgraph",
     * so a tenant is configured once instead of eight times and a rotated client secret is
     * edited once. Microsoft Graph cannot ship as a single tool — Entra grants admin consent
     * per scope, and a monolith would have to request the union of every scope it might ever
     * use — so the family is not a convenience, it is the only shippable shape.
     *
     * The key is also the LICENSING unit: a paid family is licensed once, against this name,
     * not once per member.
     *
     * A tool cannot help itself to another tool's credentials by naming them here. The named
     * tool must exist, be credential-based, and list this one in its connector.json
     * `credentialFamilyMembers` — owner opt-in, checked at load. A tool that names a family it
     * was not invited to is refused with an error in the log and falls back to its own type;
     * it still loads, because silently dropping a tool is its own bug.
     *
     * The default keeps every existing plugin on its own credential, unchanged.
     */
    fun getCredentialTypeName(): String = getName()

    /**
     * Tools permitted to share THIS tool's credential — the other half of owner opt-in.
     *
     * Returning a name here is an explicit grant: that tool may bind this tool's credential
     * type and will be handed its secrets via [setCredentialProperties]. Grant only to tools
     * you publish, authenticating against the same account.
     *
     * Default: empty — no tool shares this one's credential.
     */
    fun getCredentialFamilyMembers(): List<String> = emptyList()

    /** Get the JSONForms UI schema for the credential configuration. */
    fun getCredentialModel(): String

    /** Get default credential configuration JSON. */
    fun getDefaultCredentialJson(): String

    /** Test a credential. Returns empty string on success, error message on failure. */
    fun testCredential(json: String): String

    /** Set credential properties before execution. */
    fun setCredentialProperties(json: String)

    // ── Execution ─────────────────────────────────────────────

    /**
     * Execute the tool with the given task configuration JSON.
     *
     * @param taskJson Task configuration as JSON string
     * @return Triple of (statusCode, message, outputJson)
     *         statusCode: SUCCESS=0, WARNING=1, FAILED=2
     *         message: Human-readable result message
     *         outputJson: JSON string of published properties (key-value pairs)
     */
    fun execute(taskJson: String): Triple<Int, String, String>

    // ── Lifecycle ─────────────────────────────────────────────

    /** Request cancellation of a running execution. */
    fun kill()

    /** Whether this tool supports cancellation. */
    fun isKillable(): Boolean

    /** Enable or disable debug logging. */
    fun setDebug(enabled: Boolean)

    /**
     * Callback for posting activity messages during execution.
     * Set by the host before execute() is called.
     */
    var messageCallback: ((String) -> Unit)?

    /**
     * Callback for publishing properties incrementally during execution.
     *
     * Tools that want progressive publishing (e.g. updating `rowCount` as a long
     * import progresses) can invoke this whenever a new value is ready. Tools
     * that only need atomic publishing can ignore this and continue to return
     * the full property map as the third element of [execute]'s `Triple`.
     *
     * Both mechanisms work together. Values returned from [execute] are applied
     * after the incremental callbacks have fired, so a final value with the same
     * key wins.
     *
     * Default: a no-op — existing plugins that don't override this lose nothing
     * and keep their atomic-on-return behavior.
     */
    var publishCallback: ((String, String) -> Unit)?
        get() = null
        set(_) {}

    // ── Licensing ─────────────────────────────────────────────

    /**
     * Whether this tool requires a license to operate.
     * Tools that return true will be blocked from execution on Department
     * and Enterprise editions unless a valid license has been applied.
     *
     * Default: false (no license required).
     */
    fun licenseRequired(): Boolean = false

    /**
     * Apply a license string provided by the tool provider.
     *
     * InTouch does not interpret the license — the tool is responsible for
     * validating, storing, and enforcing its own license format.
     *
     * @param license The license string provided by the administrator
     * @return Pair of (statusCode, message)
     *         statusCode: 0 = license accepted, non-zero = rejected
     *         message: Human-readable result (e.g., "Licensed to ACME Corp, expires 2027-12-31")
     */
    fun applyLicense(license: String): Pair<Int, String> = 0 to "No license required"

    /**
     * Whether the tool's license has expired.
     *
     * Called before each execution on Department and Enterprise editions
     * (only when [licenseRequired] returns true). If this returns true,
     * execution is blocked.
     *
     * Default: false (not expired / no license needed).
     */
    fun licenseExpired(): Boolean = false
}
