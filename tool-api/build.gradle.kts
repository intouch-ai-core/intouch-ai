plugins {
    kotlin("jvm") version "1.9.10"
}

group = "com.blueisle.intouch"
version = "8.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10")
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

// Keep the artifact name identical to the one published on Releases, so the jar a reader
// downloads and the jar this build produces are interchangeable.
tasks.jar { archiveBaseName.set("intouch-tool-api") }
