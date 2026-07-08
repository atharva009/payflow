package com.payments.config;

import tools.jackson.databind.ObjectMapper;
import com.payments.idempotency.IdempotencyFilter;
import com.payments.idempotency.IdempotencyService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;
import static org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.secret-key}")
    private String jwtSecret;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/**").hasRole("USER")
                .requestMatchers("/actuator/health/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder
            .withSecretKey(new SecretKeySpec(
                jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
            .macAlgorithm(HS256)
            .build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        return new JwtAuthenticationConverter() {{
            setJwtGrantedAuthoritiesConverter(jwt ->
                List.of(new SimpleGrantedAuthority(
                    "ROLE_" + jwt.getClaimAsString("role"))));
        }};
    }

    // Idempotency runs AFTER the Spring Security filter chain — authenticate first,
    // deduplicate second. Registered (not @Component) to control ordering precisely
    // and avoid double registration. Scoped to POST /api/v1/payments only.
    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyService idempotencyService,
            RedissonClient redissonClient,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new IdempotencyFilter(idempotencyService, redissonClient, objectMapper));
        registration.addUrlPatterns("/api/v1/payments");
        // Boot 4: DEFAULT_FILTER_ORDER (-100) relocated from SecurityProperties to
        // SecurityFilterProperties. Same value → +10 = -90, i.e. after the security chain.
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10);
        return registration;
    }
}
