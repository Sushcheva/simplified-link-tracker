package backend.academy.linktracker.scrapper.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    public static final String COOKIE_NAME = "LINK_TRACKER_SESSION";

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    DefaultCookieSerializer cookieSerializer(@Value("${app.security.secure-cookie}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName(COOKIE_NAME);
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        cookie.setUseSecureCookie(secure);
        return cookie;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountService accounts,
                                            PasswordEncoder encoder, ObjectMapper mapper) throws Exception {
        var provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(encoder);
        http.authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/styles.css",
                                "/api/auth/csrf", "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email")
                        .successHandler((request, response, authentication) -> {
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            mapper.writeValue(response.getOutputStream(),
                                    UserView.from((TrackerPrincipal) authentication.getPrincipal()));
                        })
                        .failureHandler((request, response, exception) ->
                                problem(response, mapper, 401, "Неверный email или пароль.")))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .deleteCookies(COOKIE_NAME)
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problem(response, mapper, 401, "Войдите в аккаунт."))
                        .accessDeniedHandler((request, response, exception) ->
                                problem(response, mapper, 403, "Запрос отклонён. Обновите страницу и повторите действие.")))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")));
        // CSRF protection and session fixation protection remain enabled.
        return http.build();
    }

    private static void problem(HttpServletResponse response, ObjectMapper mapper,
                                int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), Map.of(
                "type", "about:blank", "status", status,
                "title", status == 401 ? "Unauthorized" : "Forbidden", "detail", detail));
    }
}
