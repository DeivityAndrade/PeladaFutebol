package br.com.pelada.assistant;

import br.com.pelada.domain.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Short recordings only. Bytes are forwarded in memory and never persisted or logged. */
@Service
public class AudioTranscriber {

  public static final int MAX_BYTES = 2 * 1024 * 1024;
  private final RestClient client;
  private final boolean enabled;
  private final String key, model;
  private final int dailyLimit;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  @Autowired
  public AudioTranscriber(
    @Value("${app.assistant.audio-enabled:false}") boolean enabled,
    @Value("${app.assistant.api-key:}") String key,
    @Value(
      "${app.assistant.transcription-model:gpt-4o-mini-transcribe}"
    ) String model,
    @Value(
      "${app.assistant.base-url:https://api.openai.com/v1}"
    ) String baseUrl,
    @Value("${app.assistant.audio-daily-limit:20}") int dailyLimit,
    JdbcTemplate jdbc,
    TransactionTemplate tx,
    Clock clock
  ) {
    this(
      createClient(baseUrl),
      enabled,
      key,
      model,
      dailyLimit,
      jdbc,
      tx,
      clock
    );
  }

  AudioTranscriber(
    RestClient client,
    boolean enabled,
    String key,
    String model,
    int dailyLimit,
    JdbcTemplate jdbc,
    TransactionTemplate tx,
    Clock clock
  ) {
    this.client = client;
    this.enabled = enabled;
    this.key = key;
    this.model = model;
    this.dailyLimit = Math.max(1, Math.min(100, dailyLimit));
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
  }

  private static RestClient createClient(String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(40));
    return RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  public boolean available() {
    return enabled && !key.isBlank() && !model.isBlank();
  }

  public record Transcript(String text) {}

  public Transcript transcribe(UUID user, byte[] bytes, String mime) {
    if (!available()) throw new ApiException(
      503,
      "O áudio ainda não está disponível. Escreva seu pedido."
    );
    if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new ApiException(
      413,
      "Envie um áudio de até 2 MB."
    );
    String extension = extension(bytes, mime);
    tx.executeWithoutResult(s -> {
      Instant now = clock.instant();
      jdbc.update(
        "DELETE FROM assistant_request_limits WHERE expires_at<?",
        now.atOffset(ZoneOffset.UTC)
      );
      take(
        "audio:user:" + user + ":" + now.getEpochSecond() / 3600,
        5,
        now.plusSeconds(7200)
      );
      take(
        "audio:daily:" + LocalDate.ofInstant(now, ZoneOffset.UTC),
        dailyLimit,
        now.plus(Duration.ofDays(2))
      );
    });
    try {
      var body = new LinkedMultiValueMap<String, Object>();
      body.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return "pedido." + extension;
          }
        }
      );
      body.add("model", model);
      body.add("language", "pt");
      body.add("response_format", "json");
      String response = client
        .post()
        .uri("/audio/transcriptions")
        .header("Authorization", "Bearer " + key)
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .retrieve()
        .body(String.class);
      if (
        response == null || response.length() > 32000
      ) throw new IllegalArgumentException();
      String text = JsonMapper.builder()
        .build()
        .readTree(response)
        .path("text")
        .asText("")
        .strip();
      if (text.isEmpty()) throw new ApiException(
        422,
        "Não consegui ouvir uma fala. Grave novamente ou escreva."
      );
      if (text.length() > 1200) throw new ApiException(
        422,
        "O pedido ficou longo. Envie um áudio mais curto ou escreva até 1200 caracteres."
      );
      return new Transcript(text);
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      // Never propagate provider bodies, filenames, recordings or credentials.
      throw new ApiException(
        503,
        "Não consegui transcrever agora. Tente novamente ou escreva seu pedido."
      );
    }
  }

  private void take(String bucket, int maximum, Instant expiry) {
    if (
      jdbc
        .queryForList(
          "INSERT INTO assistant_request_limits VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET requests=assistant_request_limits.requests+1 WHERE assistant_request_limits.requests<? RETURNING requests",
          Integer.class,
          bucket,
          expiry.atOffset(ZoneOffset.UTC),
          maximum
        )
        .isEmpty()
    ) throw new ApiException(
      429,
      "O limite de áudios do piloto foi atingido. Tente mais tarde ou escreva seu pedido."
    );
  }

  static String extension(byte[] b, String mime) {
    String m =
      mime == null
        ? ""
        : mime.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    boolean webm =
      b.length >= 4 &&
      b[0] == 0x1a &&
      b[1] == 0x45 &&
      (b[2] & 255) == 0xdf &&
      (b[3] & 255) == 0xa3;
    boolean ogg =
      b.length >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S';
    boolean mp4 =
      b.length >= 12 &&
      b[4] == 'f' &&
      b[5] == 't' &&
      b[6] == 'y' &&
      b[7] == 'p';
    if (webm && Set.of("audio/webm", "video/webm").contains(m)) return "webm";
    if (ogg && Set.of("audio/ogg", "application/ogg").contains(m)) return "ogg";
    if (
      mp4 && Set.of("audio/mp4", "audio/m4a", "video/mp4").contains(m)
    ) return "m4a";
    throw new ApiException(
      415,
      "Formato de áudio inválido. Use uma gravação WebM, Ogg ou M4A."
    );
  }
}
