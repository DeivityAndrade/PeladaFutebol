package br.com.pelada;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.pelada.api.Contracts.PasswordResetCompletion;
import br.com.pelada.auth.PasswordRecovery;
import br.com.pelada.domain.ApiException;
import br.com.pelada.domain.Domain.Player;
import br.com.pelada.domain.Store;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
  properties = {
    "spring.mail.host=smtp.example.test",
    "spring.mail.username=mailer",
    "spring.mail.password=test-password",
    "app.mail-from=noreply@example.test",
    "app.public-app-url=https://pelada.example.test",
  }
)
class PasswordRecoveryTest {

  @Autowired
  PasswordRecovery recovery;

  @Autowired
  Store store;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  PasswordEncoder encoder;

  @Autowired
  TransactionTemplate tx;

  @MockitoBean
  JavaMailSender mailSender;

  @BeforeEach
  void clean() {
    jdbc.execute(
      "TRUNCATE password_reset_tokens,password_reset_limits,spring_session,players CASCADE"
    );
    reset(mailSender);
  }

  @Test
  void requestStoresOnlyTokenHashAndCompletionChangesPasswordAndRevokesSessions()
    throws Exception {
    String email = "reset-" + UUID.randomUUID() + "@example.test";
    UUID playerId = tx.execute(
      status ->
        store
          .save(
            new Player(
              "Jogador de teste",
              email,
              encoder.encode("SenhaAntiga123!")
            )
          )
          .id
    );
    long now = System.currentTimeMillis();
    String sessionId = UUID.randomUUID().toString();
    jdbc.update(
      "insert into spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name) values (?, ?, ?, ?, ?, ?, ?)",
      sessionId,
      sessionId,
      now,
      now,
      14_400,
      now + 14_400_000,
      email
    );

    recovery.request(email, "192.0.2.19");

    org.mockito.ArgumentCaptor<SimpleMailMessage> mail =
      org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(mail.capture());
    String body = mail.getValue().getText();
    String token = java.util.regex.Pattern.compile(
      "#reset-password/([A-Za-z0-9_-]{40,50})"
    )
      .matcher(body)
      .results()
      .map(result -> result.group(1))
      .findFirst()
      .orElseThrow();
    String storedHash = jdbc.queryForObject(
      "select token_hash from password_reset_tokens where player_id = ?",
      String.class,
      playerId
    );
    String expectedHash = HexFormat.of().formatHex(
      MessageDigest.getInstance("SHA-256").digest(
        token.getBytes(StandardCharsets.UTF_8)
      )
    );
    assertThat(storedHash).isEqualTo(expectedHash).isNotEqualTo(token);
    assertThat(
      jdbc.queryForObject(
        "select count(*) from spring_session where principal_name = ?",
        Integer.class,
        email
      )
    ).isEqualTo(1);

    recovery.complete(
      new PasswordResetCompletion(token, "SenhaNova123!", "SenhaNova123!")
    );

    String passwordHash = jdbc.queryForObject(
      "select password from players where id = ?",
      String.class,
      playerId
    );
    assertThat(encoder.matches("SenhaNova123!", passwordHash)).isTrue();
    assertThat(
      jdbc.queryForObject(
        "select count(*) from spring_session where principal_name = ?",
        Integer.class,
        email
      )
    ).isZero();
    assertThat(
      jdbc.queryForObject(
        "select used_at is not null from password_reset_tokens where token_hash = ?",
        Boolean.class,
        storedHash
      )
    ).isTrue();
    assertThatThrownBy(() ->
      recovery.complete(
        new PasswordResetCompletion(token, "SenhaNova123!", "SenhaNova123!")
      )
    )
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("expirou ou já foi usado");
  }

  @Test
  void unknownEmailGetsNoAccountSignalAndRequestsAreRateLimited() {
    String email = "ausente-" + UUID.randomUUID() + "@example.test";
    for (int attempt = 0; attempt < 3; attempt++) recovery.request(
      email,
      "192.0.2.20"
    );
    verifyNoInteractions(mailSender);
    assertThat(
      jdbc.queryForObject(
        "select count(*) from password_reset_tokens",
        Integer.class
      )
    ).isZero();
    assertThatThrownBy(() -> recovery.request(email, "192.0.2.20"))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("Muitas tentativas");
  }

  @Test
  void onlyTheMostRecentlyRequestedLinkRemainsValid() {
    String email = "ultimolink-" + UUID.randomUUID() + "@example.test";
    tx.execute(status ->
      store.save(
        new Player("Jogador", email, encoder.encode("SenhaAntiga123!"))
      )
    );
    recovery.request(email, "192.0.2.23");
    recovery.request(email, "192.0.2.23");
    org.mockito.ArgumentCaptor<SimpleMailMessage> mail =
      org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, times(2)).send(mail.capture());
    List<String> tokens = mail
      .getAllValues()
      .stream()
      .map(SimpleMailMessage::getText)
      .map(body ->
        java.util.regex.Pattern.compile(
          "#reset-password/([A-Za-z0-9_-]{40,50})"
        )
          .matcher(body)
          .results()
          .map(result -> result.group(1))
          .findFirst()
          .orElseThrow()
      )
      .toList();
    assertThatThrownBy(() ->
      recovery.complete(
        new PasswordResetCompletion(
          tokens.getFirst(),
          "SenhaNova123!",
          "SenhaNova123!"
        )
      )
    ).isInstanceOf(ApiException.class);
    assertThatCode(() ->
      recovery.complete(
        new PasswordResetCompletion(
          tokens.get(1),
          "SenhaNova123!",
          "SenhaNova123!"
        )
      )
    ).doesNotThrowAnyException();
  }

  @Test
  void expiredTokensAndMismatchedPasswordsCannotChangeTheAccount() {
    String email = "expira-" + UUID.randomUUID() + "@example.test";
    UUID playerId = tx.execute(
      status ->
        store
          .save(new Player("Jogador", email, encoder.encode("SenhaAntiga123!")))
          .id
    );
    recovery.request(email, "192.0.2.21");
    org.mockito.ArgumentCaptor<SimpleMailMessage> mail =
      org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(mail.capture());
    String token = java.util.regex.Pattern.compile(
      "#reset-password/([A-Za-z0-9_-]{40,50})"
    )
      .matcher(mail.getValue().getText())
      .results()
      .map(result -> result.group(1))
      .findFirst()
      .orElseThrow();

    assertThatThrownBy(() ->
      recovery.complete(
        new PasswordResetCompletion(
          token,
          "SenhaNova123!",
          "SenhaDiferente123!"
        )
      )
    )
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("não são iguais");
    jdbc.update(
      "update password_reset_tokens set expires_at = now() - interval '1 second' where player_id = ?",
      playerId
    );
    assertThatThrownBy(() ->
      recovery.complete(
        new PasswordResetCompletion(token, "SenhaNova123!", "SenhaNova123!")
      )
    )
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("expirou ou já foi usado");
    String passwordHash = jdbc.queryForObject(
      "select password from players where id = ?",
      String.class,
      playerId
    );
    assertThat(encoder.matches("SenhaAntiga123!", passwordHash)).isTrue();
  }
}
