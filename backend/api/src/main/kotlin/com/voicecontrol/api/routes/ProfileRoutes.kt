package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.profile.ProfileService
import com.voicecontrol.domain.user.Profile
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

fun Route.profileRoutes(profiles: ProfileService) {
    authenticate(JWT_AUTH) {
        get("/v1/profile") { call.respond(profiles.get(call.userId)) }
        put("/v1/profile") { call.respond(profiles.update(call.userId, call.receive<Profile>())) }
    }
}
