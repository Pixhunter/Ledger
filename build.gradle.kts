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

// Classpath for the code generator only. Kept off the application's own
// classpath - codegen tooling has no business shipping in the jar.
val jooqGenerator: Configuration by configurations.creating

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
}

// Generated jOOQ classes are committed, so a clean clone compiles with no
// Docker and no database. Regenerate with ./scripts/jooq-generate.sh.
sourceSets["main"].java.srcDir("src/generated/jooq")

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
                  <schemata>
                    <schema><inputSchema>jobs</inputSchema></schema>
                    <schema><inputSchema>ledger</inputSchema></schema>
                  </schemata>
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

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.example.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
