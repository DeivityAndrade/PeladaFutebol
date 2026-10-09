package br.com.pelada.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import br.com.pelada.domain.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class AgentInterpreterTest {

  @Test
  void usesStrictDecisionSchemaAndNeverReturnsProviderErrors() {
    var builder = RestClient.builder().baseUrl(
      "https://agent.example.invalid/v1"
    );
    var server = MockRestServiceServer.bindTo(builder).build();
    var interpreter = new AgentInterpreter(
      builder.build(),
      true,
      "test-key",
      "test-model"
    );
    var json = JsonMapper.builder().findAndAddModules().build();
    var decision = new AgentContracts.Decision(
      "LIST",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    );
    var context = new AgentContracts.Context(
      Instant.parse("2026-10-09T18:00:00Z"),
      null,
      List.of(),
      null,
      List.of()
    );
    server
      .expect(requestTo("https://agent.example.invalid/v1/chat/completions"))
      .andExpect(header("Authorization", "Bearer test-key"))
      .andExpect(
        content().json(
          """
          {"model":"test-model","store":false,"response_format":{"type":"json_schema",
            "json_schema":{"strict":true,"schema":{"additionalProperties":false,
              "properties":{"action":{"enum":["CREATE","LIST","ATTEND","DECLINE","HELP"]}}}}}}
          """
        )
      )
      .andRespond(
        withSuccess(
          json.writeValueAsString(
            Map.of(
              "choices",
              List.of(
                Map.of(
                  "finish_reason",
                  "stop",
                  "message",
                  Map.of("content", json.writeValueAsString(decision))
                )
              )
            )
          ),
          MediaType.APPLICATION_JSON
        )
      );
    server
      .expect(requestTo("https://agent.example.invalid/v1/chat/completions"))
      .andRespond(
        withStatus(HttpStatus.UNAUTHORIZED).body(
          "secret test-key provider detail"
        )
      );
    assertThat(interpreter.decide("Agenda", context)).isEqualTo(decision);
    assertThatThrownBy(() ->
      interpreter.decide("Agenda", context)
    ).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status).isEqualTo(503);
      assertThat(e.getMessage()).doesNotContain("secret", "test-key");
    });
    server.verify();
  }
}
