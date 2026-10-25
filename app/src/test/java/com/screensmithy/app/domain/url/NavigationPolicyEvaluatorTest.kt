package com.screensmithy.app.domain.url

import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyEvaluatorTest {

    @Test
    fun anyHttpHost_permitsValidHttpAndHttpsDestinations() {
        val policy = NavigationPolicy(mode = NavigationMode.ANY_HTTP_HOST)

        val decision1 = NavigationPolicyEvaluator.evaluate(
            targetUrl = "https://external.news.com/feed",
            configuredUrl = "https://kiosk.example.com",
            policy = policy
        )
        assertTrue(decision1 is NavigationDecision.Allowed)

        val decision2 = NavigationPolicyEvaluator.evaluate(
            targetUrl = "http://intranet.weather.lan",
            configuredUrl = "https://kiosk.example.com",
            policy = policy
        )
        assertTrue(decision2 is NavigationDecision.Allowed)
    }

    @Test
    fun anyHttpHost_blocksUnsafeSchemes() {
        val policy = NavigationPolicy(mode = NavigationMode.ANY_HTTP_HOST)

        val decision = NavigationPolicyEvaluator.evaluate(
            targetUrl = "javascript:alert(1)",
            configuredUrl = "https://kiosk.example.com",
            policy = policy
        )
        assertTrue(decision is NavigationDecision.Blocked)
        assertEquals(NavigationViolation.UNSUPPORTED_SCHEME, (decision as NavigationDecision.Blocked).violation)
    }

    @Test
    fun configuredHostOnly_allowsMatchingHostAndPaths() {
        val policy = NavigationPolicy(mode = NavigationMode.CONFIGURED_HOST_ONLY)

        val decision = NavigationPolicyEvaluator.evaluate(
            targetUrl = "https://kiosk.example.com:8443/new/page?ref=home",
            configuredUrl = "https://kiosk.example.com/welcome",
            policy = policy
        )
        assertTrue(decision is NavigationDecision.Allowed)
    }

    @Test
    fun configuredHostOnly_allowsApprovedRedirectHosts() {
        val policy = NavigationPolicy(
            mode = NavigationMode.CONFIGURED_HOST_ONLY,
            approvedRedirectHosts = setOf("idp.sso-provider.com", "auth.company.lan")
        )

        val decision = NavigationPolicyEvaluator.evaluate(
            targetUrl = "https://idp.sso-provider.com/saml/login",
            configuredUrl = "https://kiosk.example.com",
            policy = policy
        )
        assertTrue(decision is NavigationDecision.Allowed)
    }

    @Test
    fun configuredHostOnly_blocksUnapprovedHosts() {
        val policy = NavigationPolicy(mode = NavigationMode.CONFIGURED_HOST_ONLY)

        val decision = NavigationPolicyEvaluator.evaluate(
            targetUrl = "https://unauthorized-attacker.com/leak",
            configuredUrl = "https://kiosk.example.com",
            policy = policy
        )
        assertTrue(decision is NavigationDecision.Blocked)
        assertEquals(NavigationViolation.HOST_NOT_ALLOWED, (decision as NavigationDecision.Blocked).violation)
    }

    @Test
    fun hostMatching_subdomainsDisabledByDefault() {
        val policy = NavigationPolicy(
            mode = NavigationMode.CONFIGURED_HOST_ONLY,
            includeSubdomains = false
        )

        val decision = NavigationPolicyEvaluator.evaluate(
            targetUrl = "https://sub.kiosk.example.com",
            configuredUrl = "https://kiosk.example.com",
            policy = policy
        )
        assertTrue(decision is NavigationDecision.Blocked)
        assertEquals(NavigationViolation.HOST_NOT_ALLOWED, (decision as NavigationDecision.Blocked).violation)
    }

    @Test
    fun hostMatching_subdomainsPermittedWhenExplicitlyEnabled() {
        val policy = NavigationPolicy(
            mode = NavigationMode.CONFIGURED_HOST_ONLY,
            includeSubdomains = true
        )

        val decision = NavigationPolicyEvaluator.evaluate(
            targetUrl = "https://sub.kiosk.example.com/status",
            configuredUrl = "https://kiosk.example.com",
            policy = policy
        )
        assertTrue(decision is NavigationDecision.Allowed)
    }

    @Test
    fun hostMatching_strictBoundaryChecksPreventSuffixAttacks() {
        val policy = NavigationPolicy(
            mode = NavigationMode.CONFIGURED_HOST_ONLY,
            includeSubdomains = true
        )

        // Attacker creates a domain like: kiosk.example.com.attacker.com
        val suffixAttack = NavigationPolicyEvaluator.evaluate(
            targetUrl = "https://kiosk.example.com.attacker.com/evil",
            configuredUrl = "https://kiosk.example.com",
            policy = policy
        )
        assertTrue(suffixAttack is NavigationDecision.Blocked)

        // Attacker creates domain prefixing without dot: evilkiosk.example.com
        val prefixAttack = NavigationPolicyEvaluator.evaluate(
            targetUrl = "https://notkiosk.example.com",
            configuredUrl = "https://kiosk.example.com",
            policy = policy
        )
        assertTrue(prefixAttack is NavigationDecision.Blocked)
    }

    @Test
    fun allowlistOnly_permitsExplicitTrustedHosts() {
        val policy = NavigationPolicy(
            mode = NavigationMode.ALLOWLIST_ONLY,
            trustedHosts = setOf("portal.corp", "metrics.internal.lan")
        )

        val allowed = NavigationPolicyEvaluator.evaluate(
            targetUrl = "https://metrics.internal.lan:9090/dash",
            configuredUrl = "https://ignored.com",
            policy = policy
        )
        assertTrue(allowed is NavigationDecision.Allowed)

        val blocked = NavigationPolicyEvaluator.evaluate(
            targetUrl = "https://portal.corp.evil.com",
            configuredUrl = "https://ignored.com",
            policy = policy
        )
        assertTrue(blocked is NavigationDecision.Blocked)
    }
}
