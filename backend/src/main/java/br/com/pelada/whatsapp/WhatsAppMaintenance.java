package br.com.pelada.whatsapp;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class WhatsAppMaintenance {

  private final WhatsApp whatsapp;

  public WhatsAppMaintenance(WhatsApp whatsapp) {
    this.whatsapp = whatsapp;
  }

  // Local maintenance only; never sends messages or keeps hosting awake.
  @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
  public void cleanup() {
    whatsapp.cleanup();
  }
}
