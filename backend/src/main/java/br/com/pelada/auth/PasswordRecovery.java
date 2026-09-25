package br.com.pelada.auth;

import br.com.pelada.api.Contracts.PasswordResetCompletion;
import br.com.pelada.domain.ApiException;
import br.com.pelada.domain.Domain.Player;
import br.com.pelada.domain.Store;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordRecovery {

  private static final Logger log = LoggerFactory.getLogger(
    PasswordRecovery.class
  );
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
  private static final Duration RATE_WINDOW = Duration.ofHours(1);

  private final Store store;
  private final JdbcTemplate jdbc;
  private final org.springframework.security.crypto.password.PasswordEncoder encoder;
  private final ObjectProvider<JavaMailSender> mailSender;
  private final Clock clock;
  private final String mailHost;
  private final String mailFrom;
  private final String mailUsername;
  private final String mailPassword;
  private final String publicAppUrl;

  public PasswordRecovery(
    Store store,
    JdbcTemplate jdbc,
    org.springframework.security.crypto.password.PasswordEncoder encoder,
    ObjectProvider<JavaMailSender> mailSender,
    Clock clock,
    @Value("${spring.mail.host:}") String mailHost,
    @Value("${spring.mail.username:}") String mailUsername,
    @Value("${spring.mail.password:}") String mailPassword,
    @Value("${app.mail-from:}") String mailFrom,
    @Value("${app.public-app-url:}") String publicAppUrl
  ) {
    this.store = store;
    this.jdbc = jdbc;
    this.encoder = encoder;
    this.mailSender = mailSender;
    this.clock = clock;
    this.mailHost = mailHost;
    this.mailUsername = mailUsername;
    this.mailPassword = mailPassword;
    this.mailFrom = mailFrom;
    this.publicAppUrl = publicAppUrl;
  }

  @Transactional
  public void request(String rawEmail, String clientAddress) {
    JavaMailSender sender = mailSender.getIfAvailable();
    if (
      sender == null ||
      blank(mailHost) ||
      blank(mailUsername) ||
      blank(mailPassword) ||
      blank(mailFrom) ||
      blank(publicAppUrl)
    ) throw new ApiException(
      503,
      "A recuperação de senha está indisponível porque o envio de e-mail não foi configurado no servidor."
    );

    Instant now = clock.instant();
    jdbc.update(
      "delete from password_reset_tokens where expires_at <= ?",
      Timestamp.from(now)
    );
    jdbc.update(
      "delete from password_reset_limits where window_started_at < ?",
      Timestamp.from(now.minus(Duration.ofDays(2)))
    );
    String email = Accounts.normalize(rawEmail);
    enforceLimit("ip:" + safeAddress(clientAddress), 10, now);
    enforceLimit("email:" + email, 3, now);

    Optional<Player> player = store.first(
      Player.class,
      "from Player where email=:email",
      "email",
      email
    );
    if (player.isEmpty()) return;

    // Serialize replacement requests and keep lock ordering consistent with completion.
    Player account = store.lock(Player.class, player.get().id);
    byte[] random = new byte[32];
    RANDOM.nextBytes(random);
    String rawToken = java.util.Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(random);
    String tokenHash = sha256(rawToken);
    Instant expiresAt = now.plus(TOKEN_LIFETIME);
    jdbc.update(
      "delete from password_reset_tokens where player_id = ?",
      account.id
    );
    jdbc.update(
      "insert into password_reset_tokens (id, player_id, token_hash, expires_at, created_at) values (?, ?, ?, ?, ?)",
      UUID.randomUUID(),
      account.id,
      tokenHash,
      Timestamp.from(expiresAt),
      Timestamp.from(now)
    );

    String baseUrl = publicAppUrl.replaceAll("/+$", "");
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(mailFrom);
    message.setTo(account.email);
    message.setSubject("Redefina sua senha do Tô Dentro");
    message.setText(
      "Olá, " +
        account.name +
        ".\n\n" +
        "Recebemos um pedido para trocar a senha da sua conta. Use este link em até 30 minutos:\n" +
        baseUrl +
        "/#reset-password/" +
        rawToken +
        "\n\nSe você não pediu a troca, ignore esta mensagem."
    );
    try {
      sender.send(message);
    } catch (RuntimeException ex) {
      // Never include the address, message body or token in application logs.
      log.warn(
        "O provedor de e-mail recusou uma mensagem de recuperação de senha."
      );
      jdbc.update(
        "delete from password_reset_tokens where token_hash = ?",
        tokenHash
      );
    }
  }

  @Transactional
  public void complete(PasswordResetCompletion input) {
    if (
      !input.newPassword().equals(input.confirmPassword())
    ) throw new ApiException(400, "As senhas informadas não são iguais.");
    if (
      input.newPassword().getBytes(StandardCharsets.UTF_8).length > 72
    ) throw new ApiException(400, "A senha deve ter no máximo 72 bytes.");

    String tokenHash = sha256(input.token());
    List<UUID> players = jdbc.query(
      "select player_id from password_reset_tokens where token_hash = ?",
      (result, row) -> result.getObject("player_id", UUID.class),
      tokenHash
    );
    if (players.isEmpty()) throw new ApiException(
      400,
      "Este link expirou ou já foi usado. Peça um novo link de recuperação."
    );

    Player player = store.lock(Player.class, players.getFirst());
    List<UUID> usableToken = jdbc.query(
      "select player_id from password_reset_tokens where token_hash = ? and expires_at > ? and used_at is null for update",
      (result, row) -> result.getObject("player_id", UUID.class),
      tokenHash,
      Timestamp.from(clock.instant())
    );
    if (usableToken.isEmpty()) throw new ApiException(
      400,
      "Este link expirou ou já foi usado. Peça um novo link de recuperação."
    );
    player.password = encoder.encode(input.newPassword());
    Instant now = clock.instant();
    jdbc.update(
      "update password_reset_tokens set used_at = ? where token_hash = ?",
      Timestamp.from(now),
      tokenHash
    );
    jdbc.update(
      "delete from password_reset_tokens where player_id = ? and token_hash <> ?",
      player.id,
      tokenHash
    );
    // Spring Session uses the normalized e-mail as principal_name. This revokes all devices.
    jdbc.update(
      "delete from spring_session where principal_name = ?",
      player.email
    );
  }

  private void enforceLimit(String bucket, int maximum, Instant now) {
    String hash = sha256(bucket);
    Instant cutoff = now.minus(RATE_WINDOW);
    Integer attempts = jdbc.queryForObject(
      """
      insert into password_reset_limits (bucket_hash, window_started_at, attempts)
      values (?, ?, 1)
      on conflict (bucket_hash) do update set
        window_started_at = case when password_reset_limits.window_started_at <= ? then ? else password_reset_limits.window_started_at end,
        attempts = case when password_reset_limits.window_started_at <= ? then 1 else password_reset_limits.attempts + 1 end
      returning attempts
      """,
      Integer.class,
      hash,
      Timestamp.from(now),
      Timestamp.from(cutoff),
      Timestamp.from(now),
      Timestamp.from(cutoff)
    );
    if (attempts == null || attempts > maximum) throw new ApiException(
      429,
      "Muitas tentativas. Aguarde um pouco antes de pedir outro link."
    );
  }

  private static String safeAddress(String address) {
    return blank(address) ? "unknown" : address.strip();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          value.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }
}
