package dev.mockpay.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Three authentication schemes, one application.
 *
 * <p>They are genuinely different problems and trying to serve them with one configuration is how
 * dashboards end up with long-lived credentials in {@code localStorage}:
 *
 * <table border="1">
 *   <caption>Filter chains</caption>
 *   <tr><th>Path</th><th>Caller</th><th>Credential</th><th>State</th><th>CSRF</th></tr>
 *   <tr><td>{@code /v1/public/**}</td><td>Browser</td><td>Publishable key / client secret</td>
 *       <td>Stateless</td><td>No</td></tr>
 *   <tr><td>{@code /v1/**}</td><td>Merchant server</td><td>Secret API key</td>
 *       <td>Stateless</td><td>No</td></tr>
 *   <tr><td>{@code /dashboard/**}</td><td>A person</td><td>Session cookie</td>
 *       <td>Session</td><td><b>Yes</b></td></tr>
 * </table>
 *
 * <p><b>Why CSRF applies only to the dashboard.</b> CSRF is an attack on <em>ambient</em>
 * credentials — the browser attaches the cookie automatically, so a form on another site can make a
 * request as the logged-in user. An API key is not ambient: it has to be set deliberately on every
 * request, and a cross-site form cannot set headers. Enabling CSRF on the API chain would break
 * every server-to-server integration for no security gain; leaving it off the dashboard chain would
 * be a real vulnerability.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Argon2id for passwords.
     *
     * <p>The parameters are Spring Security's current defaults: 16 MiB of memory, 2 iterations, 1
     * degree of parallelism. Memory-hardness is the point — it is what makes GPU and ASIC cracking
     * uneconomic, which plain SHA-256 cannot do at any iteration count.
     *
     * <p>Note the contrast with API keys, which use bare SHA-256 deliberately. Keys are
     * high-entropy and verified on every request; passwords are human-chosen and verified once per
     * login. Different threat, different tool.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * Chain 1 — the browser-facing payment surface.
     *
     * <p>Ordered first so it wins over the broader {@code /v1/**} matcher below. Authentication is
     * the publishable key or the intent's client secret, checked inside the controller, so Spring
     * Security's job here is only to stay out of the way.
     */
    @Bean
    @Order(1)
    SecurityFilterChain publicApiChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/v1/public/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * Chain 2 — the merchant server API.
     *
     * <p>{@code permitAll} looks alarming and is not: {@code ApiKeyFilter} runs ahead of this and
     * rejects anything without a valid secret key. Spring Security is deliberately not the
     * authenticator here, because the API's error shape is part of its contract and a framework
     * 401 page is not it.
     */
    @Bean
    @Order(2)
    SecurityFilterChain apiKeyChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/v1/**")
                // An API key is not an ambient credential — it must be set deliberately on every
                // request — so CSRF does not apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * Chain 3 — the dashboard.
     *
     * <p>This is the one that needs real protection: a session cookie <em>is</em> ambient.
     */
    @Bean
    @Order(3)
    SecurityFilterChain dashboardChain(HttpSecurity http) throws Exception {
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        // Opt out of Spring Security's deferred CSRF token loading.
        //
        // By default the token is only materialised if something actually reads it, which for a
        // server-rendered form is a sensible optimisation. For a SPA it is a trap: nothing reads it
        // during a GET, so the XSRF-TOKEN cookie is never issued, and the first POST then fails
        // with 403 and no way for the client to recover. Setting the attribute name to null makes
        // the token render eagerly, so the cookie is always present.
        csrfHandler.setCsrfRequestAttributeName(null);

        return http
                .securityMatcher("/dashboard/**")
                .csrf(csrf -> csrf
                        // Token in a readable cookie, echoed back in a header by the SPA. Works
                        // because an attacker's site can read neither: the cookie is same-origin
                        // and the header must be set explicitly.
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        // Signup and login have no session to protect yet, and requiring a token
                        // before one exists is a chicken-and-egg problem.
                        .ignoringRequestMatchers("/dashboard/auth/signup", "/dashboard/auth/login",
                                "/dashboard/auth/accept-invitation",
                                // Both are reached from an emailed link with no session yet, so
                                // there is no token to have obtained. The reset token itself is
                                // the credential, and it is single-use and short-lived.
                                "/dashboard/auth/forgot-password", "/dashboard/auth/reset-password"))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // A new session id on login. Without this, an attacker who plants a known
                        // session id before login still holds it afterwards — session fixation.
                        .sessionFixation(fixation -> fixation.newSession()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/dashboard/auth/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        // 401 JSON, never a redirect to a login page. The caller is a fetch() in a
                        // SPA; an HTML redirect would surface as an unparseable response.
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        // Access denials — a missing or stale CSRF token, most often — are raised by
                        // the filter chain, before any controller runs, so ErrorHandler never sees
                        // them. Without this they come back in Spring's default shape and a client
                        // that parses `error.code` chokes on the one response it most needs to
                        // understand.
                        .accessDeniedHandler((request, response, denied) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType("application/json");
                            response.getWriter().write("""
                                    {"error":{"type":"permission_error","code":"access_denied",\
                                    "message":"Request rejected. If this was a state-changing \
                                    request, include the CSRF token from the XSRF-TOKEN cookie \
                                    in an X-XSRF-TOKEN header."}}""");
                        }))
                .logout(logout -> logout.disable())
                .build();
    }

    /**
     * Chain 4 — everything else: static assets, the demo checkout, the simulated challenge pages,
     * and the webhook sink.
     *
     * <p>CSRF is off because the sink is POSTed to server-to-server by the dispatcher, which holds
     * no cookie and cannot obtain a token.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        // X-Frame-Options is a blunt instrument: one value for the whole origin,
                        // and no way to name several allowed embedders. The hosted payment page
                        // has to be embeddable by every merchant that integrates, so the modern
                        // CSP directive replaces it.
                        // frame-ancestors is set by FrameEmbeddingConfig, which also covers
                        // forwarded dispatches. X-Frame-Options would contradict it here, and
                        // browsers give X-Frame-Options precedence when both are present.
                        .frameOptions(frame -> frame.disable()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
