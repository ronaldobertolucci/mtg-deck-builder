package io.github.ronaldobertolucci.mtgdeckbuilder.config.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

// Custom header forces browser preflight; CORS permits only configured trusted origins.
public class RefreshCsrfFilter extends OncePerRequestFilter {
    private final RequestMatcher protectedEndpoints = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/login"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/refresh"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/logout"));

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (protectedEndpoints.matches(request)
                && !"1".equals(request.getHeader("X-CSRF-Protection"))) {
            SecurityErrorResponse.forbidden(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
