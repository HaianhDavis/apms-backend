package com.apms.domain.ai.service.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
public final class GeminiCredentialDiagnostics {
    private GeminiCredentialDiagnostics() {}

    public static String describeKey(String key) {
        if (key == null || key.isBlank()) return "EMPTY";
        // Never disclose an entire short credential, or control characters.
        String prefix = key.length() <= 4 ? "[short]"
                : key.substring(0, 4).replaceAll("[^A-Za-z0-9._-]", "?");
        return "prefix=" + prefix + ", length=" + key.length();
    }

    public static ClientHttpRequestInterceptor interceptor(String component) {
        return (request, body, execution) -> {
            if (!log.isDebugEnabled()
                    || !"generativelanguage.googleapis.com".equals(request.getURI().getHost())) {
                return execution.execute(request, body);
            }
            String queryKey = UriComponentsBuilder.fromUri(request.getURI()).build()
                    .getQueryParams().getFirst("key");
            if (queryKey != null) {
                queryKey = java.net.URLDecoder.decode(queryKey, java.nio.charset.StandardCharsets.UTF_8);
            }
            String headerKey = request.getHeaders().getFirst("x-goog-api-key");
            log.debug("Gemini HTTP component={}, endpoint={}://{}{}, queryCredential={}, headerCredential={}",
                    component, request.getURI().getScheme(), request.getURI().getHost(),
                    request.getURI().getPath(), describeKey(queryKey), describeKey(headerKey));
            var response = execution.execute(request, body);
            log.debug("Gemini HTTP component={}, status={}", component, response.getStatusCode().value());
            return response;
        };
    }
}
