package com.example.kmd_reader.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

object NetworkModule {
    const val EmulatorBaseUrl = "http://10.0.2.2:3000/"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun createApi(
        baseUrl: String = EmulatorBaseUrl,
        enableLogging: Boolean = true
    ): KmdCommunityApi {
        val client = OkHttpClient.Builder()
            .apply {
                if (enableLogging) {
                    addInterceptor(
                        HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)
                    )
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KmdCommunityApi::class.java)
    }
}
