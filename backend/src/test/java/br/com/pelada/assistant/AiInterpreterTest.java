package br.com.pelada.assistant;

import static br.com.pelada.assistant.AssistantContracts.*;
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

class AiInterpreterTest {

  private final JsonMapper json = JsonMapper.builder()
    .findAndAddModules()
    .build();

  Context context() {
    return new Context(
      "Turma",
      "America/Sao_Paulo",
      Instant.parse("2026-10-08T15:00:00Z"),
      null
    );
  }

  @Test
  void sendsStrictSchemaAndReadsStructuredDraftWithoutActions() {
    var builder = RestClient.builder().baseUrl(
      "https://api.example.invalid/v1"
    );
    var server = MockRestServiceServer.bindTo(builder).build();
    var interpreter = new AiInterpreter(
      builder.build(),
      true,
      "test-key",
      "test-model"
    );
    var result = new Interpretation(
      "Pelada",
      "Arena",
      LocalDate.of(2026, 10, 10),
      LocalTime.of(19, 0),
      2,
      7,
      false,
      null,
      null
    );
    server
      .expect(requestTo("https://api.example.invalid/v1/chat/completions"))
      .andExpect(header("Authorization", "Bearer test-key"))
      .andExpect(
        content().json(
          """
          {"model":"test-model","store":false,"max_completion_tokens":1000,
           "response_format":{"type":"json_schema","json_schema":{"strict":true,"schema":{"additionalProperties":false}}}}
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
                  Map.of("content", json.writeValueAsString(result))
                )
              )
            )
          ),
          MediaType.APPLICATION_JSON
        )
      );
    assertThat(
      interpreter.interpret("Sábado às 19h na Arena", context())
    ).isEqualTo(result);
    server.verify();
  }

  @Test
  void refusalMalformedOutputAndProviderErrorUseSafeMessage() {
    for (String response : List.of(
      "{}",
      "{\"choices\":[{\"finish_reason\":\"length\"}]}",
      "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"refusal\":\"Não\"}}]}"
    )) {
      var builder = RestClient.builder().baseUrl("https://api.example.invalid");
      var server = MockRestServiceServer.bindTo(builder).build();
      server
        .expect(anything())
        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
      var interpreter = new AiInterpreter(
        builder.build(),
        true,
        "private-key",
        "test-model"
      );
      assertThatThrownBy(() ->
        interpreter.interpret("Pedido", context())
      ).isInstanceOfSatisfying(ApiException.class, ex -> {
        assertThat(ex.status).isEqualTo(503);
        assertThat(ex.getMessage()).doesNotContain("private-key", response);
      });
    }
    var builder = RestClient.builder().baseUrl("https://api.example.invalid");
    var server = MockRestServiceServer.bindTo(builder).build();
    server
      .expect(anything())
      .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private-key"));
    var interpreter = new AiInterpreter(
      builder.build(),
      true,
      "private-key",
      "test-model"
    );
    assertThatThrownBy(() ->
      interpreter.interpret("Pedido", context())
    ).hasMessage(
      "Não consegui interpretar agora. Tente novamente ou use o formulário."
    );
  }
}
