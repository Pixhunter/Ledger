package org.example.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

@JsonIgnoreProperties(ignoreUnknown = true)
data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
) {
    companion object {

        private const val DEFAULT_PATH = "config/application.yaml"

        // jacksonObjectMapper() takes a configuration lambda, not a factory -
        // passing YAMLFactory() there compiles but blows up at runtime.
        private val yaml: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        /**
         * File first, environment second
         */
        fun load(path: String = System.getenv("CONFIG_FILE") ?: DEFAULT_PATH): AppConfig {
            val file = File(path)

            val fromFile = if (file.isFile) {
                yaml.readValue<AppConfig>(file)
            } else {
                AppConfig()
            }

            return fromFile.withEnvOverrides()
        }

        private fun AppConfig.withEnvOverrides() = copy(
            server = server.copy(
                host = env("SERVER_HOST") ?: server.host,
                port = env("SERVER_PORT")?.toInt() ?: server.port,
                publicUrl = env("SERVER_PUBLIC_URL") ?: server.publicUrl,
            ),
            database = database.copy(
                url = env("DB_URL") ?: database.url,
                user = env("DB_USER") ?: database.user,
                password = env("DB_PASSWORD") ?: database.password,
                poolSize = env("DB_POOL_SIZE")?.toInt() ?: database.poolSize,
            ),
        )

        private fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8081,
    val publicUrl: String? = null,
) {
    /** What the OpenAPI `servers` block advertises. */
    val effectivePublicUrl: String
        get() = publicUrl?.takeIf { it.isNotBlank() } ?: "http://localhost:$port"
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DatabaseConfig(
    val url: String = "jdbc:postgresql://localhost:3333/ledger",
    val user: String = "ledger",
    val password: String = "ledger",
    val poolSize: Int = 10,
)
