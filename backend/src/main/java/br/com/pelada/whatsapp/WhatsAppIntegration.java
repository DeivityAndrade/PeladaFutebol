package br.com.pelada.whatsapp;

import br.com.pelada.domain.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppIntegration {

  public final boolean enabled, sendEnabled, repliesOnly;
  public final String token, publicUrl, graphVersion, invitationTemplate, reminderTemplate;
  public final int dailyLimit;
  public final String provider, gupshupAppId;
  private final String gupshupWebhookToken;
  private final Set<String> pilot;

  public WhatsAppIntegration(
    @Value("${app.whatsapp.automation-enabled:false}") boolean enabled,
    @Value("${app.whatsapp.send-enabled:false}") boolean sendEnabled,
    @Value("${app.whatsapp.replies-only:false}") boolean repliesOnly,
    @Value("${app.whatsapp.integration-token:}") String token,
    @Value("${app.public-app-url:}") String publicUrl,
    @Value("${app.whatsapp.graph-version:v26.0}") String graphVersion,
    @Value("${app.whatsapp.invitation-template:}") String invitationTemplate,
    @Value("${app.whatsapp.reminder-template:}") String reminderTemplate,
    @Value("${app.whatsapp.pilot-numbers:}") String pilot,
    @Value("${app.whatsapp.daily-send-limit:20}") int dailyLimit,
    @Value("${app.whatsapp.provider:META}") String provider,
    @Value("${app.whatsapp.gupshup-app-id:}") String gupshupAppId,
    @Value("${app.whatsapp.gupshup-webhook-token:}") String gupshupWebhookToken
  ) {
    this.enabled = enabled;
    this.sendEnabled = sendEnabled;
    this.repliesOnly = repliesOnly;
    this.token = token;
    this.publicUrl = publicUrl.replaceAll("/+$", "");
    this.graphVersion = graphVersion;
    this.invitationTemplate = invitationTemplate;
    this.reminderTemplate = reminderTemplate;
    this.pilot = new HashSet<>();
    for (String number : pilot.split(","))
      if (number.strip().matches("[1-9][0-9]{7,14}")) this.pilot.add(
        number.strip()
      );
    this.dailyLimit = Math.max(1, Math.min(1000, dailyLimit));
    this.provider = provider.strip().toUpperCase(Locale.ROOT);
    this.gupshupAppId = gupshupAppId;
    this.gupshupWebhookToken = gupshupWebhookToken;
  }

  public boolean gupshup() {
    return provider.equals("GUPSHUP");
  }

  public boolean transportAvailable() {
    return provider.equals("META") ||
      (gupshup() && gupshupAppId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") &&
        gupshupWebhookToken.matches("[0-9a-fA-F]{64}"));
  }

  public void authenticateGupshup(byte[] body, String suppliedToken) {
    if (!gupshup() || !transportAvailable()) throw new ApiException(503, "WhatsApp em preparação.");
    if (body.length > 131072) throw new ApiException(413, "Evento muito grande.");
    if (suppliedToken == null || !MessageDigest.isEqual(
      gupshupWebhookToken.getBytes(StandardCharsets.UTF_8),
      suppliedToken.getBytes(StandardCharsets.UTF_8)
    )) throw new ApiException(401, "Credencial de webhook inválida.");
  }

  public String deliveryUrl(String phoneId) {
    return gupshup()
      ? "https://api.gupshup.io/wa/app/" + gupshupAppId + "/v3/msg"
      : "https://graph.facebook.com/" + graphVersion + "/" + phoneId + "/messages";
  }

  public boolean available() {
    return enabled && transportAvailable() && token.length() >= 32 && token.length() <= 256;
  }

  public boolean deliveryAvailable() {
    return (
      available() &&
      sendEnabled &&
      (!gupshup() || repliesOnly) &&
      !pilot.isEmpty() &&
      publicUrl.matches(
        "https://[^\\s?#]+|http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?"
      ) &&
      graphVersion.matches("v[0-9]{1,3}\\.0") &&
      (repliesOnly ||
        (invitationTemplate.matches("[a-z0-9_]{1,100}") &&
          reminderTemplate.matches("[a-z0-9_]{1,100}")))
    );
  }

  public boolean permits(String phone) {
    return phone != null && pilot.contains(phone.replaceFirst("^\\+", ""));
  }

  public void authenticate(String authorization) {
    if (!available()) throw new ApiException(
      503,
      "Integração do WhatsApp em preparação."
    );
    if (
      authorization == null ||
      !MessageDigest.isEqual(
        ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
        authorization.getBytes(StandardCharsets.UTF_8)
      )
    ) throw new ApiException(401, "Credencial de integração inválida.");
  }
}
