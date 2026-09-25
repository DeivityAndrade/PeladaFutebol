package br.com.pelada;

import static org.assertj.core.api.Assertions.*;

import br.com.pelada.auth.PasswordRecovery;
import br.com.pelada.domain.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PasswordRecoveryConfigurationTest {

  @Autowired
  PasswordRecovery recovery;

  @Test
  void doesNotAcceptRequestsUntilMailDeliveryIsConfigured() {
    assertThatThrownBy(() ->
      recovery.request("alguem@example.test", "192.0.2.22")
    ).isInstanceOfSatisfying(ApiException.class, error -> {
      assertThat(error.status).isEqualTo(503);
      assertThat(error.getMessage()).contains(
        "envio de e-mail não foi configurado"
      );
    });
  }
}
