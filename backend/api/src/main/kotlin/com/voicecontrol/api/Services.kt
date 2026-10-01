package com.voicecontrol.api

import com.voicecontrol.api.plugins.ApiKeyPrincipal
import com.voicecontrol.application.ai.AiService
import com.voicecontrol.application.account.AccountService
import com.voicecontrol.application.account.CrashService
import com.voicecontrol.infrastructure.persistence.JdbcAccountDataRepository
import com.voicecontrol.infrastructure.persistence.JdbcCrashRepository
import com.voicecontrol.application.collab.AnalyticsService
import com.voicecontrol.application.collab.CommentService
import com.voicecontrol.application.collab.LayoutService
import com.voicecontrol.application.collab.PresenceService
import com.voicecontrol.infrastructure.persistence.JdbcAnalyticsRepository
import com.voicecontrol.infrastructure.persistence.JdbcCommentRepository
import com.voicecontrol.infrastructure.persistence.JdbcLayoutRepository
import com.voicecontrol.infrastructure.redis.RedisPresenceStore
import com.voicecontrol.application.org.ApiKeyService
import com.voicecontrol.application.org.AuditService
import com.voicecontrol.application.org.OrgAccess
import com.voicecontrol.application.org.OrgService
import com.voicecontrol.application.org.WebhookDispatcher
import com.voicecontrol.application.org.WebhookService
import com.voicecontrol.application.org.WebhookUrlPolicy
import com.voicecontrol.application.org.WebhookWorker
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.event.EventPublisher
import com.voicecontrol.domain.org.OrgRepository
import com.voicecontrol.infrastructure.kafka.FallbackEventPublisher
import com.voicecontrol.infrastructure.kafka.KafkaEventBus
import com.voicecontrol.infrastructure.persistence.JdbcApiKeyRepository
import com.voicecontrol.infrastructure.persistence.JdbcAuditRepository
import com.voicecontrol.infrastructure.persistence.JdbcOrgRepository
import com.voicecontrol.infrastructure.persistence.JdbcWebhookRepository
import com.voicecontrol.infrastructure.webhook.HttpWebhookSender
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
    val orgs: OrgService,
    val apiKeys: ApiKeyService,
    val webhooks: WebhookService,
    val webhookWorker: WebhookWorker,
    val audit: AuditService,
    val orgRepository: OrgRepository,
    val analytics: AnalyticsService,
    val comments: CommentService,
    val presence: PresenceService,
    val layouts: LayoutService,
    val account: AccountService,
    val crashes: CrashService,
    val care: com.voicecontrol.application.care.CareService,
    val eventBus: RedisStreamEventBus,
    /** Kafka bus when KAFKA_BOOTSTRAP_SERVERS is set; Redis Streams stays the fallback queue. */
    val kafkaBus: KafkaEventBus?,
    internal val database: Database,
    private val redis: RedisConnections,
    private val http: HttpClient,
    private val webhookSender: HttpWebhookSender,
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

    /** Sends due webhook deliveries every [intervalMillis] (every replica runs it; claims are exclusive). */
    fun startWebhookWorker(intervalMillis: Long) {
        val log = LoggerFactory.getLogger(WebhookWorker::class.java)
        schedulerScope.launch {
            while (isActive) {
                runCatching { webhookWorker.tick() }.onFailure { log.warn("Webhook delivery tick failed", it) }
                delay(intervalMillis)
            }
        }
    }

    /**
     * Resolves an API key for the API-key authentication provider: the key must be active, its creator must
     * still belong to the key's organization, and calls are limited per key per minute.
     */
    suspend fun apiKeyPrincipal(secret: String): ApiKeyPrincipal? {
        val key = apiKeys.authenticate(secret) ?: return null
        val creator = key.createdBy ?: return null
        orgRepository.role(key.orgId, creator) ?: return null
        if (!rateLimiter.tryAcquire("apikey:${key.id}", key.rateLimitPerMinute, 60)) {
            throw DomainException.RateLimited("This API key is limited to ${key.rateLimitPerMinute} requests per minute")
        }
        return ApiKeyPrincipal(key, creator)
    }

    /** App package of a user's flow (for app-open triggers sent to the phone). */
    /** App package and name of a flow the user can see (nulls when it can't be read). */
    suspend fun flowInfo(userId: java.util.UUID, flowId: java.util.UUID): Pair<String?, String?> =
        runCatching { flows.get(userId, flowId).flow }.getOrNull().let { it?.appPackage to it?.name }

    override fun close() {
        schedulerScope.cancel()
        kafkaBus?.close()
        eventBus.close()
        webhookSender.close()
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
        val webhookRepository = JdbcWebhookRepository(database)
        val handlers = listOf(
            FlowEmbeddingHandler(flowRepository, embeddingRepository, embedder),
            FlowCacheInvalidator(cache),
            WebhookDispatcher(webhookRepository),
        ) + extraHandlers
        val bus = RedisStreamEventBus(redis, handlers).also { it.start() }
        // Kafka carries flow events when configured; the Redis stream remains consumed as the fallback queue.
        val kafka = config.kafkaBootstrapServers?.let { servers ->
            KafkaEventBus(servers, handlers, topic = config.kafkaTopic).also {
                runCatching { it.ensureTopics() }.onFailure { e -> LoggerFactory.getLogger(Bootstrap::class.java).warn("Could not create Kafka topics: {}", e.message) }
                it.start()
            }
        }
        val publisher: EventPublisher = if (kafka != null) FallbackEventPublisher(kafka, bus) else bus
        val orgRepository = JdbcOrgRepository(database)
        val access = OrgAccess(orgRepository)
        val audit = AuditService(JdbcAuditRepository(database), access)
        val urlPolicy = WebhookUrlPolicy(config.webhookAllowHttp, config.webhookAllowPrivate)
        val webhookSender = HttpWebhookSender()
        val deviceRepository = JdbcDeviceRepository(database)
        val triggerRepository = JdbcTriggerRepository(database)
        val runRequestRepository = JdbcRunRequestRepository(database)
        val marketplaceRepository = JdbcMarketplaceRepository(database)
        kotlinx.coroutines.runBlocking { StarterTemplates.seed(marketplaceRepository) }
        val flowService = FlowService(flowRepository, publisher, orgs = orgRepository, audit = audit)
        val tenantLimits = com.voicecontrol.application.org.TenantLimits(maxMembers = config.orgMaxMembers, maxApiKeys = config.orgMaxApiKeys)
        return Services(
            config = config,
            ai = AiService(LlmProviderFactory.create(config.llm, http), timeoutMillis = config.llm.timeoutMillis, cache = cache),
            auth = AuthService(
                users = JdbcUserRepository(database),
                hasher = BcryptPasswordHasher(bcryptCost),
                issuer = JwtIssuer(config.jwt),
                sessions = RedisSessionStore(redis, config.jwt.refreshTtlSeconds),
                failures = cache,
            ),
            profiles = ProfileService(JdbcProfileRepository(database), cache),
            flows = flowService,
            matcher = FlowMatchService(flowRepository, embeddingRepository, embedder, cache),
            history = HistoryService(JdbcRunRepository(database)),
            devices = DeviceService(deviceRepository),
            triggers = TriggerService(triggerRepository, flowRepository, deviceRepository),
            runRequests = RunRequestService(runRequestRepository, deviceRepository, flowRepository, events = publisher),
            scheduler = TriggerScheduler(triggerRepository, runRequestRepository),
            marketplace = MarketplaceService(marketplaceRepository, flowRepository, flowService),
            rateLimiter = RedisRateLimiter(redis),
            orgs = OrgService(
                orgRepository, JdbcUserRepository(database), access, audit,
                membershipChanged = { userId -> cache.deleteByPrefix(FlowCacheInvalidator.userCachePattern(userId.toString())) },
                limits = tenantLimits,
            ),
            apiKeys = ApiKeyService(JdbcApiKeyRepository(database), access, audit, limits = tenantLimits),
            webhooks = WebhookService(webhookRepository, access, audit, urlPolicy),
            webhookWorker = WebhookWorker(webhookRepository, webhookSender, urlPolicy),
            audit = audit,
            orgRepository = orgRepository,
            analytics = AnalyticsService(JdbcAnalyticsRepository(database), flowService),
            comments = CommentService(JdbcCommentRepository(database), flowService, JdbcUserRepository(database), audit),
            presence = PresenceService(RedisPresenceStore(redis), flowService, JdbcUserRepository(database)),
            layouts = LayoutService(JdbcLayoutRepository(database), flowService),
            account = AccountService(
                JdbcAccountDataRepository(database), JdbcUserRepository(database), BcryptPasswordHasher(bcryptCost), RedisSessionStore(redis, config.jwt.refreshTtlSeconds),
                onDeleted = { userId -> cache.deleteByPrefix(FlowCacheInvalidator.userCachePattern(userId.toString())) },
            ),
            crashes = CrashService(JdbcCrashRepository(database)),
            care = com.voicecontrol.application.care.CareService(com.voicecontrol.infrastructure.persistence.JdbcCareRepository(database)),
            eventBus = bus,
            kafkaBus = kafka,
            database = database,
            redis = redis,
            http = http,
            webhookSender = webhookSender,
        )
    }
}
