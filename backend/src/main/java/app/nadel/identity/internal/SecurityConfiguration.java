package app.nadel.identity.internal;

import app.nadel.identity.AccessProfileSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccessProfileSource profiles,
            ProblemSecurityHandlers handlers, ObjectPostProcessor<Object> postProcessor) throws Exception {
        var converter = new MemberAuthenticationConverter(profiles);
        http
                .objectPostProcessor(rethrowFreeBearerFilter(postProcessor, handlers))
                .csrf(csrf -> csrf.disable()) // bearer tokens only, no cookie sessions
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        // the API returns JSON only: nothing may be framed, sniffed or loaded from it
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .oauth2ResourceServer(rs -> rs
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    /**
     * By default the bearer token filter rethrows an {@code AuthenticationServiceException} (a failed member lookup,
     * an unreachable Keycloak) instead of handing it to the entry point; the exception then escapes the filter chain
     * and the error dispatch answers 401, telling the user their session expired during an outage. Here the filter
     * hands it to the entry point, which answers 500 {@code INTERNAL_ERROR}. The Spring Security post-processor is
     * wrapped, not replaced: it autowires everything the configurers create.
     */
    private static ObjectPostProcessor<Object> rethrowFreeBearerFilter(ObjectPostProcessor<Object> delegate,
            ProblemSecurityHandlers handlers) {
        return new ObjectPostProcessor<>() {
            @Override
            public <O> O postProcess(O object) {
                O processed = delegate.postProcess(object);
                if (processed instanceof BearerTokenAuthenticationFilter filter) {
                    var failureHandler = new AuthenticationEntryPointFailureHandler(handlers.entryPoint());
                    failureHandler.setRethrowAuthenticationServiceException(false);
                    filter.setAuthenticationFailureHandler(failureHandler);
                }
                return processed;
            }
        };
    }
}
