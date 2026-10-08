package br.com.pelada.auth;

import br.com.pelada.api.Contracts.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class AccountProfileController {

  private final Accounts accounts;
  private final AccountProfile profile;

  public AccountProfileController(Accounts accounts, AccountProfile profile) {
    this.accounts = accounts;
    this.profile = profile;
  }

  public record NameInput(@NotBlank @Size(max = 80) String name) {}

  @PutMapping("/auth/profile")
  public UserView rename(
    Authentication auth,
    @Valid @RequestBody NameInput input
  ) {
    return profile.rename(accounts.current(auth).id, input.name());
  }

  @PostMapping(
    value = "/auth/profile/photo",
    consumes = MediaType.MULTIPART_FORM_DATA_VALUE
  )
  public UserView upload(
    Authentication auth,
    @RequestPart("file") MultipartFile file
  ) {
    return profile.upload(accounts.current(auth).id, file);
  }

  @DeleteMapping("/auth/profile/photo")
  public UserView remove(Authentication auth) {
    return profile.remove(accounts.current(auth).id);
  }

  @GetMapping(
    value = "/players/{playerId}/photo",
    produces = MediaType.IMAGE_PNG_VALUE
  )
  public ResponseEntity<byte[]> photo(
    Authentication auth,
    @PathVariable UUID playerId,
    @RequestParam UUID v
  ) {
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(profile.photo(accounts.current(auth).id, playerId, v));
  }
}
