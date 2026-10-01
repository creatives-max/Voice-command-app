package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.user.Profile
import com.voicecontrol.domain.user.ProfileRepository
import com.voicecontrol.domain.user.User
import com.voicecontrol.domain.user.UserRepository
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

class JdbcUserRepository(private val db: Database) : UserRepository {
    override suspend fun create(user: User): User = db.tx {
        try {
            update("INSERT INTO users (id, email, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)", user.id, user.email, user.name, user.passwordHash, user.createdAt)
        } catch (e: SQLException) {
            if (e.sqlState == "23505") throw DomainException.Conflict("An account with this email already exists")
            throw e
        }
        user
    }

    override suspend fun findByEmail(email: String): User? = db.tx {
        query("SELECT * FROM users WHERE lower(email) = lower(?)", email, map = ::toUser).firstOrNull()
    }

    override suspend fun findById(id: UUID): User? = db.tx {
        query("SELECT * FROM users WHERE id = ?", id, map = ::toUser).firstOrNull()
    }

    private fun toUser(rs: ResultSet) = User(rs.uuid("id"), rs.getString("email"), rs.getString("name"), rs.getString("password_hash"), rs.instant("created_at"))
}

class JdbcProfileRepository(private val db: Database) : ProfileRepository {
    override suspend fun get(userId: UUID): Profile? = db.tx {
        query("SELECT * FROM profiles WHERE user_id = ?", userId) { rs ->
            Profile(
                fullName = rs.getString("full_name"),
                email = rs.getString("email"),
                phone = rs.getString("phone"),
                addressLine = rs.getString("address_line"),
                city = rs.getString("city"),
                state = rs.getString("state"),
                pincode = rs.getString("pincode"),
                dateOfBirth = rs.getString("date_of_birth"),
            )
        }.firstOrNull()
    }

    override suspend fun upsert(userId: UUID, profile: Profile): Profile = db.tx {
        update(
            """
            INSERT INTO profiles (user_id, full_name, email, phone, address_line, city, state, pincode, date_of_birth, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (user_id) DO UPDATE SET
              full_name = EXCLUDED.full_name, email = EXCLUDED.email, phone = EXCLUDED.phone,
              address_line = EXCLUDED.address_line, city = EXCLUDED.city, state = EXCLUDED.state,
              pincode = EXCLUDED.pincode, date_of_birth = EXCLUDED.date_of_birth, updated_at = now()
            """.trimIndent(),
            userId, profile.fullName, profile.email, profile.phone, profile.addressLine, profile.city, profile.state, profile.pincode, profile.dateOfBirth,
        )
        profile
    }
}
