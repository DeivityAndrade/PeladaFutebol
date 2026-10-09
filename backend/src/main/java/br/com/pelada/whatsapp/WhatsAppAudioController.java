package br.com.pelada.whatsapp;

import br.com.pelada.assistant.AudioTranscriber;
import br.com.pelada.domain.ApiException;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/integrations/whatsapp/inbox/{id}/audio")
public class WhatsAppAudioController {

  private final WhatsAppIntegration config;
  private final WhatsAppInbox inbox;
  private final WhatsApp whatsapp;
  private final AudioTranscriber audio;

  public WhatsAppAudioController(
    WhatsAppIntegration config,
    WhatsAppInbox inbox,
    WhatsApp whatsapp,
    AudioTranscriber audio
  ) {
    this.config = config;
    this.inbox = inbox;
    this.whatsapp = whatsapp;
    this.audio = audio;
  }

  @PostMapping(consumes = "multipart/form-data")
  public AudioTranscriber.Transcript transcribe(
    @RequestHeader(name = "Authorization", required = false) String token,
    @PathVariable UUID id,
    @RequestParam UUID leaseId,
    @RequestParam MultipartFile file
  ) throws IOException {
    config.authenticate(token);
    if (!whatsapp.available()) throw new ApiException(
      503,
      "WhatsApp indisponível."
    );
    var row = inbox.audioInput(id, leaseId);
    if (row.get("text") != null) return new AudioTranscriber.Transcript(
      (String) row.get("text")
    );
    String mime = file.getContentType();
    String expected = (String) row.get("media_mime");
    if (
      mime == null ||
      expected == null ||
      !mime.split(";", 2)[0].equalsIgnoreCase(expected.split(";", 2)[0])
    ) throw new ApiException(415, "Formato de áudio diferente do recebido.");
    var transcript = audio.transcribe(
      (UUID) row.get("player_id"),
      file.getBytes(),
      mime
    );
    inbox.transcript(id, leaseId, transcript.text());
    return transcript;
  }
}
