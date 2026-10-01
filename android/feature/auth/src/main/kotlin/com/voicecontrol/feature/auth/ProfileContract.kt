package com.voicecontrol.feature.auth

import com.voicecontrol.core.model.UserProfile

data class ProfileState(
    val email: String? = null,
    val signedIn: Boolean = false,
    val draft: UserProfile = UserProfile(),
    val saving: Boolean = false,
    val loaded: Boolean = false,
)

enum class ProfileField { FULL_NAME, EMAIL, PHONE, ADDRESS, CITY, STATE, PINCODE, DOB }

sealed interface ProfileIntent {
    data class Edit(val field: ProfileField, val value: String) : ProfileIntent
    data object Save : ProfileIntent
    data object SignOut : ProfileIntent
}

sealed interface ProfileEffect {
    data class Message(val text: String) : ProfileEffect
    data object SignedOut : ProfileEffect
}

fun UserProfile.with(field: ProfileField, value: String): UserProfile {
    val v = value.ifBlank { null }
    return when (field) {
        ProfileField.FULL_NAME -> copy(fullName = v)
        ProfileField.EMAIL -> copy(email = v)
        ProfileField.PHONE -> copy(phone = v)
        ProfileField.ADDRESS -> copy(addressLine = v)
        ProfileField.CITY -> copy(city = v)
        ProfileField.STATE -> copy(state = v)
        ProfileField.PINCODE -> copy(pincode = v)
        ProfileField.DOB -> copy(dateOfBirth = v)
    }
}

fun UserProfile.valueOf(field: ProfileField): String = when (field) {
    ProfileField.FULL_NAME -> fullName
    ProfileField.EMAIL -> email
    ProfileField.PHONE -> phone
    ProfileField.ADDRESS -> addressLine
    ProfileField.CITY -> city
    ProfileField.STATE -> state
    ProfileField.PINCODE -> pincode
    ProfileField.DOB -> dateOfBirth
}.orEmpty()

/** Local validation mirrors the server rules so mistakes show up before syncing. */
fun UserProfile.validationError(): String? = when {
    pincode != null && !Regex("^\\d{6}$").matches(pincode) -> "PIN code must be 6 digits"
    phone != null && phone.count(Char::isDigit) !in 10..13 -> "Phone must have 10 to 13 digits"
    email != null && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email) -> "Please enter a valid email address"
    dateOfBirth != null && !Regex("^\\d{2}/\\d{2}/\\d{4}$").matches(dateOfBirth) -> "Date of birth must look like 31/12/1990"
    else -> null
}
