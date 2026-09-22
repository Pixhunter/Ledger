plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jooq.jooq-codegen-gradle") version "3.20.5"
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

    // Driver used by the code generator itself (not by the app).
    jooqCodegen("org.postgresql:postgresql:$postgresVersion")
}

// Generated jOOQ classes are committed to the repo, so a clean clone builds
// with no Docker and no database. Regenerate with ./scripts/jooq-generate.sh
// after changing db/migration/*.sql.
sourceSets["main"].java.srcDir("src/generated/jooq")

jooq {
    configuration {
        jdbc {
            driver = "org.postgresql.Driver"
            url = providers.gradleProperty("jooq.url")
                .getOrElse("jdbc:postgresql://localhost:3333/ledger")
            user = providers.gradleProperty("jooq.user").getOrElse("ledger")
            password = providers.gradleProperty("jooq.password").getOrElse("ledger")
        }

        generator {
            name = "org.jooq.codegen.KotlinGenerator"

            database {
                name = "org.jooq.meta.postgres.PostgresDatabase"
                inputSchema = "jobs"
                // Flyway's bookkeeping table is not part of the domain.
                excludes = "flyway_schema_history"
            }

            generate {
                isDeprecated = false
                isRecords = true
                isPojos = false
                isImmutablePojos = false
                isKotlinNotNullRecordAttributes = true
            }

            target {
                packageName = "org.example.jooq"
                directory = "src/generated/jooq"
            }
        }
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
