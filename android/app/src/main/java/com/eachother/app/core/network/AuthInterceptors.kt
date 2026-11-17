package com.eachother.app.core.network

import com.eachother.app.core.security.SessionStore
import com.eachother.app.core.security.SharingSettingsStore
import com.eachother.app.domain.model.AppRole
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class AuthInterceptor(
    private val sessionStore: SessionStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        if (isPublicEndpoint(path)) {
            return chain.proceed(original)
        }
        val token = runBlocking { sessionStore.getSession().accessToken }
        val request = if (!token.isNullOrBlank()) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }

    private fun isPublicEndpoint(path: String): Boolean {
        return path.startsWith("/v1/auth/admin/register") ||
            path.startsWith("/v1/auth/admin/challenge") ||
            path.startsWith("/v1/auth/admin/verify") ||
            path.startsWith("/v1/auth/admin/recover") ||
            path.startsWith("/v1/auth/admin/devices/enroll") ||
            path.startsWith("/v1/auth/refresh") ||
            path.startsWith("/v1/invitations/validate") ||
            path.startsWith("/v1/devices/enroll") ||
            path == "/v1/health"
    }
}

/**
 * Coordinates concurrent HTTP 401 responses under a single lock so multiple parallel requests
 * never race to consume the same single-use refresh token and trip backend reuse detection.
 */
class TokenAuthenticator(
    private val sessionStore: SessionStore,
    private val sharingSettingsStore: SharingSettingsStore? = null,
    private val refreshApiProvider: () -> EachOtherApi
) : Authenticator {
    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) {
            return null
        }

        val failedAuthHeader = response.request.header("Authorization")

        synchronized(refreshLock) {
            val latestSession = runBlocking { sessionStore.getSession() }
            if (latestSession.isRevoked) {
                return null
            }

            // If another thread already rotated the session after this request was sent, retry with the fresh token
            val currentAccess = latestSession.accessToken
            if (!currentAccess.isNullOrBlank() && "Bearer $currentAccess" != failedAuthHeader) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentAccess")
                    .build()
            }

            val refreshToken = latestSession.refreshToken ?: return null
            val refreshResponse = runBlocking {
                try {
                    refreshApiProvider().refreshToken(RefreshRequest(refreshToken))
                } catch (_: Exception) {
                    null
                }
            } ?: return null

            if (!refreshResponse.isSuccessful) {
                if (refreshResponse.code() == 401) {
                    runBlocking {
                        if (latestSession.role == AppRole.CONNECTED) {
                            sharingSettingsStore?.clear()
                        }
                        sessionStore.saveSession(
                            latestSession.copy(
                                accessToken = null,
                                refreshToken = null,
                                isRevoked = true
                            )
                        )
                    }
                }
                return null
            }

            val body = refreshResponse.body() ?: return null
            runBlocking {
                sessionStore.saveSession(
                    latestSession.copy(
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken,
                        isRevoked = false
                    )
                )
            }

            return response.request.newBuilder()
                .header("Authorization", "Bearer ${body.accessToken}")
                .build()
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
