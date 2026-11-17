package com.eachother.app.core.network

import com.eachother.app.core.security.SessionStore
import com.eachother.app.core.security.SharingSettingsStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun createApi(
        baseUrl: String,
        sessionStore: SessionStore,
        sharingSettingsStore: SharingSettingsStore? = null
    ): EachOtherApi {
        val contentType = "application/json".toMediaType()
        val unauthenticatedRetrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        val refreshApi = unauthenticatedRetrofit.create(EachOtherApi::class.java)

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(sessionStore))
            .authenticator(TokenAuthenticator(sessionStore, sharingSettingsStore) { refreshApi })
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(EachOtherApi::class.java)
    }
}
