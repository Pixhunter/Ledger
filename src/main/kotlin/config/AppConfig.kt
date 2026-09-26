package org.example.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import org.example.Constants.Config.DEFAULT_PATH

@JsonIgnoreProperties(ignoreUnknown = true)
data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val mor: MorConfig = MorConfig(),
    val psp: PspConfig = PspConfig(),
) {
    companion object {

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
            mor = mor.copy(
                country = env("MOR_COUNTRY") ?: mor.country,
                feeBasisPoints = env("MOR_FEE_BP")?.toInt() ?: mor.feeBasisPoints,
            ),
            psp = psp.copy(
                secret = env("PSP_SECRET") ?: psp.secret,
                allowSecretHeader = env("PSP_ALLOW_SECRET_HEADER")?.toBoolean() ?: psp.allowSecretHeader,
            ),
            database = database.copy(
                url = env("DB_URL") ?: database.url,
                user = env("DB_USER") ?: database.user,
                password = env("DB_PASSWORD") ?: database.password,
                poolSize = env("DB_POOL_SIZE")?.toInt() ?: database.poolSize,
                connectionTimeoutMs = env("DB_CONNECTION_TIMEOUT_MS")?.toLong() ?: database.connectionTimeoutMs,
                statementTimeoutMs = env("DB_STATEMENT_TIMEOUT_MS")?.toLong() ?: database.statementTimeoutMs,
                lockTimeoutMs = env("DB_LOCK_TIMEOUT_MS")?.toLong() ?: database.lockTimeoutMs,
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
    val connectionTimeoutMs: Long = 5_000,
    val statementTimeoutMs: Long = 30_000,
    val lockTimeoutMs: Long = 5_000,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MorConfig(
    /** Country of establishment. Decides domestic vs cross-border B2B. */
    val country: String = "DE",
    /** Flat MoR fee on the gross, basis points. 500 = 5.00%. */
    val feeBasisPoints: Int = 500,
    /**
     * Classpath file of merchants to insert or refresh at startup. Blank in
     * production, where merchants come from the onboarding service.
     */
    val merchantSeedResource: String? = "merchants.json",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PspConfig(
    /** HMAC secret for webhook signatures. */
    val secret: String? = null,
    /** Local only: also accept the secret itself as the header, so Swagger can authorise. */
    val allowSecretHeader: Boolean = false,
)
