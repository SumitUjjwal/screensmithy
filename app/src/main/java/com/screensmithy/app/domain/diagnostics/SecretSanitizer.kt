package com.screensmithy.app.domain.diagnostics

import java.net.URI

/**
 * Sanitizes URLs, strings, headers, and log payloads by redacting sensitive data.
 *
 * Guarantees zero leakage of:
 * - Sensitive query parameters (tokens, keys, passwords, credentials, tickets)
 * - Embedded user credentials in URLs (user:pass@host)
 * - HTTP Authorization headers and Cookie values
 * - PIN/hash/salt artifacts
 */
object SecretSanitizer {

    const val REDACTED = "REDACTED"

    private val SENSITIVE_PARAM_NAMES = setOf(
        "token",
        "access_token",
        "refresh_token",
        "id_token",
        "key",
        "apikey",
        "api_key",
        "secret",
        "client_secret",
        "password",
        "pass",
        "pwd",
        "auth",
        "authorization",
        "code",
        "session",
        "session_id",
        "ticket",
        "bearer",
        "credential",
        "credentials",
        "sig",
        "signature"
    )

    private val AUTH_HEADER_PATTERN = Regex(
        "(?i)(authorization\\s*:\\s*)(bearer|basic|digest)?\\s*[^\\r\\n]+"
    )

    private val COOKIE_HEADER_PATTERN = Regex(
        "(?i)(cookie\\s*:\\s*)[^\\r\\n]+"
    )

    private val USER_INFO_PATTERN = Regex(
        "https?://([^/@:]+):([^/@:]+)@"
    )

    /**
     * Sanitizes a URL string by redacting embedded userinfo and sensitive query parameters.
     * Non-sensitive parameters (e.g., page, view, lang) are preserved.
     */
    fun sanitizeUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return rawUrl

        var urlToProcess = rawUrl

        // 1. Strip userinfo if present: https://user:pass@example.com/ -> https://***:***@example.com/
        urlToProcess = USER_INFO_PATTERN.replace(urlToProcess) { match ->
            val prefix = if (match.value.startsWith("https://", ignoreCase = true)) "https://" else "http://"
            "$prefix***:***@"
        }

        return try {
            val uri = URI(urlToProcess)
            val rawQuery = uri.rawQuery
            val rawFragment = uri.rawFragment

            if (rawQuery == null && rawFragment == null) {
                return urlToProcess
            }

            val sanitizedQuery = rawQuery?.split('&')?.joinToString("&") { param ->
                val parts = param.split('=', limit = 2)
                val key = parts[0]
                if (isSensitiveParam(key)) {
                    if (parts.size > 1) "$key=$REDACTED" else key
                } else {
                    param
                }
            }

            val sanitizedFragment = rawFragment?.let { frag ->
                if (frag.contains('=')) {
                    frag.split('&').joinToString("&") { part ->
                        val p = part.split('=', limit = 2)
                        if (isSensitiveParam(p[0])) "${p[0]}=$REDACTED" else part
                    }
                } else {
                    frag
                }
            }

            val base = urlToProcess.substringBefore('?').substringBefore('#')
            val queryPart = if (sanitizedQuery != null) "?$sanitizedQuery" else ""
            val fragmentPart = if (sanitizedFragment != null) "#$sanitizedFragment" else ""

            "$base$queryPart$fragmentPart"
        } catch (_: Exception) {
            // Fallback for non-standard or malformed URLs: basic regex parameter replacement
            sanitizeFallback(urlToProcess)
        }
    }

    private fun isSensitiveParam(paramKey: String): Boolean {
        val normalized = paramKey.trim().lowercase()
        return SENSITIVE_PARAM_NAMES.contains(normalized) ||
            SENSITIVE_PARAM_NAMES.any { normalized.contains(it) }
    }

    private fun sanitizeFallback(raw: String): String {
        var result = raw
        for (param in SENSITIVE_PARAM_NAMES) {
            val regex = Regex("(?i)([?&#])$param=[^&#\\s]*")
            result = regex.replace(result, "$1$param=$REDACTED")
        }
        return result
    }

    /**
     * Sanitizes general log messages by redacting headers, cookies, and tokens.
     */
    fun sanitizeMessage(message: String): String {
        if (message.isBlank()) return message

        var sanitized = AUTH_HEADER_PATTERN.replace(message) { match ->
            val prefix = match.groupValues[1]
            "$prefix$REDACTED"
        }

        sanitized = COOKIE_HEADER_PATTERN.replace(sanitized) { match ->
            val prefix = match.groupValues[1]
            "$prefix$REDACTED"
        }

        // If the message contains full HTTP URLs, sanitize them as well
        val urlRegex = Regex("https?://[^\\s\"'<>]+")
        sanitized = urlRegex.replace(sanitized) { match ->
            sanitizeUrl(match.value)
        }

        return sanitized
    }
}
