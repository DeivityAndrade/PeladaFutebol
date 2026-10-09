package br.com.pelada.whatsapp;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class WhatsAppMaintenance {

  private final WhatsApp whatsapp;
  private final WhatsAppInbox inbox;
  private final WhatsAppOutbox outbox;

  public WhatsAppMaintenance(
    WhatsApp whatsapp,
    WhatsAppInbox inbox,
    WhatsAppOutbox outbox
  ) {
    this.whatsapp = whatsapp;
    this.inbox = inbox;
    this.outbox = outbox;
  }

  // Local maintenance only; never sends messages or keeps hosting awake.
  @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
  public void cleanup() {
    whatsapp.cleanup();
    inbox.cleanup();
    outbox.reconcile();
  }
}
