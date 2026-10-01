package com.voicecontrol.api

import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.DeviceCommandsDto
import com.voicecontrol.api.routes.DeviceDto
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.RunEventsDto
import com.voicecontrol.api.routes.RunEventInDto
import com.voicecontrol.api.routes.RunReportRequest
import com.voicecontrol.api.routes.RunRequestDto
import com.voicecontrol.api.routes.TriggerRequest
import com.voicecontrol.api.routes.UpdateDeviceRequest
import com.voicecontrol.api.routes.TriggerDto
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.automation.RunRequestStatus
import com.voicecontrol.domain.automation.RunSource
import com.voicecontrol.domain.automation.TriggerType
import com.voicecontrol.domain.flow.FlowStep
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutomationApiTest {
    private val flowBody = CreateFlowRequest(
        "com.shop.app", "Signup", "sig-${UUID.randomUUID()}",
        listOf(FlowStep("s1", 0, "vid:name", "Full name", ElementKind.TEXT_FIELD, FieldType.NAME)),
    )

    private suspend fun HttpClient.send(method: String, path: String, token: String, body: Any? = null): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> get(path, block)
            "POST" -> post(path, block)
            "PUT" -> put(path, block)
            "PATCH" -> patch(path, block)
            else -> delete(path, block)
        }
    }

    @Test
    fun `run now reaches the phone, which streams a live log back`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        val flow: FlowDto = client.send("POST", "/v1/flows", token, flowBody).body()

        // No phone yet.
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/v1/run-requests", token, mapOf("flowId" to flow.id)).status)

        val deviceId = UUID.randomUUID().toString()
        val device: DeviceDto = client.send("POST", "/v1/devices", token, mapOf("id" to deviceId, "name" to "Pixel 8")).body()
        assertTrue(device.online)
        assertTrue(device.remoteRuns)

        val request: RunRequestDto = client.send("POST", "/v1/run-requests", token, mapOf("flowId" to flow.id)).body()
        assertEquals(RunRequestStatus.PENDING, request.status)
        assertEquals(deviceId, request.deviceId)
        assertEquals(RunSource.MANUAL, request.source)

        val commands: DeviceCommandsDto = client.send("GET", "/v1/devices/$deviceId/commands?wait=1", token).body()
        assertEquals(listOf(request.id), commands.run.map { it.id })
        // Delivered once only.
        val again: DeviceCommandsDto = client.send("GET", "/v1/devices/$deviceId/commands?wait=0", token).body()
        assertTrue(again.run.isEmpty())

        val running: RunRequestDto = client.send(
            "POST", "/v1/run-requests/${request.id}/report", token,
            RunReportRequest(deviceId, RunRequestStatus.RUNNING, listOf(RunEventInDto("ask", "Asking Full name"))),
        ).body()
        assertEquals(RunRequestStatus.RUNNING, running.status)

        val log: RunEventsDto = client.send("GET", "/v1/run-requests/${request.id}/events?after=0", token).body()
        assertEquals(listOf("Requested from the dashboard for Pixel 8", "Delivered to the phone", "Asking Full name"), log.events.map { it.message })

        // Dashboard stops it; the phone sees the cancel and reports the end.
        assertEquals(RunRequestStatus.CANCEL_REQUESTED, client.send("POST", "/v1/run-requests/${request.id}/cancel", token).body<RunRequestDto>().status)
        val cancel: DeviceCommandsDto = client.send("GET", "/v1/devices/$deviceId/commands?wait=1", token).body()
        assertEquals(listOf(request.id), cancel.cancel)
        client.send("POST", "/v1/run-requests/${request.id}/report", token, RunReportRequest(deviceId, RunRequestStatus.STOPPED, listOf(RunEventInDto("status", "Stopped"))))
        val after: RunEventsDto = client.send("GET", "/v1/run-requests/${request.id}/events?after=${log.events.last().id}&wait=5", token).body()
        assertEquals(RunRequestStatus.STOPPED, after.request.status)
        assertEquals("Stopped", after.events.last().message)
        // Finished runs can't change.
        assertEquals(HttpStatusCode.Conflict, client.send("POST", "/v1/run-requests/${request.id}/report", token, RunReportRequest(deviceId, RunRequestStatus.COMPLETED)).status)

        // Turning remote runs off blocks run now.
        client.send("PATCH", "/v1/devices/$deviceId", token, UpdateDeviceRequest(remoteRuns = false))
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/v1/run-requests", token, mapOf("flowId" to flow.id, "deviceId" to deviceId)).status)

        val list: List<RunRequestDto> = client.send("GET", "/v1/run-requests?flowId=${flow.id}", token).body()
        assertEquals(1, list.size)
    }

    @Test
    fun `schedules fire once per due time and app-open triggers reach the phone`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        val flow: FlowDto = client.send("POST", "/v1/flows", token, flowBody).body()
        val deviceId = UUID.randomUUID().toString()
        client.send("POST", "/v1/devices", token, mapOf("id" to deviceId, "name" to "Phone"))

        val bad = client.send("POST", "/v1/flows/${flow.id}/triggers", token, mapOf("type" to "SCHEDULE", "cron" to "61 * * * *", "timezone" to "Asia/Kolkata"))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val badZone = client.send("POST", "/v1/flows/${flow.id}/triggers", token, mapOf("type" to "SCHEDULE", "cron" to "0 9 * * *", "timezone" to "Mars/Base"))
        assertEquals(HttpStatusCode.BadRequest, badZone.status)

        val schedule: TriggerDto = client.send(
            "POST", "/v1/flows/${flow.id}/triggers", token, mapOf("type" to "SCHEDULE", "cron" to "0 9 * * *", "timezone" to "Asia/Kolkata"),
        ).body()
        assertEquals(3, schedule.upcoming.size)
        val nextRun = Instant.parse(schedule.nextRunAt!!)
        assertTrue(nextRun.isAfter(Instant.now()))

        val appOpen: TriggerDto = client.send("POST", "/v1/flows/${flow.id}/triggers", token, mapOf("type" to "APP_OPEN")).body()
        val phoneTriggers: List<TriggerDto> = client.send("GET", "/v1/devices/$deviceId/triggers", token).body()
        assertEquals(listOf(appOpen.id), phoneTriggers.map { it.id })
        assertEquals("com.shop.app", phoneTriggers.single().appPackage)

        // Two "replicas" ticking at the same due time create exactly one request.
        val services = TestEnvironment.services
        val due = nextRun.plusSeconds(1)
        val fired = listOf(services.scheduler.tick(due), services.scheduler.tick(due)).sum()
        assertTrue(fired >= 1)
        val requests: List<RunRequestDto> = client.send("GET", "/v1/run-requests?flowId=${flow.id}", token).body()
        assertEquals(1, requests.count { it.source == RunSource.SCHEDULE })
        val updated: List<TriggerDto> = client.send("GET", "/v1/flows/${flow.id}/triggers", token).body()
        val nextAfter = Instant.parse(updated.first { it.type == TriggerType.SCHEDULE }.nextRunAt!!)
        assertEquals(Duration.ofDays(1), Duration.between(nextRun, nextAfter))

        // The phone reports an app-open run.
        val openRun: RunRequestDto = client.send("POST", "/v1/devices/$deviceId/app-open-runs", token, mapOf("flowId" to flow.id, "triggerId" to appOpen.id)).body()
        assertEquals(RunSource.APP_OPEN, openRun.source)
        assertEquals(RunRequestStatus.RUNNING, openRun.status)

        // Disable and delete.
        val disabled: TriggerDto = client.send("PUT", "/v1/triggers/${schedule.id}", token, TriggerRequest(TriggerType.SCHEDULE, enabled = false, cron = "0 9 * * *", timezone = "Asia/Kolkata")).body()
        assertEquals(null, disabled.nextRunAt)
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/v1/triggers/${schedule.id}", token).status)

        // Other users can't see or use any of it.
        val stranger = client.registerUser().accessToken
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/v1/flows/${flow.id}/triggers", stranger).status)
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/v1/devices/$deviceId/commands", stranger).status)
        assertEquals(HttpStatusCode.Conflict, client.send("POST", "/v1/devices", stranger, mapOf("id" to deviceId, "name" to "Hijack")).status)
    }

    @Test
    fun `undelivered requests expire`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        val flow: FlowDto = client.send("POST", "/v1/flows", token, flowBody).body()
        val deviceId = UUID.randomUUID().toString()
        client.send("POST", "/v1/devices", token, mapOf("id" to deviceId, "name" to "Phone"))
        val request: RunRequestDto = client.send("POST", "/v1/run-requests", token, mapOf("flowId" to flow.id)).body()
        TestEnvironment.services.scheduler.tick(Instant.now().plus(Duration.ofMinutes(11)))
        val log: RunEventsDto = client.send("GET", "/v1/run-requests/${request.id}/events", token).body()
        assertEquals(RunRequestStatus.EXPIRED, log.request.status)
    }
}
