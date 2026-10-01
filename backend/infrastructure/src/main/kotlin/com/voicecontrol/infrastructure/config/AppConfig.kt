package com.voicecontrol.infrastructure.config

/** Runtime configuration, read from environment variables (12-factor). */
data class AppConfig(
    val port: Int,
    val database: DatabaseConfig,
    val redisUrl: String,
    val jwt: JwtConfig,
    val llm: LlmConfig,
    val embeddingProvider: String,
    val corsOrigins: List<String>,
    val otlpEndpoint: String?,
    val serviceName: String,
    /** Max register/login attempts per client IP per minute. */
    val authRateLimitPerMinute: Int = 10,
    /** How often schedule triggers are checked; 0 turns the scheduler off on this replica. */
    val schedulerIntervalSeconds: Int = 15,
) {
    data class DatabaseConfig(val url: String, val user: String, val password: String, val maxPoolSize: Int)
    data class JwtConfig(val secret: String, val issuer: String, val audience: String, val accessTtlSeconds: Long, val refreshTtlSeconds: Long)
    data class LlmConfig(
        val provider: String,
        val anthropicApiKey: String?,
        val anthropicModel: String,
        val openAiApiKey: String?,
        val openAiModel: String,
        val openAiBaseUrl: String,
        val timeoutMillis: Long,
    )

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun get(key: String, default: String) = env[key]?.takeIf { it.isNotBlank() } ?: default
            val jwtSecret = get("JWT_SECRET", "dev-only-change-me-dev-only-change-me")
            require(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
            return AppConfig(
                port = get("PORT", "8080").toInt(),
                database = DatabaseConfig(
                    url = get("DATABASE_URL", "jdbc:postgresql://localhost:5432/voicecontrol"),
                    user = get("DATABASE_USER", "voicecontrol"),
                    password = get("DATABASE_PASSWORD", "voicecontrol"),
                    maxPoolSize = get("DATABASE_POOL_SIZE", "10").toInt(),
                ),
                redisUrl = get("REDIS_URL", "redis://localhost:6379"),
                jwt = JwtConfig(
                    secret = jwtSecret,
                    issuer = get("JWT_ISSUER", "voicecontrol"),
                    audience = get("JWT_AUDIENCE", "voicecontrol-clients"),
                    accessTtlSeconds = get("JWT_ACCESS_TTL_SECONDS", "900").toLong(),
                    refreshTtlSeconds = get("JWT_REFRESH_TTL_SECONDS", "2592000").toLong(),
                ),
                llm = LlmConfig(
                    provider = get("LLM_PROVIDER", "rules").lowercase(),
                    anthropicApiKey = env["ANTHROPIC_API_KEY"]?.takeIf { it.isNotBlank() },
                    anthropicModel = get("ANTHROPIC_MODEL", "claude-opus-5-5"),
                    openAiApiKey = env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() },
                    openAiModel = get("OPENAI_MODEL", "gpt-4.1-mini"),
                    openAiBaseUrl = get("OPENAI_BASE_URL", "https://api.openai.com/v1"),
                    timeoutMillis = get("LLM_TIMEOUT_MS", "8000").toLong(),
                ),
                embeddingProvider = get("EMBEDDING_PROVIDER", "hashing").lowercase(),
                corsOrigins = get("CORS_ORIGINS", "http://localhost:3000").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                otlpEndpoint = env["OTEL_EXPORTER_OTLP_ENDPOINT"]?.takeIf { it.isNotBlank() },
                serviceName = get("OTEL_SERVICE_NAME", "voicecontrol-backend"),
                authRateLimitPerMinute = get("AUTH_RATE_LIMIT_PER_MINUTE", "10").toInt(),
                schedulerIntervalSeconds = get("SCHEDULER_INTERVAL_SECONDS", "15").toInt(),
            )
        }
    }
}
