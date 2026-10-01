package com.voicecontrol.api

import com.voicecontrol.application.ai.AiService
import com.voicecontrol.application.auth.AuthService
import com.voicecontrol.application.flow.FlowCacheInvalidator
import com.voicecontrol.application.flow.FlowService
import com.voicecontrol.application.profile.ProfileService
import com.voicecontrol.domain.event.EventHandler
import com.voicecontrol.domain.event.RateLimiter
import com.voicecontrol.infrastructure.ai.LlmProviderFactory
import com.voicecontrol.infrastructure.config.AppConfig
import com.voicecontrol.infrastructure.persistence.Database
import com.voicecontrol.infrastructure.persistence.JdbcFlowRepository
import com.voicecontrol.infrastructure.persistence.JdbcProfileRepository
import com.voicecontrol.infrastructure.persistence.JdbcUserRepository
import com.voicecontrol.infrastructure.redis.RedisCache
import com.voicecontrol.infrastructure.redis.RedisConnections
import com.voicecontrol.infrastructure.redis.RedisRateLimiter
import com.voicecontrol.infrastructure.redis.RedisSessionStore
import com.voicecontrol.infrastructure.redis.RedisStreamEventBus
import com.voicecontrol.infrastructure.security.BcryptPasswordHasher
import com.voicecontrol.infrastructure.security.JwtIssuer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.withTimeoutOrNull

/** Everything the HTTP layer needs (composition root output). */
class Services(
    val config: AppConfig,
    val ai: AiService,
    val auth: AuthService,
    val profiles: ProfileService,
    val flows: FlowService,
    val rateLimiter: RateLimiter,
    val eventBus: RedisStreamEventBus,
    private val database: Database,
    private val redis: RedisConnections,
    private val http: HttpClient,
) : AutoCloseable {

    suspend fun readiness(): Readiness {
        val db = withTimeoutOrNull(2_000) { runCatching { database.tx { isValid(2) } }.getOrDefault(false) } ?: false
        val cache = withTimeoutOrNull(2_000) { runCatching { redis.ping() }.getOrDefault(false) } ?: false
        return Readiness(db && cache, db, cache)
    }

    override fun close() {
        eventBus.close()
        redis.close()
        http.close()
        (database.dataSource as? AutoCloseable)?.close()
    }
}

/** Wires adapters to ports. The only place that knows concrete infrastructure classes. */
object Bootstrap {
    fun create(config: AppConfig, extraHandlers: List<EventHandler> = emptyList(), bcryptCost: Int = 12): Services {
        val database = Database.connect(config.database).also { it.migrate() }
        val redis = RedisConnections(config.redisUrl)
        val http = HttpClient(CIO) { engine { requestTimeout = config.llm.timeoutMillis * 3 } }
        val cache = RedisCache(redis)
        val handlers = listOf<EventHandler>(FlowCacheInvalidator(cache)) + extraHandlers
        val bus = RedisStreamEventBus(redis, handlers).also { it.start() }
        val flowRepository = JdbcFlowRepository(database)
        return Services(
            config = config,
            ai = AiService(LlmProviderFactory.create(config.llm, http), timeoutMillis = config.llm.timeoutMillis),
            auth = AuthService(
                users = JdbcUserRepository(database),
                hasher = BcryptPasswordHasher(bcryptCost),
                issuer = JwtIssuer(config.jwt),
                sessions = RedisSessionStore(redis, config.jwt.refreshTtlSeconds),
            ),
            profiles = ProfileService(JdbcProfileRepository(database), cache),
            flows = FlowService(flowRepository, bus),
            rateLimiter = RedisRateLimiter(redis),
            eventBus = bus,
            database = database,
            redis = redis,
            http = http,
        )
    }
}
