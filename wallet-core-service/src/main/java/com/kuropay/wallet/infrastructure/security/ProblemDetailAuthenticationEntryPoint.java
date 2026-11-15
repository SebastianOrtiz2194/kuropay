package com.kuropay.wallet.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders a missing or invalid token as an RFC 9457 problem, like every other API error.
 *
 * <p>Authentication fails in the security filter chain, before the request reaches Spring MVC,
 * so {@code GlobalExceptionHandler} never sees it; that is why this lives here.
 *
 * <p>The standard entry point still does the protocol part: status 401 and the
 * {@code WWW-Authenticate: Bearer} header that RFC 6750 requires (with the reason when a token
 * was sent). This class only adds the body, which stays generic on purpose.
 */
class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint bearerTokenEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final JsonMapper jsonMapper;

    ProblemDetailAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException
    ) throws IOException, ServletException {
        bearerTokenEntryPoint.commence(request, response, authException);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "A valid Bearer access token is required");
        problem.setTitle("Unauthorized");
        problem.setType(URI.create("https://api.kuropay.com/errors/unauthorized"));
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
