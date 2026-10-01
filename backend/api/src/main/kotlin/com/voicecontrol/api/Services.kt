package com.voicecontrol.api

import com.voicecontrol.application.ai.AiService
import com.voicecontrol.application.marketplace.MarketplaceService
import com.voicecontrol.application.marketplace.StarterTemplates
import com.voicecontrol.infrastructure.persistence.JdbcMarketplaceRepository
import com.voicecontrol.application.automation.DeviceService
import com.voicecontrol.application.automation.RunRequestService
import com.voicecontrol.application.automation.TriggerScheduler
import com.voicecontrol.application.automation.TriggerService
import com.voicecontrol.infrastructure.persistence.JdbcDeviceRepository
import com.voicecontrol.infrastructure.persistence.JdbcRunRequestRepository
import com.voicecontrol.infrastructure.persistence.JdbcTriggerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import com.voicecontrol.application.auth.AuthService
import com.voicecontrol.application.flow.FlowCacheInvalidator
import com.voicecontrol.application.flow.FlowService
import com.voicecontrol.application.history.HistoryService
import com.voicecontrol.infrastructure.persistence.JdbcRunRepository
import com.voicecontrol.application.match.FlowEmbeddingHandler
import com.voicecontrol.application.match.FlowMatchService
import com.voicecontrol.domain.match.EmbeddingProvider
import com.voicecontrol.infrastructure.embedding.HashingEmbeddingProvider
import com.voicecontrol.infrastructure.embedding.OpenAiEmbeddingProvider
import com.voicecontrol.infrastructure.persistence.JdbcEmbeddingRepository
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
    val matcher: FlowMatchService,
    val history: HistoryService,
    val devices: DeviceService,
    val triggers: TriggerService,
    val runRequests: RunRequestService,
    val scheduler: TriggerScheduler,
    val marketplace: MarketplaceService,
    val rateLimiter: RateLimiter,
    val eventBus: RedisStreamEventBus,
    internal val database: Database,
    private val redis: RedisConnections,
    private val http: HttpClient,
) : AutoCloseable {

    suspend fun readiness(): Readiness {
        val db = withTimeoutOrNull(2_000) { runCatching { database.tx { isValid(2) } }.getOrDefault(false) } ?: false
        val cache = withTimeoutOrNull(2_000) { runCatching { redis.ping() }.getOrDefault(false) } ?: false
        return Readiness(db && cache, db, cache)
    }

    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fires due schedule triggers every [intervalMillis] (every replica runs it; claims are exclusive). */
    fun startScheduler(intervalMillis: Long) {
        val log = LoggerFactory.getLogger(TriggerScheduler::class.java)
        schedulerScope.launch {
            while (isActive) {
                runCatching { scheduler.tick() }
                    .onSuccess { if (it > 0) log.info("Fired {} scheduled flow(s)", it) }
                    .onFailure { log.warn("Scheduler tick failed", it) }
                delay(intervalMillis)
            }
        }
    }

    /** App package of a user's flow (for app-open triggers sent to the phone). */
    suspend fun flowApp(userId: java.util.UUID, flowId: java.util.UUID): String? =
        runCatching { flows.get(userId, flowId).flow.appPackage }.getOrNull()

    override fun close() {
        schedulerScope.cancel()
        eventBus.close()
        redis.close()
        http.close()
        (database.dataSource as? AutoCloseable)?.close()
    }
}

/** Wires adapters to ports. The only place that knows concrete infrastructure classes. */
object Bootstrap {
    private fun embeddingProvider(config: AppConfig, http: HttpClient): EmbeddingProvider {
        val key = config.llm.openAiApiKey
        return if (config.embeddingProvider == "openai" && key != null) {
            OpenAiEmbeddingProvider(http, key, config.llm.openAiBaseUrl)
        } else {
            HashingEmbeddingProvider()
        }
    }

    fun create(config: AppConfig, extraHandlers: List<EventHandler> = emptyList(), bcryptCost: Int = 12): Services {
        val database = Database.connect(config.database).also { it.migrate() }
        val redis = RedisConnections(config.redisUrl)
        val http = HttpClient(CIO) { engine { requestTimeout = config.llm.timeoutMillis * 3 } }
        val cache = RedisCache(redis)
        val flowRepository = JdbcFlowRepository(database)
        val embeddingRepository = JdbcEmbeddingRepository(database)
        val embedder = embeddingProvider(config, http)
        // Order matters: store the new embedding first, then drop cached match results,
        // so a concurrent match can't cache a miss computed without the new embedding.
        val handlers = listOf(
            FlowEmbeddingHandler(flowRepository, embeddingRepository, embedder),
            FlowCacheInvalidator(cache),
        ) + extraHandlers
        val bus = RedisStreamEventBus(redis, handlers).also { it.start() }
        val deviceRepository = JdbcDeviceRepository(database)
        val triggerRepository = JdbcTriggerRepository(database)
        val runRequestRepository = JdbcRunRequestRepository(database)
        val marketplaceRepository = JdbcMarketplaceRepository(database)
        kotlinx.coroutines.runBlocking { StarterTemplates.seed(marketplaceRepository) }
        val flowService = FlowService(flowRepository, bus)
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
            flows = flowService,
            matcher = FlowMatchService(flowRepository, embeddingRepository, embedder, cache),
            history = HistoryService(JdbcRunRepository(database)),
            devices = DeviceService(deviceRepository),
            triggers = TriggerService(triggerRepository, flowRepository, deviceRepository),
            runRequests = RunRequestService(runRequestRepository, deviceRepository, flowRepository),
            scheduler = TriggerScheduler(triggerRepository, runRequestRepository),
            marketplace = MarketplaceService(marketplaceRepository, flowRepository, flowService),
            rateLimiter = RedisRateLimiter(redis),
            eventBus = bus,
            database = database,
            redis = redis,
            http = http,
        )
    }
}
