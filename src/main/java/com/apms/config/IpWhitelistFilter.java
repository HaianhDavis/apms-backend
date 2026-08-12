package com.apms.config;

import com.apms.domain.admin.service.AdminIpWhitelistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * IP Whitelist enforcement filter.
 *
 * When the whitelist feature is ENABLED (stored in admin_settings), this filter:
 * - Extracts the client IP (handles X-Forwarded-For for proxies)
 * - Always allows: 127.0.0.1, ::1 (localhost) for local development
 * - Always allows: /api/v1/auth/** so admins don't get locked out
 * - Rejects non-whitelisted IPs with HTTP 403
 *
 * When the whitelist feature is DISABLED, all requests pass through normally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpWhitelistFilter extends OncePerRequestFilter {

    private final AdminIpWhitelistService ipWhitelistService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Always allow auth endpoints to prevent lockout
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/auth/") || path.startsWith("/ws/") || path.equals("/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only enforce when whitelist feature is enabled
        if (!ipWhitelistService.isWhitelistEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = extractClientIp(request);

        // Always allow localhost (critical for development and health checks)
        if (isLocalhost(clientIp)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check against DB-stored whitelist
        List<String> allowedIps = ipWhitelistService.getEnabledIpAddresses();
        boolean allowed = allowedIps.stream().anyMatch(allowed_ip -> ipMatches(clientIp, allowed_ip));

        if (!allowed) {
            log.warn("IP whitelist: Blocked request from {} to {}", clientIp, path);
            sendForbiddenResponse(response, clientIp);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String extractClientIp(HttpServletRequest request) {
        // Handle reverse proxy / load balancer
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For can be comma-separated; take the first (original client)
            return forwarded.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }

    private boolean isLocalhost(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }

    /**
     * Checks if clientIp matches the allowedIp pattern.
     * Supports exact IPv4 match and simple CIDR /prefix matching.
     */
    private boolean ipMatches(String clientIp, String allowedPattern) {
        if (allowedPattern.contains("/")) {
            return ipMatchesCidr(clientIp, allowedPattern);
        }
        return clientIp.equals(allowedPattern);
    }

    private boolean ipMatchesCidr(String clientIp, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String networkAddress = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            long clientLong = ipToLong(clientIp);
            long networkLong = ipToLong(networkAddress);
            long mask = prefixLength == 0 ? 0L : (-1L << (32 - prefixLength));

            return (clientLong & mask) == (networkLong & mask);
        } catch (Exception e) {
            log.warn("IP whitelist: Failed to parse CIDR {}: {}", cidr, e.getMessage());
            return false;
        }
    }

    private long ipToLong(String ip) {
        String[] octets = ip.split("\\.");
        long result = 0;
        for (String octet : octets) {
            result = (result << 8) | (Long.parseLong(octet) & 0xFF);
        }
        return result;
    }

    private void sendForbiddenResponse(HttpServletResponse response, String clientIp) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = Map.of(
                "success", false,
                "message", "Access denied: your IP address (" + clientIp + ") is not in the allowed whitelist.",
                "status", 403
        );

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
