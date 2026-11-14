package com.hcsc.claims.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security SCAFFOLD generated from the WebSphere security configuration.
 *
 * <p>WAS JAAS security has no direct Spring equivalent: every rule below is a TODO for the architect.
 * The default denies anonymous access (anyRequest().authenticated()) so nothing is exposed by accident.
 * Selected approach: Expose a REST controller (+ OpenAPI): Transform the EJB interface into a RESTful API using Spring's @RestController, allowing external systems to interact with the ClaimProcessingBean via HTTP requests.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/**").hasAnyRole("claims-adjuster", "claims-supervisor", "claims-readonly", "medical-review")
                .requestMatchers("/api/admin/**").hasRole("claims-supervisor")
                .anyRequest().authenticated())
            .httpBasic(basic -> { });   // TODO(architect): replace with the chosen mechanism (OIDC / form / LDAP)
        return http.build();
    }
}
