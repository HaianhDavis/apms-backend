//package com.apms.security;
//
//import com.apms.domain.admin.IpWhitelistEntry;
//import com.apms.domain.admin.service.AdminSettingsService;
//import jakarta.servlet.FilterChain;
//import jakarta.servlet.ServletException;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.HttpMethod;
//import org.springframework.web.filter.OncePerRequestFilter;
//
//import java.io.IOException;
//import java.net.InetAddress;
//import java.util.List;
//
//@RequiredArgsConstructor
//public class IpWhitelistFilter extends OncePerRequestFilter {
//
//    private final AdminSettingsService adminSettingsService;
//
//    @Override
//    protected boolean shouldNotFilter(HttpServletRequest request) {
//        String uri = request.getRequestURI();
//        return HttpMethod.OPTIONS.matches(request.getMethod())
//                || uri.startsWith("/api/v1/auth/")
//                || uri.equals("/health")
//                || uri.startsWith("/swagger-ui")
//                || uri.equals("/v3/api-docs")
//                || uri.startsWith("/ws/");
//    }
//
//    @Override
//    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
//            throws ServletException, IOException {
//
//        if (!adminSettingsService.isIpWhitelistEnabled()) {
//            filterChain.doFilter(request, response);
//            return;
//        }
//
//        String clientIp = resolveClientIp(request);
//        List<IpWhitelistEntry> entries = adminSettingsService.findEnabledIpEntries();
//
//        if (isLocalhost(clientIp) || entries.stream().anyMatch(e -> matches(clientIp, e.getIpAddress()))) {
//            filterChain.doFilter(request, response);
//            return;
//        }
//
//        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
//        response.setContentType("application/json;charset=UTF-8");
//        response.getWriter().write("{\"success\":false,\"message\":\"Access denied: IP not whitelisted\",\"data\":null}");
//    }
//
//    private String resolveClientIp(HttpServletRequest request) {
//        String forwarded = request.getHeader("X-Forwarded-For");
//        if (forwarded != null && !forwarded.isBlank()) {
//            return forwarded.split(",")[0].trim();
//        }
//        return request.getRemoteAddr();
//    }
//
//    private boolean isLocalhost(String ip) {
//        return ip == null || ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("127.");
//    }
//
//    private boolean matches(String ip, String rule) {
//        if (rule == null || rule.isBlank()) {
//            return false;
//        }
//        rule = rule.trim();
//        int slash = rule.indexOf('/');
//        if (slash < 0) {
//            return rule.equals(ip);
//        }
//        try {
//            InetAddress ipAddr = InetAddress.getByName(ip);
//            byte[] ipBytes = ipAddr.getAddress();
//            int prefix = Integer.parseInt(rule.substring(slash + 1));
//            InetAddress netAddr = InetAddress.getByName(rule.substring(0, slash));
//            byte[] netBytes = netAddr.getAddress();
//            if (ipBytes.length != netBytes.length) {
//                return false;
//            }
//            int fullBytes = prefix / 8;
//            int remBits = prefix % 8;
//            for (int i = 0; i < fullBytes; i++) {
//                if (ipBytes[i] != netBytes[i]) {
//                    return false;
//                }
//            }
//            if (remBits > 0) {
//                int mask = (0xFF << (8 - remBits)) & 0xFF;
//                return (ipBytes[fullBytes] & mask) == (netBytes[fullBytes] & mask);
//            }
//            return true;
//        } catch (Exception e) {
//            return false;
//        }
//    }
//}
