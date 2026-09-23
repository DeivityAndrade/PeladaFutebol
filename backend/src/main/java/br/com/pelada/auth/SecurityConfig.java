package br.com.pelada.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;

@Configuration
public class SecurityConfig {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  AuthenticationManager authenticationManager(
    AuthenticationConfiguration config
  ) throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  SecurityFilterChain security(
    HttpSecurity http,
    SecurityContextRepository repository
  ) throws Exception {
    return http
      .authorizeHttpRequests(auth ->
        auth
          .requestMatchers(
            HttpMethod.GET,
            "/api/auth/csrf",
            "/api/demo",
            "/api/demo/finished",
            "/api/health",
            "/api/openapi/**",
            "/api/docs/**",
            "/swagger-ui/**"
          )
          .permitAll()
          .requestMatchers(
            HttpMethod.POST,
            "/api/auth/login",
            "/api/auth/register"
          )
          .permitAll()
          .requestMatchers("/api/**")
          .authenticated()
          .anyRequest()
          .permitAll()
      )
      .securityContext(c -> c.securityContextRepository(repository))
      .requestCache(c -> c.disable())
      .exceptionHandling(c ->
        c
          .authenticationEntryPoint((req, res, ex) ->
            error(res, 401, "Entre na sua conta para continuar.")
          )
          .accessDeniedHandler((req, res, ex) ->
            error(
              res,
              403,
              "Ação não autorizada. Atualize a página e tente novamente."
            )
          )
      )
      .logout(c ->
        c
          .logoutUrl("/api/auth/logout")
          .logoutSuccessHandler((req, res, auth) -> res.setStatus(204))
      )
      .headers(c ->
        c.contentSecurityPolicy(p ->
          p.policyDirectives(
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
          )
        )
      )
      .build();
  }

  private static void error(HttpServletResponse res, int status, String message)
    throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write("{\"message\":\"" + message + "\"}");
  }
}
