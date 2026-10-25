package com.screensmithy.app.domain.url

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Pure Kotlin URL validation and normalization service.
 * Enforces strict scheme restrictions, host boundary requirements, and security constraints.
 */
object UrlValidator {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    // Pattern for checking whether input looks like an unambiguous hostname or IP without scheme
    // e.g. "example.com", "sub.domain.org", "192.168.1.1", "kiosk-tv:8080/path"
    private val BARE_HOST_PATTERN = Regex(
        "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}(:\\d{1,5})?(/.*)?$"
    )
    private val IPV4_PATTERN = Regex(
        "^(\\d{1,3}\\.){3}\\d{1,3}(:\\d{1,5})?(/.*)?$"
    )
    private val INTRANET_HOST_PATTERN = Regex(
        "^[a-zA-Z0-9\\-_]+(:\\d{1,5})?(/.*)?$"
    )

    /**
     * Validates and normalizes user or configuration URL input.
     *
     * @param rawInput String input to evaluate.
     * @return [UrlValidationResult.Valid] or [UrlValidationResult.Invalid].
     */
    fun validate(rawInput: String?): UrlValidationResult {
        if (rawInput.isNullOrBlank()) {
            return UrlValidationResult.Invalid(
                error = UrlValidationError.BlankInput,
                userMessage = "URL cannot be empty."
            )
        }

        val trimmed = rawInput.trim()

        // 1. Check for unsafe or dangerous prefixes before any normalization
        if (trimmed.contains(' ')) {
            return UrlValidationResult.Invalid(
                error = UrlValidationError.MalformedUri("URL contains invalid whitespace"),
                userMessage = "The URL contains spaces. Please enter a valid web address."
            )
        }

        // 2. Normalize unambiguous bare hostnames to HTTPS
        val candidateUrl = normalizeSchemeIfBare(trimmed)

        // 3. Early check for explicit unsupported schemes (e.g. javascript:, file:, data:, intent:)
        val colonIndex = candidateUrl.indexOf(':')
        val firstSlashIndex = candidateUrl.indexOf('/')
        if (colonIndex != -1 && (firstSlashIndex == -1 || colonIndex < firstSlashIndex)) {
            val potentialScheme = candidateUrl.substring(0, colonIndex).lowercase(Locale.ROOT)
            if (potentialScheme.isNotEmpty() && potentialScheme.all { it.isLetter() || it.isDigit() || it == '+' || it == '-' || it == '.' }) {
                if (potentialScheme !in ALLOWED_SCHEMES) {
                    return UrlValidationResult.Invalid(
                        error = UrlValidationError.UnsupportedScheme(potentialScheme),
                        userMessage = "Unsupported scheme '$potentialScheme://'. Only HTTP and HTTPS are permitted."
                    )
                }
            }
        }

        // 4. Parse URI
        val parsedUri: URI = try {
            URI(candidateUrl)
        } catch (e: URISyntaxException) {
            return UrlValidationResult.Invalid(
                error = UrlValidationError.MalformedUri(e.reason ?: "Syntax error"),
                userMessage = "The entered web address is malformed."
            )
        }

        // 4. Validate Scheme
        val scheme = parsedUri.scheme?.lowercase(Locale.ROOT)
        if (scheme == null) {
            return UrlValidationResult.Invalid(
                error = UrlValidationError.UnsupportedScheme(""),
                userMessage = "Missing URL scheme. Use http:// or https://"
            )
        }

        if (scheme !in ALLOWED_SCHEMES) {
            return UrlValidationResult.Invalid(
                error = UrlValidationError.UnsupportedScheme(scheme),
                userMessage = "Unsupported scheme '$scheme://'. Only HTTP and HTTPS are permitted."
            )
        }

        // 5. Reject embedded credentials (security requirement)
        val userInfo = parsedUri.userInfo
        if (!userInfo.isNullOrBlank()) {
            return UrlValidationResult.Invalid(
                error = UrlValidationError.EmbeddedCredentialsNotAllowed(userInfo),
                userMessage = "Embedded credentials (user:password@) are not permitted in kiosk URLs."
            )
        }

        // 6. Validate Host
        val rawHost = parsedUri.host
        if (rawHost.isNullOrBlank()) {
            return UrlValidationResult.Invalid(
                error = UrlValidationError.MissingHost,
                userMessage = "The URL does not specify a valid host address."
            )
        }

        val normalizedHost = rawHost.lowercase(Locale.ROOT)
        if (!isValidHostFormat(normalizedHost)) {
            return UrlValidationResult.Invalid(
                error = UrlValidationError.InvalidHost(rawHost, "Hostname characters or format are invalid"),
                userMessage = "The host address '$rawHost' is invalid."
            )
        }

        // 7. Validate Port
        val port = parsedUri.port
        if (port != -1 && port !in 1..65535) {
            return UrlValidationResult.Invalid(
                error = UrlValidationError.InvalidPort(port),
                userMessage = "Port number $port is outside valid range (1-65535)."
            )
        }

        // 8. Construct canonical normalized URL
        val canonicalPort = if (port == -1 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)) {
            null
        } else {
            port
        }

        val normalizedUrl = buildCanonicalUrl(parsedUri, scheme, normalizedHost, canonicalPort)
        val isHttp = scheme == "http"

        return UrlValidationResult.Valid(
            normalizedUrl = normalizedUrl,
            scheme = scheme,
            host = normalizedHost,
            port = canonicalPort,
            hasInsecureHttpWarning = isHttp,
            warningMessage = if (isHttp) {
                "HTTP connections do not provide transport encryption. HTTPS is strongly recommended."
            } else {
                null
            }
        )
    }

    /**
     * Determines whether an input without scheme is unambiguously a bare hostname and prefixes "https://".
     * If ambiguous or containing colons/slashes indicative of schemes/paths, leaves as-is.
     */
    private fun normalizeSchemeIfBare(input: String): String {
        // If input already has a scheme separator (like "http:", "javascript:", "file:"), return as-is
        val colonIndex = input.indexOf(':')
        val firstSlashIndex = input.indexOf('/')

        if (colonIndex != -1 && (firstSlashIndex == -1 || colonIndex < firstSlashIndex)) {
            // Might be scheme (e.g. "http://", "file://") or host with port (e.g. "example.com:8080")
            // If the part before colon contains dots or looks like an IP/host, and part after colon starts with digits:
            val beforeColon = input.substring(0, colonIndex)
            val afterColon = input.substring(colonIndex + 1)
            val isPortAfterColon = afterColon.takeWhile { it.isDigit() }.isNotEmpty()

            if (isPortAfterColon && (beforeColon.contains('.') || INTRANET_HOST_PATTERN.matches(beforeColon))) {
                return "https://$input"
            }
            return input
        }

        // Starts with "//" or "/"
        if (input.startsWith("/")) {
            return input
        }

        // Check if unambiguous bare domain, IPv4, or intranet host
        if (BARE_HOST_PATTERN.matches(input) || IPV4_PATTERN.matches(input) || INTRANET_HOST_PATTERN.matches(input)) {
            return "https://$input"
        }

        return input
    }

    private fun isValidHostFormat(host: String): Boolean {
        // IPv6 bracketed address
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.length > 2
        }

        // IPv4 or hostname
        // Must contain valid characters: alphanumeric, hyphens, dots
        return host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }

    private fun buildCanonicalUrl(
        uri: URI,
        scheme: String,
        host: String,
        port: Int?
    ): String {
        val portString = if (port != null) ":$port" else ""
        val rawPath = uri.rawPath.ifEmpty { "/" }
        val rawQuery = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
        val rawFragment = if (uri.rawFragment != null) "#${uri.rawFragment}" else ""

        return "$scheme://$host$portString$rawPath$rawQuery$rawFragment"
    }
}
