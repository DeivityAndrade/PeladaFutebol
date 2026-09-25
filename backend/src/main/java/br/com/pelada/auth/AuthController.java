package br.com.pelada.auth;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.ApiException;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final Accounts accounts;
  private final AuthenticationManager manager;
  private final SecurityContextRepository repository;
  private final PasswordRecovery passwordRecovery;

  public AuthController(
    Accounts accounts,
    AuthenticationManager manager,
    SecurityContextRepository repository,
    PasswordRecovery passwordRecovery
  ) {
    this.accounts = accounts;
    this.manager = manager;
    this.repository = repository;
    this.passwordRecovery = passwordRecovery;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken csrf) {
    return Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName());
  }

  @PostMapping("/register")
  public UserView register(@Valid @RequestBody Register input) {
    return accounts.register(input);
  }

  @PostMapping("/password-reset/request")
  public ResponseEntity<Map<String, String>> requestPasswordReset(
    @Valid @RequestBody PasswordResetRequest input,
    HttpServletRequest request
  ) {
    passwordRecovery.request(input.email(), request.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(
      Map.of(
        "message",
        "Se este e-mail estiver cadastrado, enviaremos um link de recuperação."
      )
    );
  }

  @PostMapping("/password-reset/complete")
  public ResponseEntity<Void> completePasswordReset(
    @Valid @RequestBody PasswordResetCompletion input,
    HttpServletRequest request
  ) {
    passwordRecovery.complete(input);
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    if (request.getSession(false) != null) request
      .getSession(false)
      .invalidate();
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/login")
  public UserView login(
    @Valid @RequestBody Login input,
    HttpServletRequest req,
    HttpServletResponse res
  ) {
    Authentication auth;
    try {
      auth = manager.authenticate(
        UsernamePasswordAuthenticationToken.unauthenticated(
          Accounts.normalize(input.email()),
          input.password()
        )
      );
    } catch (org.springframework.security.core.AuthenticationException ex) {
      throw new ApiException(401, "E-mail ou senha incorretos.");
    }
    if (req.getSession(false) != null) req.changeSessionId();
    new HttpSessionCsrfTokenRepository().saveToken(null, req, res);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    repository.saveContext(context, req, res);
    return Accounts.view(accounts.current(auth));
  }

  @GetMapping("/me")
  public UserView me(Authentication auth) {
    return Accounts.view(accounts.current(auth));
  }
}
