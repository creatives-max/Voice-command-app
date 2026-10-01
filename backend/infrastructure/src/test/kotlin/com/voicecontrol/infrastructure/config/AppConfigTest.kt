package com.voicecontrol.infrastructure.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {
    @Test
    fun `hosting-style postgres URLs become JDBC URLs with their credentials`() {
        val c = AppConfig.databaseConfig("postgres://vc_user:p%40ss@dpg-abc123-a:5432/voicecontrol_db", null, null, 10)
        assertEquals("jdbc:postgresql://dpg-abc123-a:5432/voicecontrol_db", c.url)
        assertEquals("vc_user", c.user)
        assertEquals("p@ss", c.password)

        val noPort = AppConfig.databaseConfig("postgresql://u:pw@db.example.com/app?sslmode=require", null, null, 5)
        assertEquals("jdbc:postgresql://db.example.com/app?sslmode=require", noPort.url)

        // Explicit DATABASE_USER / DATABASE_PASSWORD win over the URL's.
        val explicit = AppConfig.databaseConfig("postgres://u:pw@h/db", "other", "secret", 5)
        assertEquals("other" to "secret", explicit.user to explicit.password)
    }

    @Test
    fun `JDBC URLs and defaults are kept as they were`() {
        val env = mapOf("DATABASE_URL" to "jdbc:postgresql://localhost:5432/voicecontrol")
        val c = AppConfig.fromEnv(env).database
        assertEquals("jdbc:postgresql://localhost:5432/voicecontrol", c.url)
        assertEquals("voicecontrol" to "voicecontrol", c.user to c.password)
        val custom = AppConfig.fromEnv(env + mapOf("DATABASE_USER" to "a", "DATABASE_PASSWORD" to "b")).database
        assertEquals("a" to "b", custom.user to custom.password)
        val fromUrl = AppConfig.fromEnv(mapOf("DATABASE_URL" to "postgres://ru:rp@host:5432/db")).database
        assertEquals(Triple("jdbc:postgresql://host:5432/db", "ru", "rp"), Triple(fromUrl.url, fromUrl.user, fromUrl.password))
    }
}
