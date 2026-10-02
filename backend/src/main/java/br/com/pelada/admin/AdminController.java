package br.com.pelada.admin;

import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private final Administration administration;

  public AdminController(Administration administration) {
    this.administration = administration;
  }

  @GetMapping("/summary")
  public ResponseEntity<Administration.Summary> summary(Authentication auth) {
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(administration.summary(auth));
  }
}
