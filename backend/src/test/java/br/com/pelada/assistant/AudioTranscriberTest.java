package br.com.pelada.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import br.com.pelada.domain.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest
@ActiveProfiles("test")
class AudioTranscriberTest {

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  TransactionTemplate tx;

  @Value("${spring.datasource.url}")
  String database;

  final byte[] recording = { 0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 0, 1 };
  final UUID user = UUID.randomUUID();
  final Clock clock = Clock.fixed(
    Instant.parse("2026-10-09T12:00:00Z"),
    ZoneOffset.UTC
  );
  MockRestServiceServer server;
  AudioTranscriber transcriber;

  @BeforeEach
  void setup() {
    assertThat(database).matches(
      "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/pelada[a-z_]*test"
    );
    jdbc.execute("TRUNCATE assistant_request_limits");
    var builder = RestClient.builder().baseUrl("https://audio.invalid/v1");
    server = MockRestServiceServer.bindTo(builder).build();
    transcriber = new AudioTranscriber(
      builder.build(),
      true,
      "fake-key",
      "gpt-4o-mini-transcribe",
      20,
      jdbc,
      tx,
      clock
    );
  }

  void answer(String text) {
    server
      .expect(requestTo("https://audio.invalid/v1/audio/transcriptions"))
      .andExpect(header("Authorization", "Bearer fake-key"))
      .andExpect(request -> {
        assertThat(
          request
            .getHeaders()
            .getContentType()
            .isCompatibleWith(MediaType.MULTIPART_FORM_DATA)
        ).isTrue();
        String body = request.getBody().toString();
        assertThat(body)
          .contains("pedido.webm", "gpt-4o-mini-transcribe")
          .doesNotContain(user.toString());
      })
      .andRespond(
        withSuccess("{\"text\":\"" + text + "\"}", MediaType.APPLICATION_JSON)
      );
  }

  @Test
  void transcriptNeverExecutesAndAttemptsAreLimitedBeforeProviderCall() {
    int proposals = jdbc.queryForObject(
      "SELECT count(*) FROM assistant_proposals",
      Integer.class
    );
    for (int i = 0; i < 5; i++) answer("Jogo sexta às 19h");
    for (int i = 0; i < 5; i++) assertThat(
      transcriber.transcribe(user, recording, "audio/webm;codecs=opus").text()
    ).contains("sexta");
    assertThatThrownBy(() ->
      transcriber.transcribe(user, recording, "audio/webm")
    ).isInstanceOfSatisfying(ApiException.class, e ->
      assertThat(e.status).isEqualTo(429)
    );
    server.verify();
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM assistant_proposals",
        Integer.class
      )
    ).isEqualTo(proposals);
  }

  @Test
  void rejectsInvalidBytesAndOversizeBeforeSpendingAndHidesProviderErrors() {
    assertThatThrownBy(() ->
      transcriber.transcribe(user, new byte[] { 1, 2, 3, 4 }, "audio/webm")
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      transcriber.transcribe(
        user,
        new byte[AudioTranscriber.MAX_BYTES + 1],
        "audio/webm"
      )
    ).isInstanceOf(ApiException.class);
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM assistant_request_limits",
        Integer.class
      )
    ).isZero();
    server
      .expect(anything())
      .andRespond(withServerError().body("private-key-and-recording"));
    assertThatThrownBy(() ->
      transcriber.transcribe(user, recording, "audio/webm")
    )
      .hasMessageNotContaining("private-key")
      .isInstanceOfSatisfying(ApiException.class, e ->
        assertThat(e.status).isEqualTo(503)
      );
    assertThat(
      jdbc.queryForObject(
        "SELECT max(requests) FROM assistant_request_limits",
        Integer.class
      )
    ).isEqualTo(1);
    server.verify();
  }

  @Test
  void exhaustedDailyQuotaRollsBackTheUserCounter() {
    jdbc.update(
      "INSERT INTO assistant_request_limits VALUES('audio:daily:2026-10-09',20,?)",
      clock.instant().plusSeconds(86400).atOffset(ZoneOffset.UTC)
    );
    assertThatThrownBy(() ->
      transcriber.transcribe(user, recording, "audio/webm")
    ).isInstanceOfSatisfying(ApiException.class, e ->
      assertThat(e.status).isEqualTo(429)
    );
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM assistant_request_limits WHERE bucket LIKE 'audio:user:%'",
        Integer.class
      )
    ).isZero();
    server.verify();
  }
}
