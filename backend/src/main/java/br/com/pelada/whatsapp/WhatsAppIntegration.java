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
    @Value("${app.whatsapp.daily-send-limit:20}") int dailyLimit
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
  }

  public boolean available() {
    return enabled && token.length() >= 32 && token.length() <= 256;
  }

  public boolean deliveryAvailable() {
    return (
      available() &&
      sendEnabled &&
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
