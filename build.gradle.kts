import java.io.File
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Bump these if Gradle can't resolve them.
val ktorVersion = "3.2.3"
val jooqVersion = "3.20.5"
val coroutinesVersion = "1.10.2"
val postgresVersion = "42.7.7"
val hikariVersion = "6.3.0"
val testcontainersVersion = "1.21.3"
val jacksonVersion = "2.19.2"
val flywayVersion = "11.8.2"
val openApiGeneratorVersion = "7.11.0"

// Classpath for the code generator only. Kept off the application's own
// classpath - codegen tooling has no business shipping in the jar.
val jooqGenerator: Configuration by configurations.creating
val openApiGenerator: Configuration by configurations.creating

dependencies {
    // HTTP
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // DB
    implementation("org.jooq:jooq:$jooqVersion")
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")

    // Migrations run in-process at startup; no Flyway container.
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // jsonb columns are serialised with Jackson (kotlinx.serialization stays on the API edge).
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")

    jooqGenerator("org.jooq:jooq-codegen:$jooqVersion")
    jooqGenerator("org.jooq:jooq-meta:$jooqVersion")
    jooqGenerator("org.postgresql:postgresql:$postgresVersion")

    openApiGenerator("org.openapitools:openapi-generator-cli:$openApiGeneratorVersion")
}

// src/generated is gitignored: a clean clone must run ./scripts/jooq-generate.sh
// (needs a migrated database) and ./scripts/api-generate.sh before compiling.
sourceSets["main"].java.srcDir("src/generated/jooq")
sourceSets["main"].java.srcDir("src/generated/api")

/**
 * Runs jOOQ's GenerationTool directly instead of using the Gradle plugin.
 *
 * The plugin adds a DSL extension that has to track Gradle's API, and it
 * breaks on newer Gradle with confusing "cannot access java.io.Serializable"
 * script errors. GenerationTool itself is a plain main() that takes an XML
 * file, so this works on any Gradle version and is easy to debug: the config
 * it ran with is left in build/jooq-config.xml.
 */
tasks.register<JavaExec>("jooqCodegen") {
    group = "build"
    description = "Generates jOOQ classes from a live database"

    classpath = jooqGenerator
    mainClass.set("org.jooq.codegen.GenerationTool")

    val configFile = layout.buildDirectory.file("jooq-config.xml")
    outputs.upToDateWhen { false }

    doFirst {
        val url = providers.gradleProperty("jooq.url")
            .getOrElse("jdbc:postgresql://localhost:3333/ledger")
        val user = providers.gradleProperty("jooq.user").getOrElse("ledger")
        val password = providers.gradleProperty("jooq.password").getOrElse("ledger")

        val file = configFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            <configuration>
              <jdbc>
                <driver>org.postgresql.Driver</driver>
                <url>$url</url>
                <user>$user</user>
                <password>$password</password>
              </jdbc>
              <generator>
                <name>org.jooq.codegen.KotlinGenerator</name>
                <database>
                  <name>org.jooq.meta.postgres.PostgresDatabase</name>
                  <inputSchema>mor</inputSchema>
                  <includes>.*</includes>
                  <excludes>flyway_schema_history</excludes>
                </database>
                <generate>
                  <deprecated>false</deprecated>
                  <records>true</records>
                  <pojos>false</pojos>
                  <kotlinNotNullRecordAttributes>true</kotlinNotNullRecordAttributes>
                </generate>
                <target>
                  <packageName>org.example.jooq</packageName>
                  <directory>src/generated/jooq</directory>
                </target>
              </generator>
            </configuration>
            """.trimIndent()
        )

        args = listOf(file.absolutePath)
        logger.lifecycle("jooq codegen from $url")
    }
}

tasks.register<JavaExec>("apiCodegen") {
    group = "build"
    description = "Generates DTOs from api/definitions.yaml"

    classpath = openApiGenerator
    mainClass.set("org.openapitools.codegen.OpenAPIGenerator")

    val output = layout.projectDirectory.dir("src/generated/api")
    val bundled = layout.buildDirectory.file("api-models.yaml")

    doFirst {
        delete(output)

        // The generator inlines external $refs and then names the model after
        // the operation, so api.yaml would give CapturePaymentRequestDto.
        // Generating from the schema file keeps the declared names.
        val spec = bundled.get().asFile
        spec.parentFile.mkdirs()
        spec.writeText(
            buildString {
                appendLine("openapi: 3.1.0")
                appendLine("info:")
                appendLine("  title: MoR Ledger API models")
                appendLine("  version: 0.1.0")
                appendLine("paths: {}")
                append(file("api/definitions.yaml").readText())
            }
        )

        args = listOf(
            "generate",
            "-i", spec.absolutePath,
            "-g", "kotlin",
            "-o", output.asFile.absolutePath,
            "--model-name-suffix", "Dto",
            "--package-name", "org.example.api.generated",
            "--model-package", "org.example.api.generated.model",
            "--global-property", "models,modelDocs=false,modelTests=false",
            "--type-mappings", "UUID=kotlin.String,DateTime=kotlin.String,date-time=kotlin.String",
            "--additional-properties",
            "serializationLibrary=kotlinx_serialization,enumPropertyNaming=UPPERCASE,sourceFolder=,dateLibrary=string",
        )
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.example.MainKt")
}

tasks.test {
    useJUnitPlatform()

    // A skipped database test must be visible. Without this the build prints
    // BUILD SUCCESSFUL whether Postgres was exercised or quietly bypassed.
    testLogging {
        events("skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }

    // One JVM, one container. A second fork would start a second Postgres,
    // and on a laptop that is how a test run turns into a swap storm. Classes
    // that need the database queue behind each other instead; the container
    // starts once for the whole run and Ryuk removes it at exit.
    maxParallelForks = 1
    forkEvery = 0
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")

    // Testcontainers looks for DOCKER_HOST, then /var/run/docker.sock. Colima
    // and Rancher Desktop put their socket under the user's home instead, so
    // the lookup fails and every database test dies with "Could not find a
    // valid Docker environment". Docker Desktop needs none of this - the list
    // below finds nothing and the block is a no-op.
    //
    // The socket override tells Ryuk, which runs inside the VM, where the
    // socket is from ITS point of view, which is always /var/run/docker.sock.
    if (System.getenv("DOCKER_HOST") == null) {
        val home = System.getProperty("user.home")
        val socket = listOf(
            "$home/.colima/default/docker.sock",
            "$home/.colima/docker.sock",
            "$home/.rd/docker.sock",
            "$home/.docker/run/docker.sock",
        ).firstOrNull { File(it).exists() }

        if (socket != null) {
            environment("DOCKER_HOST", "unix://$socket")
            environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")

            // Without this docker-java falls back to API 1.32 and Colima's
            // daemon rejects anything below 1.40. 1.41 is Docker 20.10, old
            // enough to be safe everywhere and new enough to be accepted.
            if (System.getenv("DOCKER_API_VERSION") == null) {
                environment("DOCKER_API_VERSION", "1.41")
                environment("API_VERSION", "1.41")
                systemProperty("api.version", "1.41")
            }

            logger.lifecycle("testcontainers docker socket: $socket")
        }
    }
}
