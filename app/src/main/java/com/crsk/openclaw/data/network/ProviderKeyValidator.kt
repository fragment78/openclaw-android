package com.crsk.openclaw.data.network

import com.crsk.openclaw.data.providers.AiProvider
import com.crsk.openclaw.data.providers.KeyAuthStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed interface KeyValidationResult {
    data object Valid : KeyValidationResult
    data object InvalidKey : KeyValidationResult
    data class NetworkError(val message: String) : KeyValidationResult
    data class UnexpectedStatus(val code: Int) : KeyValidationResult
}

@Singleton
class ProviderKeyValidator @Inject constructor() {

    suspend fun validate(
        provider: AiProvider,
        apiKey: String
    ): KeyValidationResult = withContext(Dispatchers.IO) {

        val key = apiKey.trim()

        if (key.isBlank()) {
    return@withContext KeyValidationResult.InvalidKey
}

if (provider.id != "gem" && !key.matches(provider.keyPattern)) {
    return@withContext KeyValidationResult.InvalidKey
}

        val isGemini = provider.id == "gem"

        val url = if (isGemini) {
            "https://generativelanguage.googleapis.com/v1beta/models"
        } else {
            "${provider.baseUrl}/models"
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"

            if (isGemini) {
                setRequestProperty("x-goog-api-key", key)
            } else {
                when (provider.authStyle) {
                    KeyAuthStyle.BEARER ->
                        setRequestProperty("Authorization", "Bearer $key")

                    KeyAuthStyle.ANTHROPIC -> {
                        setRequestProperty("x-api-key", key)
                        setRequestProperty(
                            "anthropic-version",
                            "2023-06-01"
                        )
                    }
                }
            }

            setRequestProperty("Accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        try {
            when (val code = conn.responseCode) {
                200 -> KeyValidationResult.Valid
                400, 401, 403 ->
                    KeyValidationResult.InvalidKey
                else ->
                    KeyValidationResult.UnexpectedStatus(code)
            }
        } catch (e: Exception) {
            KeyValidationResult.NetworkError(
                e.message ?: "Couldn't reach ${provider.displayName}"
            )
        } finally {
            conn.disconnect()
        }
    }
}
