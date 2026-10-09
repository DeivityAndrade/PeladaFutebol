package br.com.pelada.assistant;

import br.com.pelada.auth.Accounts;
import br.com.pelada.groups.Groups;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/groups/{id}/assistant/audio")
public class AudioController {

  private final Accounts accounts;
  private final Groups groups;
  private final AudioTranscriber audio;
  private final TransactionTemplate tx;

  public AudioController(
    Accounts accounts,
    Groups groups,
    AudioTranscriber audio,
    TransactionTemplate tx
  ) {
    this.accounts = accounts;
    this.groups = groups;
    this.audio = audio;
    this.tx = tx;
  }

  public record Status(boolean available) {}

  @GetMapping
  public Status status(Authentication auth, @PathVariable UUID id) {
    UUID user = accounts.current(auth).id;
    tx.executeWithoutResult(s -> groups.requireOwner(user, id));
    return new Status(audio.available());
  }

  @PostMapping(consumes = "multipart/form-data")
  public AudioTranscriber.Transcript transcribe(
    Authentication auth,
    @PathVariable UUID id,
    @RequestParam MultipartFile file
  ) throws IOException {
    UUID user = accounts.current(auth).id;
    tx.executeWithoutResult(s -> groups.requireOwner(user, id));
    return audio.transcribe(user, file.getBytes(), file.getContentType());
  }
}
