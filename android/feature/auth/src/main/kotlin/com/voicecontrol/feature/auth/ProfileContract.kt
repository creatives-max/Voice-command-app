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
    data class DeleteAccount(val password: String) : ProfileIntent
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
fun UserProfile.validationError(): String? {
    val pin = pincode
    val phoneNumber = phone
    val mail = email
    val dob = dateOfBirth
    return when {
        pin != null && !Regex("^\\d{6}$").matches(pin) -> "PIN code must be 6 digits"
        phoneNumber != null && phoneNumber.count(Char::isDigit) !in 10..13 -> "Phone must have 10 to 13 digits"
        mail != null && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(mail) -> "Please enter a valid email address"
        dob != null && !Regex("^\\d{2}/\\d{2}/\\d{4}$").matches(dob) -> "Date of birth must look like 31/12/1990"
        else -> null
    }
}
