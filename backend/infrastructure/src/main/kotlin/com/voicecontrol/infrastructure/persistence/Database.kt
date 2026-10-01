package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.infrastructure.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Connection pool + migrations + small coroutine-friendly JDBC helpers. */
class Database(val dataSource: DataSource) {

    /** Runs [block] in a transaction on the IO dispatcher. */
    suspend fun <T> tx(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.block().also { c.commit() }
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }
    }

    fun migrate() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
    }

    companion object {
        fun connect(config: AppConfig.DatabaseConfig): Database {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.maxPoolSize
                minimumIdle = 1
                poolName = "voicecontrol"
                connectionTimeout = 10_000
            }
            return Database(HikariDataSource(hikari))
        }
    }
}

internal fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { ps -> ps.bind(params); ps.executeUpdate() }

internal fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { ps ->
        ps.bind(params)
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

internal fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { i, p ->
        when (p) {
            null -> setObject(i + 1, null)
            is Instant -> setTimestamp(i + 1, Timestamp.from(p))
            is UUID -> setObject(i + 1, p)
            else -> setObject(i + 1, p)
        }
    }
}

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
internal fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()
