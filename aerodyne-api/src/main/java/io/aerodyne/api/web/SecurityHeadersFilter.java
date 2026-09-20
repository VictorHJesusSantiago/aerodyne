package io.aerodyne.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds browser-facing security headers for every API response.
 *
 * <p>Aerodyne is a JSON/text API and does not use cookie-authenticated browser sessions, so CSRF
 * protection is handled by avoiding ambient credentials rather than token validation. These headers
 * still harden documentation/error pages and any future browser surface by default.
 */
@Component
public final class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; "
                    + "base-uri 'none'; "
                    + "frame-ancestors 'none'; "
                    + "form-action 'none'; "
                    + "img-src 'self' data:; "
                    + "style-src 'self' 'unsafe-inline'; "
                    + "script-src 'self'; "
                    + "connect-src 'self'";
    static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()";
    private static final String STRICT_TRANSPORT_SECURITY =
            "max-age=31536000; includeSubDomains";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Cache-Control", "no-store");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY);
        }
        filterChain.doFilter(request, response);
    }
}
