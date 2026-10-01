package com.voicecontrol.infrastructure.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await

/** One shared connection for commands; blocking stream reads get their own connection. */
class RedisConnections(url: String) : AutoCloseable {
    private val client: RedisClient = RedisClient.create(url)
    private val shared: StatefulRedisConnection<String, String> = client.connect()

    val commands: RedisAsyncCommands<String, String> get() = shared.async()

    suspend fun ping(): Boolean = commands.ping().await() == "PONG"

    fun dedicated(): StatefulRedisConnection<String, String> = client.connect()

    override fun close() {
        shared.close()
        client.shutdown()
    }
}
