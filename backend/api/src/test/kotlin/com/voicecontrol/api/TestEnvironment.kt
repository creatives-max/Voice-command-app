package com.voicecontrol.api

import com.voicecontrol.infrastructure.config.AppConfig
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** Real Postgres (with pgvector) and Redis shared by all integration tests in this JVM. */
object TestEnvironment {
    private val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("voicecontrol")
        .withUsername("vc")
        .withPassword("vc")
    private val redis = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)

    val services: Services by lazy {
        postgres.start()
        redis.start()
        val config = AppConfig.fromEnv(
            mapOf(
                "DATABASE_URL" to postgres.jdbcUrl,
                "DATABASE_USER" to postgres.username,
                "DATABASE_PASSWORD" to postgres.password,
                "REDIS_URL" to "redis://${redis.host}:${redis.getMappedPort(6379)}",
                "JWT_SECRET" to "test-secret-test-secret-test-secret-1234",
                "LLM_PROVIDER" to "rules",
                "EMBEDDING_PROVIDER" to "hashing",
                "AUTH_RATE_LIMIT_PER_MINUTE" to "1000",
                // Webhook tests deliver to a local HTTP server.
                "WEBHOOK_ALLOW_HTTP" to "true",
                "WEBHOOK_ALLOW_PRIVATE" to "true",
            ),
        )
        Bootstrap.create(config, bcryptCost = 4)
    }
}
