package com.voicecontrol.domain.common

/** Domain-level failures mapped to HTTP statuses by the API layer. */
sealed class DomainException(message: String) : RuntimeException(message) {
    class Validation(message: String) : DomainException(message)
    class NotFound(message: String) : DomainException(message)
    class Conflict(message: String) : DomainException(message)
    class Unauthorized(message: String = "Unauthorized") : DomainException(message)
    class Forbidden(message: String = "Forbidden") : DomainException(message)
    class RateLimited(message: String = "Too many requests") : DomainException(message)
    class Upstream(message: String) : DomainException(message)
}
