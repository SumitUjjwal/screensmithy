package com.screensmithy.app.domain.url

import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import java.net.URI
import java.util.Locale

/**
 * Result of evaluating whether a navigation request is permitted by [NavigationPolicy].
 */
sealed interface NavigationDecision {
    /**
     * The navigation destination is permitted.
     */
    data class Allowed(
        val targetUri: URI,
        val reason: String
    ) : NavigationDecision

    /**
     * The navigation destination was blocked by policy or unsafe scheme.
     */
    data class Blocked(
        val rawUrl: String,
        val reason: String,
        val violation: NavigationViolation
    ) : NavigationDecision
}

enum class NavigationViolation {
    UNSUPPORTED_SCHEME,
    HOST_NOT_ALLOWED,
    MALFORMED_URL
}

/**
 * Pure domain evaluator for WebView navigation and host boundary enforcement.
 * Operates without Android framework UI or WebView dependencies.
 */
object NavigationPolicyEvaluator {

    /**
     * Evaluates whether navigation to [targetUrl] is allowed under [policy] given [configuredUrl].
     *
     * @param targetUrl The destination URL being requested by user interaction or redirect.
     * @param configuredUrl The primary entry-point URL configured for the kiosk.
     * @param policy The active [NavigationPolicy].
     * @return [NavigationDecision.Allowed] or [NavigationDecision.Blocked].
     */
    fun evaluate(
        targetUrl: String,
        configuredUrl: String?,
        policy: NavigationPolicy
    ): NavigationDecision {
        // 1. Validate destination URL format and scheme
        val validation = UrlValidator.validate(targetUrl)
        if (validation is UrlValidationResult.Invalid) {
            val violation = when (validation.error) {
                is UrlValidationError.UnsupportedScheme -> NavigationViolation.UNSUPPORTED_SCHEME
                else -> NavigationViolation.MALFORMED_URL
            }
            return NavigationDecision.Blocked(
                rawUrl = targetUrl,
                reason = validation.userMessage,
                violation = violation
            )
        }

        val validTarget = validation as UrlValidationResult.Valid
        val targetHost = validTarget.host
        val targetUri = URI(validTarget.normalizedUrl)

        // 2. Evaluate based on NavigationMode
        return when (policy.mode) {
            NavigationMode.ANY_HTTP_HOST -> {
                NavigationDecision.Allowed(
                    targetUri = targetUri,
                    reason = "Destination permitted under open navigation policy."
                )
            }

            NavigationMode.CONFIGURED_HOST_ONLY -> {
                val configuredHost = configuredUrl?.let {
                    (UrlValidator.validate(it) as? UrlValidationResult.Valid)?.host
                }

                val permittedHosts = buildSet {
                    if (!configuredHost.isNullOrBlank()) {
                        add(configuredHost.lowercase(Locale.ROOT))
                    }
                    policy.approvedRedirectHosts.forEach { host ->
                        val clean = host.trim().lowercase(Locale.ROOT)
                        if (clean.isNotEmpty()) add(clean)
                    }
                }

                if (isHostPermitted(targetHost, permittedHosts, policy.includeSubdomains)) {
                    NavigationDecision.Allowed(
                        targetUri = targetUri,
                        reason = "Host '$targetHost' matches configured entry host or approved redirect."
                    )
                } else {
                    NavigationDecision.Blocked(
                        rawUrl = targetUrl,
                        reason = "Host '$targetHost' is not the configured host or an approved redirect host.",
                        violation = NavigationViolation.HOST_NOT_ALLOWED
                    )
                }
            }

            NavigationMode.ALLOWLIST_ONLY -> {
                val permittedHosts = policy.trustedHosts.map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotEmpty() }
                    .toSet()

                if (isHostPermitted(targetHost, permittedHosts, policy.includeSubdomains)) {
                    NavigationDecision.Allowed(
                        targetUri = targetUri,
                        reason = "Host '$targetHost' is in the trusted allowlist."
                    )
                } else {
                    NavigationDecision.Blocked(
                        rawUrl = targetUrl,
                        reason = "Host '$targetHost' is not included in the trusted allowlist.",
                        violation = NavigationViolation.HOST_NOT_ALLOWED
                    )
                }
            }
        }
    }

    /**
     * Checks if [targetHost] matches any host in [allowedHosts] with strict boundary checks.
     * Prevents suffix spoofing attacks (e.g. "example.com.attacker.com" matching "example.com").
     */
    fun isHostPermitted(
        targetHost: String,
        allowedHosts: Set<String>,
        includeSubdomains: Boolean
    ): Boolean {
        val normalizedTarget = targetHost.trim().lowercase(Locale.ROOT)

        for (allowed in allowedHosts) {
            val normalizedAllowed = allowed.trim().lowercase(Locale.ROOT)
            if (normalizedAllowed.isEmpty()) continue

            // Exact match
            if (normalizedTarget == normalizedAllowed) {
                return true
            }

            // Subdomain match: only if explicitly enabled AND separated by a dot boundary
            if (includeSubdomains && normalizedTarget.endsWith(".$normalizedAllowed")) {
                return true
            }
        }

        return false
    }
}
