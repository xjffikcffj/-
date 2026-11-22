package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.remote.backend.BackendAdminVersionDataSource
import com.studentlearning.platform2026.data.remote.backend.BackendHttpClient
import com.studentlearning.platform2026.data.remote.firebase.AuthTokenProvider
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.util.LoopbackHttpServer
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetSocketAddress

@OptIn(ExperimentalCoroutinesApi::class)
class AdminGovernanceRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
    }

    private lateinit var server: LoopbackHttpServer
    private lateinit var baseUrl: String

    @Volatile
    private var staffListCallCount = 0

    @Volatile
    private var staffRoleCallCount = 0

    @Volatile
    private var lastRoleRequestBody: String = ""

    @Volatile
    private var returnInsufficientRoleOnStaff = false

    @Volatile
    private var returnUserNotFoundOnRole = false

    @Volatile
    private var broadcastSentFlag = false

    @Before
    fun setUp() {
        server = LoopbackHttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"

        server.createContext("/api/admin/staff/roles") { ex ->
            staffRoleCallCount++
            lastRoleRequestBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            when {
                returnInsufficientRoleOnStaff -> {
                    val body = """{"error":{"code":"INSUFFICIENT_ROLE","message":"Owner only"}}"""
                        .toByteArray(Charsets.UTF_8)
                    ex.responseHeaders.set("Content-Type", "application/json")
                    ex.sendResponseHeaders(403, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                returnUserNotFoundOnRole -> {
                    val body = """{"error":{"code":"USER_NOT_FOUND","message":"Not found"}}"""
                        .toByteArray(Charsets.UTF_8)
                    ex.responseHeaders.set("Content-Type", "application/json")
                    ex.sendResponseHeaders(404, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                else -> {
                    val body = """{"status":"UPDATED","uid":"admin-2","email":"admin2@example.com","role":"admin","tokensRevoked":true}"""
                        .toByteArray(Charsets.UTF_8)
                    ex.responseHeaders.set("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
            }
        }

        server.createContext("/api/admin/staff") { ex ->
            staffListCallCount++
            if (returnInsufficientRoleOnStaff) {
                val body = """{"error":{"code":"INSUFFICIENT_ROLE","message":"Owner only"}}"""
                    .toByteArray(Charsets.UTF_8)
                ex.responseHeaders.set("Content-Type", "application/json")
                ex.sendResponseHeaders(403, body.size.toLong())
                ex.responseBody.use { it.write(body) }
                return@createContext
            }
            val body = """
                {
                  "items": [
                    {"uid":"owner-1","email":"owner@example.com","displayName":"المالك","role":"owner","disabled":false,"emailVerified":true},
                    {"uid":"admin-1","email":"admin@example.com","displayName":"المشرف","role":"admin","disabled":false,"emailVerified":true}
                  ]
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        server.createContext("/api/admin/audit-logs") { ex ->
            val body = """
                {
                  "items": [
                    {
                      "id":"log-1",
                      "action":"VERSION_PUBLISHED",
                      "actorUid":"admin-1",
                      "actorRole":"admin",
                      "targetCollection":"study_files",
                      "targetId":"file-1",
                      "summary":"نشر الإصدار 1.0",
                      "source":"backend",
                      "createdAtEpochMillis":1700000005000
                    }
                  ]
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        server.createContext("/api/admin/notifications/broadcast") { ex ->
            val body = """
                {
                  "notificationId":"notif-1",
                  "topic":"haqibati_updates",
                  "type":"IMPORTANT_UPDATE",
                  "title":"تنبيه مهم",
                  "body":"تفاصيل التحديث",
                      "sent":$broadcastSentFlag,
                  "reason":${if (broadcastSentFlag) "null" else "\"FCM_NOT_CONFIGURED\""}
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun buildRepo(session: AdminSession): AdminGovernanceRepositoryImpl {
        val authRepo = FakeAuthRepository(session)
        val dataSource = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = baseUrl, allowInsecureHttpForTesting = true),
            authTokenProvider = object : AuthTokenProvider {
                override suspend fun getIdTokenOrNull(forceRefresh: Boolean): String = "token-123"
            }
        )
        return AdminGovernanceRepositoryImpl(
            authRepository = authRepo,
            backendDataSource = dataSource,
            dispatcherProvider = testDispatchers
        )
    }

    @Test
    fun `owner can fetch staff grant admin and prevent self modification or invalid email`() = runTest {
        val repo = buildRepo(
            AdminSession(
                isSignedIn = true,
                uid = "owner-1",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )

        val staffRes = repo.getStaffMembers()
        assertTrue(staffRes is AppResult.Success)
        val staff = (staffRes as AppResult.Success).data
        assertEquals(listOf("owner-1", "admin-1"), staff.map { it.uid })
        assertEquals(AdminRole.OWNER, staff[0].role)

        val emptyEmailRes = repo.setStaffAdminRoleByEmail("   ", assignAdmin = true)
        assertEquals(
            AdminValidationError.EMPTY_EMAIL,
            ((emptyEmailRes as AppResult.Error).cause as AdminException).validationError
        )
        val invalidEmailRes = repo.setStaffAdminRoleByEmail("not-an-email", assignAdmin = true)
        assertEquals(
            AdminValidationError.INVALID_EMAIL,
            ((invalidEmailRes as AppResult.Error).cause as AdminException).validationError
        )
        val selfRes = repo.setStaffAdminRoleByEmail("OWNER@example.com", assignAdmin = false)
        assertEquals(
            AdminValidationError.BACKEND_REQUEST_REJECTED,
            ((selfRes as AppResult.Error).cause as AdminException).validationError
        )
        assertEquals(0, staffRoleCallCount)

        val grantRes = repo.setStaffAdminRoleByEmail("Admin2@Example.com", assignAdmin = true)
        assertTrue(grantRes is AppResult.Success)
        assertEquals(1, staffRoleCallCount)
        assertTrue(lastRoleRequestBody.contains("admin2@example.com"))
        assertTrue(lastRoleRequestBody.contains("\"role\":\"admin\""))

        returnUserNotFoundOnRole = true
        val missingUserRes = repo.setStaffAdminRoleByEmail("missing@example.com", assignAdmin = true)
        assertEquals(
            AdminFailureReason.ACCOUNT_NOT_FOUND,
            ((missingUserRes as AppResult.Error).cause as AdminException).reason
        )
    }

    @Test
    fun `non-owner admin is denied staff endpoints locally before HTTP while allowed audit logs and broadcast`() = runTest {
        val repo = buildRepo(
            AdminSession(
                isSignedIn = true,
                uid = "admin-1",
                email = "admin@example.com",
                role = AdminRole.ADMIN,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )

        val staffRes = repo.getStaffMembers()
        assertEquals(
            AdminFailureReason.INSUFFICIENT_ROLE,
            ((staffRes as AppResult.Error).cause as AdminException).reason
        )
        val grantRes = repo.setStaffAdminRoleByEmail("other@example.com", assignAdmin = true)
        assertEquals(
            AdminFailureReason.INSUFFICIENT_ROLE,
            ((grantRes as AppResult.Error).cause as AdminException).reason
        )
        assertEquals(0, staffListCallCount)
        assertEquals(0, staffRoleCallCount)

        val logsRes = repo.getAuditLogs(limit = 20)
        assertTrue(logsRes is AppResult.Success)
        assertEquals("log-1", (logsRes as AppResult.Success).data.single().id)

        broadcastSentFlag = false
        val broadcastRes = repo.broadcastImportantUpdate("تنبيه مهم", "تفاصيل التحديث")
        assertTrue(broadcastRes is AppResult.Success)
        val outcome = (broadcastRes as AppResult.Success).data
        assertFalse(outcome.sent)
        assertEquals("FCM_NOT_CONFIGURED", outcome.reason)
    }
}
