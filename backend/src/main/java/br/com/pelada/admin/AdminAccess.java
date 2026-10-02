package br.com.pelada.admin;

import br.com.pelada.domain.Domain.Player;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Access is granted only by server configuration, never by a registration field. */
@Component
public class AdminAccess {

  private final Set<String> emails;

  public AdminAccess(@Value("${app.admin-emails:}") String configuredEmails) {
    emails = Arrays.stream(configuredEmails.split(","))
      .map(email -> email.strip().toLowerCase(Locale.ROOT))
      .filter(email -> !email.isEmpty())
      .collect(Collectors.toUnmodifiableSet());
  }

  public boolean allowed(Player player) {
    return (
      player != null &&
      !"!disabled".equals(player.password) &&
      emails.contains(player.email.toLowerCase(Locale.ROOT))
    );
  }
}
