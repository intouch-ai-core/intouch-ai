// One Gradle build for the whole repo, so a fresh clone compiles with no artifact hosting
// and no published Maven coordinate: the examples depend on :tool-api by project reference.
rootProject.name = "intouch-ai"

include("tool-api")

listOf("cassandra", "clickhouse", "excel", "git", "ldap", "mongodb").forEach { name ->
    include(":examples:connectors:$name")
    project(":examples:connectors:$name").projectDir = file("examples/connectors/$name")
}
