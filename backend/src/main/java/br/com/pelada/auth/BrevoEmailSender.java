package br.com.pelada.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Sends transactional messages through Brevo's HTTPS API (compatible with Render Free). */
@Component
public class BrevoEmailSender {

  private final RestClient client;
  private final String apiKey;
  private final String from;

  @Autowired
  public BrevoEmailSender(
    @Value("${app.brevo-api-key:}") String apiKey,
    @Value("${app.mail-from:}") String from
  ) {
    this(createClient(), apiKey, from);
  }

  private static RestClient createClient() {
    SimpleClientHttpRequestFactory requestFactory =
      new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder()
      .baseUrl("https://api.brevo.com/v3")
      .requestFactory(requestFactory)
      .build();
  }

  BrevoEmailSender(RestClient client, String apiKey, String from) {
    this.client = client;
    this.apiKey = apiKey;
    this.from = from;
  }

  public boolean isConfigured() {
    return !blank(apiKey) && !blank(from);
  }

  public void send(RecoveryEmail email) {
    client
      .post()
      .uri("/smtp/email")
      .header("api-key", apiKey)
      .contentType(MediaType.APPLICATION_JSON)
      .body(
        new BrevoMessage(
          new Sender("Pelada", from),
          List.of(new Recipient(email.recipient(), email.recipientName())),
          email.subject(),
          email.textContent()
        )
      )
      .retrieve()
      .toBodilessEntity();
  }

  public record RecoveryEmail(
    String recipient,
    String recipientName,
    String subject,
    String textContent
  ) {}

  private record BrevoMessage(
    Sender sender,
    List<Recipient> to,
    String subject,
    String textContent
  ) {}

  private record Sender(String name, String email) {}

  private record Recipient(String email, String name) {}

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
