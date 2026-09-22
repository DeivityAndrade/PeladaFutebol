package br.com.pelada.auth;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.ApiException;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.Map;
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

  public AuthController(
    Accounts accounts,
    AuthenticationManager manager,
    SecurityContextRepository repository
  ) {
    this.accounts = accounts;
    this.manager = manager;
    this.repository = repository;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken csrf) {
    return Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName());
  }

  @PostMapping("/register")
  public UserView register(@Valid @RequestBody Register input) {
    return accounts.register(input);
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
