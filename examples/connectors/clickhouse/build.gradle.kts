plugins {
    kotlin("jvm") version "1.9.10"
}

group = "com.blueisle.intouch.tools"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.0")
    compileOnly(project(":tool-api"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
        javaParameters = true
    }
}

tasks.register<Jar>("toolJar") {
    archiveBaseName.set("clickhouse")
    // Self-contained plugin JAR: bundles the full runtime classpath (Jackson,
    // Kotlin stdlib) so the tool never depends on libraries from the InTouch server. The one
    // deliberate exception is intouch-tool-api (compileOnly) — the plugin CONTRACT — which the
    // server provides at load time so both sides share the same interface classes.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        // Strip jar signatures (invalid once merged) and module descriptors (meaningless in a fat jar).
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
    }
}

// Self-contained plugin jar: bundle every runtime dep INTO <tool>-<ver>.jar so it installs and runs
// standalone via the plugin classloader — nothing pulled into the server. intouch-tool-api is
// compileOnly (host provides that ABI), so it is correctly NOT bundled.
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
}
