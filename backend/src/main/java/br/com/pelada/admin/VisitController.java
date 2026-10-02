package br.com.pelada.admin;

import jakarta.servlet.http.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class VisitController {

  private static final String COOKIE = "pelada_visit";
  private final SiteVisits visits;
  private final boolean secure;

  public VisitController(
    SiteVisits visits,
    @Value("${server.servlet.session.cookie.secure:false}") boolean secure
  ) {
    this.visits = visits;
    this.secure = secure;
  }

  @PostMapping("/api/visits")
  public ResponseEntity<Void> record(
    @CookieValue(name = COOKIE, required = false) String cookie,
    HttpServletRequest request
  ) {
    if (
      !visits.enabled() ||
      "1".equals(request.getHeader("DNT")) ||
      "1".equals(request.getHeader("Sec-GPC"))
    ) {
      return ResponseEntity.noContent()
        .cacheControl(CacheControl.noStore())
        .build();
    }
    UUID token = parse(cookie);
    if (token == null) token = UUID.randomUUID();
    visits.record(token);
    return ResponseEntity.noContent()
      .cacheControl(CacheControl.noStore())
      .header(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(COOKIE, token.toString())
          .httpOnly(true)
          .secure(secure)
          .sameSite("Lax")
          .path("/")
          .maxAge(SiteVisits.WINDOW)
          .build()
          .toString()
      )
      .build();
  }

  private UUID parse(String value) {
    if (value == null || value.length() != 36) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
