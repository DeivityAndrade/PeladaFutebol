package br.com.pelada;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import br.com.pelada.admin.*;
import br.com.pelada.api.Contracts.Register;
import br.com.pelada.auth.Accounts;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.Player;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.admin-emails= ADMIN@example.test ")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdministrationTest {

  @Autowired
  Accounts accounts;

  @Autowired
  Administration administration;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  MockMvc mvc;

  @MockitoBean
  Clock clock;

  private static final Instant NOW = Instant.parse("2026-10-01T02:30:00Z");

  @BeforeEach
  void setup() {
    jdbc.execute("TRUNCATE players CASCADE");
    when(clock.instant()).thenReturn(NOW);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    accounts.register(
      new Register("Administrador", "admin@example.test", "SenhaTeste123!")
    );
    accounts.register(
      new Register("Participante", "player@example.test", "SenhaTeste123!")
    );
  }

  @Test
  void protectsEndpointAndDoesNotExposePersonalData() throws Exception {
    UUID groupOwner = jdbc.queryForObject(
      "SELECT id FROM players WHERE email='player@example.test'",
      UUID.class
    );
    jdbc.update(
      "INSERT INTO clubs (id,name,description,owner_id,invite,demo) VALUES (?,?,?,?,?,false)",
      UUID.randomUUID(),
      "Grupo próprio",
      "Teste",
      groupOwner,
      UUID.randomUUID()
    );
    mvc.perform(get("/api/admin/summary")).andExpect(status().isUnauthorized());
    mvc
      .perform(get("/api/admin/summary").with(user("player@example.test")))
      .andExpect(status().isForbidden());
    var result = mvc
      .perform(get("/api/admin/summary").with(user("admin@example.test")))
      .andExpect(status().isOk())
      .andExpect(header().string("Cache-Control", "no-store"))
      .andExpect(jsonPath("$.totalAccounts").value(2))
      .andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain(
      "admin@example.test",
      "password",
      "SenhaTeste",
      "Administrador"
    );
  }

  @Test
  void registrationRecordsTimeAndCannotGrantAdminWithBody() throws Exception {
    assertThat(
      jdbc
        .queryForObject(
          "SELECT created_at FROM players WHERE email='player@example.test'",
          Timestamp.class
        )
        .toInstant()
    ).isEqualTo(NOW);
    mvc
      .perform(get("/api/auth/me").with(user("player@example.test")))
      .andExpect(jsonPath("$.admin").value(false));
    mvc
      .perform(get("/api/auth/me").with(user("admin@example.test")))
      .andExpect(jsonPath("$.admin").value(true));
    mvc
      .perform(
        post("/api/auth/register")
          .with(csrf())
          .contentType("application/json")
          .content(
            "{\"name\":\"Outro\",\"email\":\"other@example.test\",\"password\":\"SenhaTeste123!\",\"admin\":true}"
          )
      )
      .andExpect(status().isBadRequest());
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM players", Long.class)
    ).isEqualTo(2);
  }

  @Test
  void countsRealAccountsOnceAndPreservesUnknownHistoricalDates() {
    legacy("legacy@example.test", "hash", null);
    legacy("demo-0@example.invalid", "!disabled", NOW);
    legacy("demo-99@example.invalid", "hash", NOW);
    UUID disabled = legacy("disabled@example.test", "!disabled", NOW);
    UUID owner = jdbc.queryForObject(
      "SELECT id FROM players WHERE email='admin@example.test'",
      UUID.class
    );
    for (int i = 0; i < 2; i++) jdbc.update(
      "INSERT INTO clubs (id,name,description,owner_id,invite,demo) VALUES (?,?,?,?,?,false)",
      UUID.randomUUID(),
      "Grupo",
      "Teste",
      owner,
      UUID.randomUUID()
    );
    jdbc.update(
      "INSERT INTO clubs (id,name,description,owner_id,invite,demo) VALUES (?,?,?,?,?,true)",
      UUID.randomUUID(),
      "Demo",
      "Teste",
      disabled,
      UUID.randomUUID()
    );
    var result = summary();
    assertThat(result.totalAccounts()).isEqualTo(3);
    assertThat(result.totalGroups()).isEqualTo(2);
    assertThat(result.undatedAccounts()).isEqualTo(1);
    assertThat(result.newThisMonth()).isEqualTo(2);
    assertThat(result.months()).hasSize(12);
    assertThat(
      result
        .months()
        .stream()
        .mapToLong(Administration.Month::registrations)
        .sum()
    ).isEqualTo(2);
  }

  @Test
  void usesBrasiliaMonthBoundariesAndRollingSevenDays() {
    // NOW is still September 30 in São Paulo, although UTC is October 1.
    legacy(
      "august@example.test",
      "hash",
      Instant.parse("2026-09-01T02:59:59Z")
    );
    legacy(
      "september@example.test",
      "hash",
      Instant.parse("2026-09-01T03:00:00Z")
    );
    legacy("seven@example.test", "hash", NOW.minus(Duration.ofDays(7)));
    legacy(
      "older@example.test",
      "hash",
      NOW.minus(Duration.ofDays(7)).minusSeconds(1)
    );
    legacy("future@example.test", "hash", NOW.plusSeconds(1));
    var result = summary();
    assertThat(result.months().getLast().period()).isEqualTo("2026-09");
    assertThat(result.months().get(10).registrations()).isEqualTo(1);
    assertThat(result.months().getLast().registrations()).isEqualTo(5);
    assertThat(result.newLast7Days()).isEqualTo(3);
    assertThat(result.newThisMonth()).isEqualTo(5);
  }

  @Test
  void emptyConfigurationDeniesAccessAndMatchesOnlyExactEmails() {
    Player owner = new Player("Owner", "admin@example.test", "hash");
    assertThat(new AdminAccess("").allowed(owner)).isFalse();
    assertThat(
      new AdminAccess("other@example.test, ADMIN@example.test ").allowed(owner)
    ).isTrue();
    assertThat(
      new AdminAccess("not-admin@example.test").allowed(owner)
    ).isFalse();
    owner.password = "!disabled";
    assertThat(new AdminAccess("admin@example.test").allowed(owner)).isFalse();
  }

  private Administration.Summary summary() {
    return administration.summary(
      UsernamePasswordAuthenticationToken.authenticated(
        "admin@example.test",
        "",
        java.util.List.of()
      )
    );
  }

  private UUID legacy(String email, String password, Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
      "INSERT INTO players (id,name,email,password,created_at) VALUES (?,?,?,?,?)",
      id,
      "Teste",
      email,
      password,
      createdAt == null ? null : Timestamp.from(createdAt)
    );
    return id;
  }
}
