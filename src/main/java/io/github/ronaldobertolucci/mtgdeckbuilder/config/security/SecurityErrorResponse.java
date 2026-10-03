package io.github.ronaldobertolucci.mtgdeckbuilder.config.security;

import io.github.ronaldobertolucci.mtgdeckbuilder.exception.JwtAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

final class SecurityErrorResponse {
    static final String AUTHENTICATION_FAILURE = SecurityErrorResponse.class.getName() + ".failure";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SecurityErrorResponse() {}

    static void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object failure = request.getAttribute(AUTHENTICATION_FAILURE);
        String code = failure instanceof JwtAuthenticationException jwt ? jwt.getCode()
                : failure == null ? "AUTHENTICATION_REQUIRED" : "INVALID_TOKEN";
        String message = switch (code) {
            case "SESSION_EXPIRED" -> "Session expired. Please log in again.";
            case "INVALID_TOKEN" -> "Invalid token. Please log in again.";
            default -> "Authentication required. Please log in.";
        };
        response.setHeader("WWW-Authenticate", failure == null ? "Bearer" : "Bearer error=\"invalid_token\"");
        write(request, response, HttpStatus.UNAUTHORIZED, code, message);
    }

    static void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied.");
    }

    private static void write(HttpServletRequest request, HttpServletResponse response,
                              HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        MAPPER.writeValue(response.getWriter(), Map.of(
                "timestamp", Instant.now().toString(), "status", status.value(),
                "error", status.getReasonPhrase(), "code", code,
                "message", message, "path", request.getRequestURI()));
    }
}
