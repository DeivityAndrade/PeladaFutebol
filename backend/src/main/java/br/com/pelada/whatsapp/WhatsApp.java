package br.com.pelada.whatsapp;

import br.com.pelada.domain.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WhatsApp {

  public static final String TEXT_VERSION = "whatsapp-v1";
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final boolean enabled;
  private final String businessNumber, phoneId, appSecret, verifyToken;
  private final SecureRandom random = new SecureRandom();

  public WhatsApp(
    JdbcTemplate jdbc,
    Clock clock,
    @Value("${app.whatsapp.enabled:false}") boolean enabled,
    @Value("${app.whatsapp.business-number:}") String businessNumber,
    @Value("${app.whatsapp.phone-id:}") String phoneId,
    @Value("${app.whatsapp.app-secret:}") String appSecret,
    @Value("${app.whatsapp.verify-token:}") String verifyToken
  ) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.enabled = enabled;
    this.businessNumber = businessNumber;
    this.phoneId = phoneId;
    this.appSecret = appSecret;
    this.verifyToken = verifyToken;
  }

  public record Preference(
    UUID clubId,
    String name,
    boolean invitations,
    boolean reminders
  ) {}

  public record Status(
    boolean available,
    String phone,
    boolean verified,
    boolean stopped,
    UUID challengeId,
    String pendingPhone,
    Instant expiresAt,
    String textVersion,
    List<Preference> groups
  ) {}

  public record Challenge(
    UUID id,
    String code,
    String url,
    Instant expiresAt
  ) {}

  public record Choice(
    boolean invitations,
    boolean reminders,
    String textVersion
  ) {}

  public boolean available() {
    return (
      enabled &&
      businessNumber.matches("[1-9][0-9]{7,14}") &&
      !phoneId.isBlank() &&
      !appSecret.isBlank() &&
      !verifyToken.isBlank()
    );
  }

  // All contact/consent mutations take this short database lock. It also protects
  // uniqueness and STOP versus confirmation across multiple backend instances.
  private void lock() {
    jdbc.execute("SELECT pg_advisory_xact_lock(742109812)");
    Instant now = clock.instant();
    jdbc.update(
      "UPDATE whatsapp_contacts SET code_hash=NULL,challenge_id=NULL,pending_phone=NULL,expires_at=NULL WHERE expires_at<=?",
      time(now)
    );
    jdbc.update(
      "DELETE FROM whatsapp_received_events WHERE received_at<?",
      time(now.minus(Duration.ofDays(30)))
    );
    jdbc.update(
      "DELETE FROM whatsapp_consent_events WHERE created_at<?",
      time(now.minus(Duration.ofDays(90)))
    );
  }

  private Map<String, Object> contact(UUID user) {
    return jdbc
      .queryForList("SELECT * FROM whatsapp_contacts WHERE player_id=?", user)
      .stream()
      .findFirst()
      .orElse(Map.of());
  }

  @Transactional(readOnly = true)
  public Status status(UUID user) {
    var c = contact(user);
    Instant expires = instant(c.get("expires_at"));
    boolean pending = expires != null && expires.isAfter(clock.instant());
    var groups = jdbc.query(
      """
      SELECT c.id,c.name,coalesce(w.invitations,false) invitations,coalesce(w.reminders,false) reminders
      FROM members m JOIN clubs c ON c.id=m.club_id
      LEFT JOIN whatsapp_preferences w ON w.member_id=m.id
      WHERE m.player_id=? AND NOT c.demo ORDER BY c.name,c.id
      """,
      (r, n) ->
        new Preference(
          r.getObject("id", UUID.class),
          r.getString("name"),
          r.getBoolean("invitations"),
          r.getBoolean("reminders")
        ),
      user
    );
    return new Status(
      available(),
      mask((String) c.get("phone")),
      c.get("phone") != null,
      c.get("stopped_at") != null,
      pending ? (UUID) c.get("challenge_id") : null,
      pending ? mask((String) c.get("pending_phone")) : null,
      pending ? expires : null,
      TEXT_VERSION,
      groups
    );
  }

  public Challenge begin(UUID user) {
    if (!available()) throw new ApiException(
      503,
      "A vinculação do WhatsApp ainda está em preparação."
    );
    lock();
    jdbc.update(
      "INSERT INTO whatsapp_contacts(player_id) VALUES (?) ON CONFLICT DO NOTHING",
      user
    );
    var c = contact(user);
    Instant now = clock.instant(),
      issued = instant(c.get("issued_at")),
      window = instant(c.get("window_start"));
    if (
      issued != null && issued.plusSeconds(60).isAfter(now)
    ) throw new ApiException(
      429,
      "Aguarde um minuto antes de gerar outro código."
    );
    int count = (Integer) c.get("window_count");
    if (window == null || !window.plusSeconds(3600).isAfter(now)) {
      window = now;
      count = 0;
    }
    if (count >= 3) throw new ApiException(
      429,
      "Você já gerou três códigos nesta hora. Tente mais tarde."
    );
    byte[] bytes = new byte[12];
    random.nextBytes(bytes);
    String code = HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
    UUID id = UUID.randomUUID();
    Instant expiry = now.plusSeconds(600);
    jdbc.update(
      """
      UPDATE whatsapp_contacts SET code_hash=?,challenge_id=?,pending_phone=NULL,
      expires_at=?,issued_at=?,window_start=?,window_count=? WHERE player_id=?
      """,
      hash(code),
      id,
      time(expiry),
      time(now),
      time(window),
      count + 1,
      user
    );
    String url =
      "https://wa.me/" +
      businessNumber +
      "?text=" +
      URLEncoder.encode("VINCULAR " + code, StandardCharsets.UTF_8);
    return new Challenge(id, code, url, expiry);
  }

  public Status confirm(UUID user, UUID challengeId) {
    if (!available()) throw new ApiException(
      503,
      "A vinculação do WhatsApp ainda está em preparação."
    );
    lock();
    var c = contact(user);
    String phone = (String) c.get("pending_phone");
    if (
      phone == null || !challengeId.equals(c.get("challenge_id"))
    ) throw ApiException.conflict(
      "Envie o código pelo WhatsApp e confira o número antes de confirmar. O código vale por dez minutos."
    );
    if (
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_contacts WHERE phone=? AND player_id<>?",
        Integer.class,
        phone,
        user
      ) > 0
    ) throw ApiException.conflict(
      "Não foi possível vincular esse número. Confira a conta utilizada ou desconecte o vínculo anterior."
    );
    // Reverification never carries consent forward, including after SAIR.
    revoke(user, "NUMBER_LINK");
    jdbc.update(
      """
      UPDATE whatsapp_contacts SET phone=?,verified_at=?,stopped_at=NULL,
      code_hash=NULL,challenge_id=NULL,pending_phone=NULL,expires_at=NULL WHERE player_id=?
      """,
      phone,
      time(clock.instant()),
      user
    );
    return status(user);
  }

  public Status disconnect(UUID user) {
    lock();
    revoke(user, "DISCONNECT");
    jdbc.update(
      """
      UPDATE whatsapp_contacts SET phone=NULL,verified_at=NULL,stopped_at=NULL,
      code_hash=NULL,challenge_id=NULL,pending_phone=NULL,expires_at=NULL WHERE player_id=?
      """,
      user
    );
    return status(user);
  }

  public Status choose(UUID user, UUID club, Choice choice) {
    lock();
    var members = jdbc.queryForList(
      "SELECT m.id FROM members m JOIN clubs c ON c.id=m.club_id WHERE m.player_id=? AND m.club_id=? AND NOT c.demo FOR UPDATE OF m",
      user,
      club
    );
    if (members.isEmpty()) throw ApiException.forbidden();
    if (!TEXT_VERSION.equals(choice.textVersion())) throw ApiException.conflict(
      "Atualize a página para conferir o texto de autorização."
    );
    var c = contact(user);
    if (
      (choice.invitations() || choice.reminders()) &&
      (c.get("phone") == null || c.get("stopped_at") != null)
    ) throw ApiException.conflict(
      "Vincule seu WhatsApp antes de autorizar os avisos. Após SAIR, confirme novamente o número."
    );
    UUID member = (UUID) members.getFirst().get("id");
    jdbc.update(
      """
      INSERT INTO whatsapp_preferences(member_id,invitations,reminders,text_version,updated_at) VALUES(?,?,?,?,?)
      ON CONFLICT(member_id) DO UPDATE SET invitations=EXCLUDED.invitations,reminders=EXCLUDED.reminders,
      text_version=EXCLUDED.text_version,updated_at=EXCLUDED.updated_at
      """,
      member,
      choice.invitations(),
      choice.reminders(),
      TEXT_VERSION,
      time(clock.instant())
    );
    event(user, club, choice.invitations(), choice.reminders(), "SITE");
    return status(user);
  }

  private void event(
    UUID user,
    UUID club,
    boolean invitations,
    boolean reminders,
    String reason
  ) {
    jdbc.update(
      "INSERT INTO whatsapp_consent_events VALUES(?,?,?,?,?,?,?,?)",
      UUID.randomUUID(),
      user,
      club,
      invitations,
      reminders,
      reason,
      TEXT_VERSION,
      time(clock.instant())
    );
  }

  private void revoke(UUID user, String reason) {
    jdbc
      .query(
        """
        SELECT m.club_id FROM whatsapp_preferences w JOIN members m ON m.id=w.member_id
        WHERE m.player_id=? AND (w.invitations OR w.reminders)
        """,
        (r, n) -> r.getObject(1, UUID.class),
        user
      )
      .forEach(club -> event(user, club, false, false, reason));
    jdbc.update(
      "UPDATE whatsapp_preferences SET invitations=false,reminders=false,updated_at=? WHERE member_id IN (SELECT id FROM members WHERE player_id=?)",
      time(clock.instant()),
      user
    );
  }

  public String challenge(String mode, String token, String challenge) {
    if (!available()) throw new ApiException(503, "WhatsApp em preparação.");
    if (
      !"subscribe".equals(mode) ||
      token == null ||
      !MessageDigest.isEqual(
        token.getBytes(StandardCharsets.UTF_8),
        verifyToken.getBytes(StandardCharsets.UTF_8)
      ) ||
      challenge == null ||
      !challenge.matches("[0-9]{1,100}")
    ) throw ApiException.forbidden();
    return challenge;
  }

  public void authenticate(byte[] body, String signature) {
    if (!available()) throw new ApiException(503, "WhatsApp em preparação.");
    if (body.length > 131072) throw new ApiException(
      413,
      "Evento muito grande."
    );
    try {
      if (
        signature == null || !signature.matches("sha256=[0-9a-f]{64}")
      ) throw ApiException.forbidden();
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
        new SecretKeySpec(
          appSecret.getBytes(StandardCharsets.UTF_8),
          "HmacSHA256"
        )
      );
      if (
        !MessageDigest.isEqual(
          mac.doFinal(body),
          HexFormat.of().parseHex(signature.substring(7))
        )
      ) throw ApiException.forbidden();
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Falha na autenticação do webhook", ex);
    }
  }

  public record Incoming(String id, String from, String text, long timestamp) {}

  public void receive(List<Incoming> messages) {
    lock();
    for (var message : messages) {
      String phone = "+" + message.from();
      if (
        message.timestamp() <= 0 ||
        message.timestamp() > clock.instant().getEpochSecond() + 5 ||
        message.id() == null ||
        message.id().isBlank() ||
        message.id().length() > 256 ||
        !phone.matches("\\+[1-9][0-9]{7,14}")
      ) continue;
      if (
        jdbc.update(
          "INSERT INTO whatsapp_received_events VALUES(?,?) ON CONFLICT DO NOTHING",
          hash(message.id()),
          time(clock.instant())
        ) == 0
      ) continue;
      String text = message.text().strip().toUpperCase(Locale.ROOT);
      if (text.equals("SAIR")) {
        for (var row : jdbc.queryForList(
          "SELECT player_id FROM whatsapp_contacts WHERE (phone=? AND verified_at<=?) OR (pending_phone=? AND issued_at<=?)",
          phone,
          time(Instant.ofEpochSecond(message.timestamp()).plusSeconds(1)),
          phone,
          time(Instant.ofEpochSecond(message.timestamp()).plusSeconds(1))
        )) {
          UUID user = (UUID) row.get("player_id");
          revoke(user, "STOP");
          jdbc.update(
            "UPDATE whatsapp_contacts SET stopped_at=?,code_hash=NULL,challenge_id=NULL,pending_phone=NULL,expires_at=NULL WHERE player_id=?",
            time(clock.instant()),
            user
          );
        }
      } else if (text.matches("VINCULAR [0-9A-F]{24}")) {
        // Consume the code once, but require a second confirmation in the authenticated site.
        jdbc.update(
          """
          UPDATE whatsapp_contacts SET pending_phone=?,code_hash=NULL WHERE code_hash=?
          AND expires_at>? AND issued_at<=?
          """,
          phone,
          hash(text.substring(9)),
          time(clock.instant()),
          time(Instant.ofEpochSecond(message.timestamp()).plusSeconds(5))
        );
      }
    }
  }

  public String phoneId() {
    return phoneId;
  }

  public void cleanup() {
    lock();
  }

  static String hash(String text) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          text.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  static String mask(String phone) {
    return phone == null
      ? null
      : phone.substring(0, Math.min(3, phone.length() - 4)) +
          " •••• " +
          phone.substring(phone.length() - 4);
  }

  private static Timestamp time(Instant instant) {
    return Timestamp.from(instant);
  }

  private static Instant instant(Object value) {
    return value == null ? null : ((Timestamp) value).toInstant();
  }
}
