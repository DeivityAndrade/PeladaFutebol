package br.com.pelada.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BrevoEmailSenderTest {

  @Test
  void sendsPasswordResetAsTransactionalHttpsApiRequest() {
    RestClient.Builder clientBuilder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(
      clientBuilder
    ).build();
    BrevoEmailSender sender = new BrevoEmailSender(
      clientBuilder.baseUrl("https://api.brevo.com/v3").build(),
      "brevo-test-key",
      "noreply@example.test"
    );

    server
      .expect(requestTo("https://api.brevo.com/v3/smtp/email"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(header("api-key", "brevo-test-key"))
      .andExpect(
        content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
      )
      .andExpect(
        content().json(
          """
          {
            "sender": {"name": "Pelada", "email": "noreply@example.test"},
            "to": [{"email": "jogador@example.test", "name": "Jogador"}],
            "subject": "Redefina sua senha",
            "textContent": "Use este link uma única vez."
          }
          """
        )
      )
      .andRespond(
        withSuccess(
          "{\"messageId\":\"message-test\"}",
          MediaType.APPLICATION_JSON
        )
      );

    assertThat(sender.isConfigured()).isTrue();
    sender.send(
      new BrevoEmailSender.RecoveryEmail(
        "jogador@example.test",
        "Jogador",
        "Redefina sua senha",
        "Use este link uma única vez."
      )
    );

    server.verify();
  }
}
