package com.voicecontrol.application.profile

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.event.Cache
import com.voicecontrol.domain.user.Profile
import com.voicecontrol.domain.user.ProfileRepository
import kotlinx.serialization.json.Json
import java.util.UUID

/** Reusable personal details, cached in Redis (read on every voice session start). */
class ProfileService(
    private val profiles: ProfileRepository,
    private val cache: Cache,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(userId: UUID): Profile {
        val key = key(userId)
        cache.get(key)?.let { cached -> runCatching { return json.decodeFromString(Profile.serializer(), cached) } }
        val profile = profiles.get(userId) ?: Profile()
        cache.put(key, json.encodeToString(Profile.serializer(), profile), TTL_SECONDS)
        return profile
    }

    suspend fun update(userId: UUID, profile: Profile): Profile {
        val clean = validate(profile)
        val saved = profiles.upsert(userId, clean)
        cache.delete(key(userId))
        return saved
    }

    private fun validate(p: Profile): Profile {
        fun String?.clean(max: Int, field: String): String? {
            val v = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (v.length > max) throw DomainException.Validation("$field is too long")
            return v
        }
        val phone = p.phone.clean(20, "Phone")?.filter { it.isDigit() || it == '+' }
        if (phone != null && phone.count(Char::isDigit) !in 10..13) throw DomainException.Validation("Phone must have 10 to 13 digits")
        val pincode = p.pincode.clean(10, "PIN code")
        if (pincode != null && !Regex("^\\d{6}$").matches(pincode)) throw DomainException.Validation("PIN code must be 6 digits")
        val email = p.email.clean(254, "Email")?.lowercase()
        if (email != null && !Regex("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$").matches(email)) {
            throw DomainException.Validation("Please enter a valid email address")
        }
        return Profile(
            fullName = p.fullName.clean(120, "Name"),
            email = email,
            phone = phone,
            addressLine = p.addressLine.clean(300, "Address"),
            city = p.city.clean(80, "City"),
            state = p.state.clean(80, "State"),
            pincode = pincode,
            dateOfBirth = p.dateOfBirth.clean(10, "Date of birth"),
        )
    }

    private fun key(userId: UUID) = "vc:profile:$userId"

    private companion object {
        const val TTL_SECONDS = 600L
    }
}
