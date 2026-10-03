package io.github.ronaldobertolucci.mtgdeckbuilder.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import java.time.*;

@Component
public class RefreshCookie {
    public static final String NAME = "mtg_refresh";
    @Value("${api.security.refresh.cookie-secure:true}") private boolean secure;
    @Value("${server.servlet.context-path:}") private String contextPath;

    public String create(String value, Instant expiresAt) {
        long seconds = Math.max(0, Duration.between(Instant.now(), expiresAt).getSeconds());
        return cookie(value, seconds);
    }
    public String clear() { return cookie("", 0); }
    private String cookie(String value, long seconds) {
        String path = ("/".equals(contextPath) ? "" : contextPath) + "/auth";
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure)
                .sameSite("Strict").path(path).maxAge(seconds).build().toString();
    }
}
