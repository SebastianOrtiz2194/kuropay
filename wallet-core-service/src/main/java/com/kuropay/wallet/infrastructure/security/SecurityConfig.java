package com.kuropay.wallet.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * This service is an OAuth2 <em>resource server</em>: it never logs anyone in and never sees a
 * password or a signing key. It only verifies the signature, issuer and expiry of the access
 * token (a JWT) that an identity provider issued, using that provider's public keys.
 *
 * <p>Authentication only. Whether the caller may touch a given wallet is a separate question.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        return http
                // Deny by default: a new endpoint is protected unless someone opens it on purpose.
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(new ProblemDetailAuthenticationEntryPoint(jsonMapper)))
                // Every request carries its own token, so the server keeps no session to steal
                // and any instance can serve any request.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF abuses credentials the browser attaches by itself (cookies). A Bearer token
                // is attached explicitly by the client and there is no session cookie to ride on.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
