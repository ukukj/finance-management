package com.investment.dashboard.auth

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.support.MutableClock
import com.investment.dashboard.support.TestClockConfiguration
import com.investment.dashboard.support.TestLogin
import com.jayway.jsonpath.JsonPath
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.spec.SecretKeySpec

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class LoginApiTest {

    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    lateinit var loginAttemptRepository: LoginAttemptRepository

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun reset() {
        refreshTokenRepository.deleteAllInBatch()
        loginAttemptRepository.save(LoginAttempt(1, 0, null))
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    @DisplayName(
        "맞는 비밀번호로 로그인하면 access token 을 주고 refresh 쿠키가 HttpOnly·SameSite=Strict·/api/auth·30일로 내려온다",
    )
    fun login_returns_access_token_and_refresh_cookie() {
        login("owner", "test-pass").andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { isNotEmpty() }
            jsonPath("$.expiresAt") { value("2026-11-22T10:30:00+09:00") }
            cookie {
                value(REFRESH_COOKIE, matchesPattern("[A-Za-z0-9_-]{43}"))
                httpOnly(REFRESH_COOKIE, true)
                secure(REFRESH_COOKIE, true)
                sameSite(REFRESH_COOKIE, "Strict")
                path(REFRESH_COOKIE, "/api/auth")
                maxAge(REFRESH_COOKIE, 2592000)
            }
        }
    }

    @Test
    @DisplayName("access token 으로 /api/auth/me 는 200 이고 username 을 준다")
    fun me_returns_username_with_access_token() {
        val accessToken = accessTokenOf(loginOk())

        me(accessToken).andExpect {
            status { isOk() }
            jsonPath("$.username") { value("owner") }
        }
    }

    @Test
    @DisplayName("틀린 비밀번호는 401 BAD_CREDENTIALS 다")
    fun wrong_password_returns_bad_credentials() {
        login("owner", "wrong-pass").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("BAD_CREDENTIALS") }
            jsonPath("$.message") { value("아이디 또는 비밀번호가 틀림") }
        }

        assertThat(loginAttempt().failedCount).isEqualTo(1)
    }

    @Test
    @DisplayName("틀린 아이디는 틀린 비밀번호와 같은 401 BAD_CREDENTIALS 이고 실패로 센다")
    fun wrong_username_is_rejected_like_wrong_password() {
        login("stranger", "test-pass").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("BAD_CREDENTIALS") }
            jsonPath("$.message") { value("아이디 또는 비밀번호가 틀림") }
        }

        assertThat(loginAttempt().failedCount).isEqualTo(1)
    }

    @Test
    @DisplayName("5번 연속 틀리면 423 이고 맞는 비밀번호도 15분 동안 거절한다")
    fun locks_after_five_failures() {
        failLoginTimes(5)

        login("owner", "test-pass").andExpect {
            status { isEqualTo(423) }
            jsonPath("$.code") { value("LOGIN_LOCKED") }
        }
        clock.advance(Duration.ofMinutes(15))
        login("owner", "test-pass").andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("잠긴 동안 틀린 비밀번호는 423 이고 실패 횟수와 잠금 해제 시각을 바꾸지 않는다")
    fun locked_login_keeps_failed_count_and_lock() {
        failLoginTimes(5)
        clock.advance(Duration.parse("PT14M59S"))

        login("owner", "wrong-pass").andExpect {
            status { isEqualTo(423) }
            jsonPath("$.code") { value("LOGIN_LOCKED") }
            jsonPath("$.message") { value("로그인 실패가 많아 잠김") }
            jsonPath("$.lockedUntil") { value("2026-11-22T10:15:00+09:00") }
        }

        val attempt = loginAttempt()
        assertThat(attempt.failedCount).isEqualTo(0)
        assertThat(attempt.lockedUntil).isAtSameInstantAs(OffsetDateTime.parse("2026-11-22T10:15:00+09:00"))
    }

    @Test
    @DisplayName("로그인에 성공하면 실패 횟수가 0 으로 돌아간다")
    fun successful_login_resets_failed_count() {
        failLoginTimes(4)

        login("owner", "test-pass").andExpect { status { isOk() } }

        assertThat(loginAttempt().failedCount).isEqualTo(0)
    }

    @Test
    @DisplayName("동시에 들어온 틀린 로그인도 실패 횟수에 모두 들어간다")
    fun concurrent_failures_are_all_counted() {
        val statuses = failLoginConcurrently(3)

        assertThat(statuses).containsExactly(401, 401, 401)
        assertThat(loginAttempt().failedCount).isEqualTo(3)
    }

    @Test
    @DisplayName("access token 은 30분이 지나면 401 이다")
    fun access_token_expires_after_thirty_minutes() {
        val accessToken = accessTokenOf(loginOk())

        clock.advance(Duration.parse("PT30M1S"))

        me(accessToken).andExpect { status { isUnauthorized() } }
    }

    @Test
    @DisplayName("access token 은 발급 뒤 30분이 되는 순간까지 200 이다")
    fun access_token_is_valid_until_thirty_minutes() {
        val accessToken = accessTokenOf(loginOk())

        clock.advance(Duration.ofMinutes(30))

        me(accessToken).andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("refresh 쿠키로 새 access token 과 새 refresh 쿠키를 받는다")
    fun refresh_issues_new_access_token_and_refresh_cookie() {
        val firstCookie = refreshCookieOf(loginOk())
        clock.advance(Duration.ofMinutes(10))

        val result = refresh(firstCookie).andExpect {
            status { isOk() }
            jsonPath("$.expiresAt") { value("2026-11-22T10:40:00+09:00") }
            cookie {
                httpOnly(REFRESH_COOKIE, true)
                sameSite(REFRESH_COOKIE, "Strict")
                path(REFRESH_COOKIE, "/api/auth")
                maxAge(REFRESH_COOKIE, 2591400)
            }
        }.andReturn()

        assertThat(refreshCookieOf(result).value).isNotEqualTo(firstCookie.value)
        me(accessTokenOf(result)).andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("한 번 쓴 refresh 쿠키는 다시 쓸 수 없다")
    fun used_refresh_cookie_is_rejected() {
        val firstCookie = refreshCookieOf(loginOk())
        refresh(firstCookie).andExpect { status { isOk() } }

        refresh(firstCookie).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
        }
    }

    @Test
    @DisplayName("refresh 쿠키 없이 재발급하면 401 INVALID_REFRESH_TOKEN 이고 쿠키를 건드리지 않는다")
    fun refresh_without_cookie_is_rejected() {
        mvc.post("/api/auth/refresh").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
            jsonPath("$.message") { value("로그인 유지 토큰이 없거나 만료됨") }
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
        }
    }

    @Test
    @DisplayName("같은 refresh 쿠키로 동시에 재발급하면 하나만 200 이고 401 응답은 쿠키를 건드리지 않는다")
    fun concurrent_refreshes_with_one_cookie_leave_winner_cookie() {
        val refreshCookie = refreshCookieOf(loginOk())

        val responses = concurrently(2) { refresh(refreshCookie).andReturn().response }

        assertThat(responses).filteredOn { it.status == 200 }.singleElement()
            .satisfies({ assertThat(it.getHeader(HttpHeaders.SET_COOKIE)).startsWith("REFRESH_TOKEN=") })
        assertThat(responses).filteredOn { it.status == 401 }.singleElement()
            .satisfies({ assertThat(it.getHeader(HttpHeaders.SET_COOKIE)).isNull() })
    }

    @Test
    @DisplayName("10일 뒤 재발급한 refresh 쿠키의 Max-Age 는 남은 20일이고 DB 만료 시각은 로그인 기준 그대로다")
    fun rotated_refresh_token_keeps_login_expiry() {
        val firstCookie = refreshCookieOf(loginOk())
        clock.advance(Duration.ofDays(10))

        refresh(firstCookie).andExpect {
            status { isOk() }
            cookie { maxAge(REFRESH_COOKIE, 1728000) }
        }

        val stored = refreshTokenRepository.findAll()
        assertThat(stored).hasSize(1)
        assertThat(stored[0].expiresAt).isAtSameInstantAs(OffsetDateTime.parse("2026-12-22T10:00+09:00"))
    }

    @Test
    @DisplayName("중간에 재발급했어도 로그인부터 30일이 지나면 refresh 는 401 이다")
    fun refresh_expires_thirty_days_after_login_despite_rotation() {
        val firstCookie = refreshCookieOf(loginOk())
        clock.advance(Duration.ofDays(10))
        val rotatedCookie = refreshCookieOf(refresh(firstCookie).andExpect { status { isOk() } }.andReturn())

        clock.advance(Duration.parse("P20DT1S"))

        refresh(rotatedCookie).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
        }
    }

    @Test
    @DisplayName("만료된 access token 헤더가 있어도 재발급은 200 이고 새 access token 을 준다")
    fun refresh_ignores_expired_access_token_header() {
        val login = loginOk()
        clock.advance(Duration.parse("PT30M1S"))

        mvc.post("/api/auth/refresh") {
            cookie(refreshCookieOf(login))
            header(HttpHeaders.AUTHORIZATION, "Bearer ${accessTokenOf(login)}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.expiresAt") { value("2026-11-22T11:00:01+09:00") }
        }
    }

    @Test
    @DisplayName("로그아웃 뒤 같은 refresh 쿠키로 재발급하면 401 이다")
    fun refresh_after_logout_is_rejected() {
        val refreshCookie = refreshCookieOf(loginOk())
        logout(refreshCookie).andExpect { status { isNoContent() } }

        refresh(refreshCookie).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
        }
    }

    @Test
    @DisplayName("로그아웃은 access token 없이 204 이고 refresh 행과 쿠키를 지운다")
    fun logout_deletes_refresh_token_and_cookie() {
        val refreshCookie = refreshCookieOf(loginOk())

        logout(refreshCookie).andExpect {
            status { isNoContent() }
            cookie {
                maxAge(REFRESH_COOKIE, 0)
                path(REFRESH_COOKIE, "/api/auth")
            }
        }

        assertThat(refreshTokenRepository.count()).isZero()
    }

    @Test
    @DisplayName("만료된 access token 헤더가 있어도 로그아웃은 204 이고 refresh 행을 지운다")
    fun logout_ignores_expired_access_token_header() {
        val login = loginOk()
        clock.advance(Duration.parse("PT30M1S"))

        mvc.post("/api/auth/logout") {
            cookie(refreshCookieOf(login))
            header(HttpHeaders.AUTHORIZATION, "Bearer ${accessTokenOf(login)}")
        }.andExpect {
            status { isNoContent() }
            cookie { maxAge(REFRESH_COOKIE, 0) }
        }

        assertThat(refreshTokenRepository.count()).isZero()
    }

    @Test
    @DisplayName("refresh 쿠키 없이 로그아웃해도 204 다")
    fun logout_without_cookie_returns_no_content() {
        mvc.post("/api/auth/logout").andExpect { status { isNoContent() } }
    }

    @Test
    @DisplayName("refresh token 은 30일이 지나면 401 이다")
    fun refresh_token_expires_after_thirty_days() {
        val refreshCookie = refreshCookieOf(loginOk())

        clock.advance(Duration.parse("P30DT1S"))

        refresh(refreshCookie).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
        }
        assertThat(refreshTokenRepository.count()).isZero()
    }

    @Test
    @DisplayName("refresh token 은 발급 뒤 30일이 되는 순간까지 쓸 수 있다")
    fun refresh_token_is_valid_until_thirty_days() {
        val refreshCookie = refreshCookieOf(loginOk())

        clock.advance(Duration.ofDays(30))

        refresh(refreshCookie).andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("DB 에는 refresh token 원문 대신 해시만 저장된다")
    fun stores_only_hash_of_refresh_token() {
        val refreshCookie = refreshCookieOf(loginOk())

        val stored = refreshTokenRepository.findAll()

        assertThat(stored).singleElement()
            .extracting(RefreshToken::tokenHash).asString()
            .matches("[0-9a-f]{64}")
            .isNotEqualTo(refreshCookie.value)
    }

    @Test
    @DisplayName("저장된 해시가 쿠키 값의 jwt-secret 키 HMAC-SHA256 16진수와 같으면 재발급된다")
    fun refresh_accepts_cookie_matching_stored_hmac_hex() {
        refreshTokenRepository.save(
            RefreshToken(
                "0979ef2a7acc852ba31274e8d3da514ca57146e5449eba65f8585a2b6eb628bb",
                OffsetDateTime.parse("2026-12-01T00:00+09:00"),
                OffsetDateTime.parse("2026-11-21T00:00+09:00"),
            ),
        )

        refresh(Cookie(REFRESH_COOKIE, "test-refresh-token")).andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("다른 jwt-secret 키의 HMAC 으로 저장된 refresh 토큰은 401 이다")
    fun refresh_rejects_token_hashed_with_other_key() {
        refreshTokenRepository.save(
            RefreshToken(
                "58e9ea752b6838ad98c012e4230a02995aa72d3844c478e9a498a5e016e07a69",
                OffsetDateTime.parse("2026-12-01T00:00+09:00"),
                OffsetDateTime.parse("2026-11-21T00:00+09:00"),
            ),
        )

        refresh(Cookie(REFRESH_COOKIE, "test-refresh-token")).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
        }
    }

    @Test
    @DisplayName("로그인하면 만료된 refresh 행만 지우고 유효한 행은 남긴다")
    fun login_deletes_only_expired_refresh_tokens() {
        refreshTokenRepository.save(
            RefreshToken(
                EXPIRED_HASH,
                OffsetDateTime.parse("2026-11-22T09:59:59+09:00"),
                OffsetDateTime.parse("2026-10-23T09:59:59+09:00"),
            ),
        )
        refreshTokenRepository.save(
            RefreshToken(
                VALID_HASH,
                OffsetDateTime.parse("2026-12-01T00:00+09:00"),
                OffsetDateTime.parse("2026-11-01T00:00+09:00"),
            ),
        )

        loginOk()

        val storedHashes = refreshTokenRepository.findAll().map(RefreshToken::tokenHash)
        assertThat(storedHashes)
            .hasSize(2)
            .contains(VALID_HASH)
            .doesNotContain(EXPIRED_HASH)
    }

    @Test
    @DisplayName("토큰 없이 /api/accounts 는 401 이다")
    fun accounts_without_token_returns_401() {
        mvc.get("/api/accounts").andExpect {
            status { isUnauthorized() }
            content { string("") }
        }
    }

    @Test
    @DisplayName("유효한 access token 헤더로 /api/accounts 는 200 이다")
    fun accounts_with_valid_access_token_returns_200() {
        val accessToken = accessTokenOf(loginOk())

        mvc.get("/api/accounts") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }.andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("GET /.well-known/oauth-protected-resource 는 토큰 없이 200 이고 데이터 없는 메타데이터만 준다")
    fun protected_resource_metadata_is_public() {
        mvc.get("/.well-known/oauth-protected-resource").andExpect {
            status { isOk() }
            content {
                json(
                    """{"resource":"http://localhost","bearer_methods_supported":["header"],
                    |"tls_client_certificate_bound_access_tokens":false}
                    """.trimMargin(),
                    JsonCompareMode.STRICT,
                )
            }
        }
    }

    @Test
    @DisplayName("같은 키와 발급자로 서명한 만료 전 토큰은 200 이다")
    fun token_signed_with_shared_key_and_issuer_is_accepted() {
        val token = signedToken(TestLogin.JWT_SECRET, "investment-dashboard", Instant.parse("2026-11-22T01:30:00Z"))

        me(token).andExpect {
            status { isOk() }
            jsonPath("$.username") { value("owner") }
        }
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 401 이다")
    fun token_signed_with_other_key_returns_401() {
        val token = signedToken(OTHER_JWT_SECRET, "investment-dashboard", Instant.parse("2026-11-22T01:30:00Z"))

        me(token).andExpect { status { isUnauthorized() } }
    }

    @Test
    @DisplayName("발급자가 다른 토큰은 401 이다")
    fun token_with_other_issuer_returns_401() {
        val token = signedToken(TestLogin.JWT_SECRET, "other-issuer", Instant.parse("2026-11-22T01:30:00Z"))

        me(token).andExpect { status { isUnauthorized() } }
    }

    @Test
    @DisplayName("만료 시각이 없는 토큰은 401 이다")
    fun token_without_expiry_returns_401() {
        val token = signedToken(TestLogin.JWT_SECRET, "investment-dashboard", null)

        me(token).andExpect { status { isUnauthorized() } }
    }

    @Test
    @DisplayName("발급자가 없는 토큰은 401 이다")
    fun token_without_issuer_returns_401() {
        val token = signedToken(TestLogin.JWT_SECRET, null, Instant.parse("2026-11-22T01:30:00Z"))

        me(token).andExpect { status { isUnauthorized() } }
    }

    @Test
    @DisplayName("서명이 없는 alg none 토큰은 401 이다")
    fun token_with_alg_none_returns_401() {
        val claims = JWTClaimsSet.Builder()
            .issuer("investment-dashboard")
            .subject("owner")
            .issueTime(Date.from(Instant.parse("2026-11-22T01:00:00Z")))
            .expirationTime(Date.from(Instant.parse("2026-11-22T01:30:00Z")))
            .build()

        me(PlainJWT(claims).serialize()).andExpect { status { isUnauthorized() } }
    }

    @Test
    @DisplayName("같은 키로 HS512 서명한 토큰은 401 이다")
    fun token_signed_hs512_with_same_key_returns_401() {
        val token = signedToken(
            TestLogin.JWT_SECRET,
            "investment-dashboard",
            Instant.parse("2026-11-22T01:30:00Z"),
            MacAlgorithm.HS512,
        )

        me(token).andExpect { status { isUnauthorized() } }
    }

    @Test
    @DisplayName("미인증 요청은 세션 쿠키를 만들지 않는다")
    fun unauthenticated_request_does_not_create_session() {
        val result = mvc.get("/api/accounts").andExpect { status { isUnauthorized() } }.andReturn()

        assertThat(result.request.getSession(false)).isNull()
    }

    @Test
    @DisplayName("GET /login 은 401 이다")
    fun get_login_returns_401() {
        mvc.get("/login").andExpect { status { isUnauthorized() } }
    }

    @Test
    @DisplayName("기동 시 임시 비밀번호 사용자(UserDetailsService)를 만들지 않는다")
    fun does_not_create_generated_password_user() {
        assertThat(context.getBeanNamesForType(UserDetailsService::class.java)).isEmpty()
    }

    private fun login(username: String, password: String): ResultActionsDsl = mvc.post("/api/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"username":"$username","password":"$password"}"""
    }

    private fun loginOk(): MvcResult = login("owner", "test-pass").andExpect { status { isOk() } }.andReturn()

    private fun failLoginTimes(times: Int) {
        repeat(times) { login("owner", "wrong-pass").andExpect { status { isUnauthorized() } } }
    }

    private fun failLoginConcurrently(times: Int): List<Int> =
        concurrently(times) { login("owner", "wrong-pass").andReturn().response.status }

    private fun <T> concurrently(times: Int, request: () -> T): List<T> =
        Executors.newFixedThreadPool(times).use { pool ->
            pool.invokeAll(List(times) { Callable(request) }).map { it.get() }
        }

    private fun refresh(cookie: Cookie): ResultActionsDsl = mvc.post("/api/auth/refresh") { cookie(cookie) }

    private fun logout(cookie: Cookie): ResultActionsDsl = mvc.post("/api/auth/logout") { cookie(cookie) }

    private fun me(accessToken: String): ResultActionsDsl = mvc.get("/api/auth/me") {
        header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
    }

    private fun accessTokenOf(result: MvcResult): String =
        JsonPath.read(result.response.getContentAsString(StandardCharsets.UTF_8), "$.accessToken")

    private fun refreshCookieOf(result: MvcResult): Cookie =
        Cookie(REFRESH_COOKIE, checkNotNull(result.response.getCookie(REFRESH_COOKIE)).value)

    private fun loginAttempt(): LoginAttempt = checkNotNull(loginAttemptRepository.findByIdOrNull(1))

    private fun signedToken(
        base64Secret: String,
        issuer: String?,
        expiresAt: Instant?,
        algorithm: MacAlgorithm = MacAlgorithm.HS256,
    ): String {
        val key = SecretKeySpec(Base64.getDecoder().decode(base64Secret), "HmacSHA256")
        val claims = JwtClaimsSet.builder()
            .subject("owner")
            .issuedAt(Instant.parse("2026-11-22T01:00:00Z"))
        issuer?.let { claims.issuer(it) }
        expiresAt?.let { claims.expiresAt(it) }
        val header = JwsHeader.with(algorithm).build()
        return NimbusJwtEncoder.withSecretKey(key).algorithm(algorithm).build()
            .encode(JwtEncoderParameters.from(header, claims.build()))
            .tokenValue
    }

    private companion object {
        const val REFRESH_COOKIE = "REFRESH_TOKEN"

        const val EXPIRED_HASH = "1111111111111111111111111111111111111111111111111111111111111111"

        const val VALID_HASH = "2222222222222222222222222222222222222222222222222222222222222222"

        val OTHER_JWT_SECRET: String =
            Base64.getEncoder().encodeToString("other-test-jwt-signing-key-not-a-secret".toByteArray())
    }
}
