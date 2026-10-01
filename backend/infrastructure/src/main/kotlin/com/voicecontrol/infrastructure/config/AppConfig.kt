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
    /** Kafka brokers for flow events; when unset, events go to Redis Streams only. */
    val kafkaBootstrapServers: String? = null,
    val kafkaTopic: String = "vc.events",
    /** How often due webhook deliveries are sent; 0 turns delivery off on this replica. */
    val webhookIntervalSeconds: Int = 5,
    /** Development only: allow http:// webhook URLs and private/loopback addresses. */
    val webhookAllowHttp: Boolean = false,
    val webhookAllowPrivate: Boolean = false,
    /** Per-organization limits (members incl. open invitations, active API keys). */
    val orgMaxMembers: Int = 200,
    val orgMaxApiKeys: Int = 25,
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
        /**
         * Accepts a JDBC URL, or a `postgres://user:password@host:port/db` URL as hosting providers (Render,
         * Heroku…) give it; then the user and password come from the URL unless DATABASE_USER/PASSWORD are set.
         */
        private const val DEFAULT_DB_USER = "voicecontrol"

        fun databaseConfig(url: String, user: String?, password: String?, maxPoolSize: Int): DatabaseConfig {
            val match = Regex("^postgres(?:ql)?://(?:([^:@/]*)(?::([^@/]*))?@)?([^/?#]+)(/[^?#]*)?(\\?.*)?$").find(url.trim())
                ?: return DatabaseConfig(url, user ?: DEFAULT_DB_USER, password ?: DEFAULT_DB_USER, maxPoolSize)
            val (urlUser, urlPassword, hostPort, path, query) = match.destructured
            val decode = { v: String -> java.net.URLDecoder.decode(v, Charsets.UTF_8) }
            return DatabaseConfig(
                url = "jdbc:postgresql://$hostPort${path.ifEmpty { "/" }}$query",
                user = user ?: decode(urlUser),
                password = password ?: decode(urlPassword),
                maxPoolSize = maxPoolSize,
            )
        }

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun get(key: String, default: String) = env[key]?.takeIf { it.isNotBlank() } ?: default
            val jwtSecret = get("JWT_SECRET", "dev-only-change-me-dev-only-change-me")
            require(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
            return AppConfig(
                port = get("PORT", "8080").toInt(),
                database = databaseConfig(
                    url = get("DATABASE_URL", "jdbc:postgresql://localhost:5432/voicecontrol"),
                    user = env["DATABASE_USER"]?.takeIf { it.isNotBlank() },
                    password = env["DATABASE_PASSWORD"]?.takeIf { it.isNotBlank() },
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
                kafkaBootstrapServers = env["KAFKA_BOOTSTRAP_SERVERS"]?.takeIf { it.isNotBlank() },
                kafkaTopic = get("KAFKA_TOPIC", "vc.events"),
                webhookIntervalSeconds = get("WEBHOOK_INTERVAL_SECONDS", "5").toInt(),
                webhookAllowHttp = get("WEBHOOK_ALLOW_HTTP", "false").toBoolean(),
                webhookAllowPrivate = get("WEBHOOK_ALLOW_PRIVATE", "false").toBoolean(),
                orgMaxMembers = get("ORG_MAX_MEMBERS", "200").toInt(),
                orgMaxApiKeys = get("ORG_MAX_API_KEYS", "25").toInt(),
            )
        }
    }
}
