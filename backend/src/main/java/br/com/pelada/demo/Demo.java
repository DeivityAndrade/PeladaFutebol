package br.com.pelada.demo;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.games.Games;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@Service
public class Demo {

  private final Store store;
  private final Games games;
  private final Clock clock;
  private final boolean enabled;

  public Demo(
    Store store,
    Games games,
    Clock clock,
    @Value("${app.demo-enabled}") boolean enabled
  ) {
    this.store = store;
    this.games = games;
    this.clock = clock;
    this.enabled = enabled;
  }

  @Bean
  ApplicationRunner seedDemo(TransactionTemplate tx) {
    return args -> {
      if (enabled) tx.executeWithoutResult(s -> seed());
    };
  }

  private void seed() {
    Optional<Club> existing = store.first(
      Club.class,
      "from Club where demo=true"
    );
    if (existing.isPresent()) {
      seedCompleted(existing.get());
      return;
    }
    String[] names = {
      "Rafael Lima",
      "Bruno Costa",
      "Lucas Alves",
      "Pedro Santos",
      "Gabriel Souza",
      "Thiago Martins",
      "Felipe Rocha",
      "André Oliveira",
      "Diego Ferreira",
      "Matheus Silva",
      "João Mendes",
      "Vitor Ribeiro",
      "Caio Nunes",
      "Gustavo Melo",
      "Henrique Dias",
      "Daniel Reis",
    };
    List<Player> players = new ArrayList<>();
    // Invalid password hash intentionally prevents login as fictional demo users.
    for (int i = 0; i < names.length; i++) players.add(
      store.save(
        new Player(names[i], "demo-" + i + "@example.invalid", "!disabled")
      )
    );
    Club club = new Club(
      "Resenha de quinta",
      "Futebol entre amigos. Toda semana, uma boa história.",
      players.getFirst().id
    );
    club.demo = true;
    store.save(club);
    players.forEach(p -> store.save(new Member(club.id, p.id)));
    ZonedDateTime next = ZonedDateTime.now(clock)
      .withZoneSameInstant(ZoneId.of("America/Sao_Paulo"))
      .with(TemporalAdjusters.next(DayOfWeek.THURSDAY))
      .withHour(20)
      .withMinute(0)
      .withSecond(0)
      .withNano(0);
    Game game = store.save(
      new Game(
        club.id,
        "A resenha tem jogo marcado",
        "Arena da Vila · Quadra 02",
        next.toInstant(),
        2,
        7
      )
    );
    Team lime = store.save(new Team(game.id, 0, "Os Crias", "#d8f36a"));
    lime.captainId = players.get(0).id;
    lime.formation = "1-2-1";
    Team purple = store.save(new Team(game.id, 1, "Boleiros FC", "#a69aff"));
    purple.captainId = players.get(7).id;
    for (int i = 0; i < players.size(); i++) {
      Participation p = new Participation(
        game.id,
        players.get(i).id,
        i < 14 ? "CONFIRMED" : "WAITING"
      );
      if (i < 14) {
        p.teamId = i < 7 ? lime.id : purple.id;
        if (i % 7 < 5) p.slot = i % 7;
      }
      store.save(p);
    }
    seedCompleted(club);
  }

  private void seedCompleted(Club club) {
    if (
      store
        .first(
          Game.class,
          "from Game where clubId=:club and title=:title",
          "club",
          club.id,
          "title",
          "Clássico da resenha"
        )
        .isPresent()
    ) return;
    List<Player> players = new ArrayList<>();
    for (int i = 0; i < 14; i++) {
      final int index = i;
      players.add(
        store
          .first(
            Player.class,
            "from Player where email=:email",
            "email",
            "demo-" + index + "@example.invalid"
          )
          .orElseThrow()
      );
    }
    Instant kickoff = clock.instant().minus(Duration.ofDays(3));
    Game game = store.save(
      new Game(
        club.id,
        "Clássico da resenha",
        "Arena da Vila · Quadra 02",
        kickoff,
        2,
        7
      )
    );
    game.matchStartedAt = kickoff.plusSeconds(120);
    game.matchEndedAt = game.matchStartedAt.plusSeconds(52 * 60);
    game.matchDurationSeconds = 52 * 60;
    Team green = store.save(new Team(game.id, 0, "Os Crias", "#d8f36a"));
    Team purple = store.save(new Team(game.id, 1, "Boleiros FC", "#a69aff"));
    green.captainId = players.get(0).id;
    purple.captainId = players.get(7).id;
    for (int i = 0; i < 14; i++) {
      Participation p = new Participation(
        game.id,
        players.get(i).id,
        "CONFIRMED"
      );
      p.teamId = i < 7 ? green.id : purple.id;
      if (i % 7 < 5) p.slot = i % 7;
      store.save(p);
    }
    store.save(
      new Goal(
        game.id,
        green.id,
        players.get(2).id,
        12,
        false,
        kickoff.plusSeconds(12 * 60)
      )
    );
    store.save(
      new Goal(
        game.id,
        purple.id,
        players.get(8).id,
        21,
        false,
        kickoff.plusSeconds(21 * 60)
      )
    );
    store.save(
      new Goal(
        game.id,
        green.id,
        players.get(9).id,
        38,
        true,
        kickoff.plusSeconds(38 * 60)
      )
    );
    store.save(
      new Goal(
        game.id,
        green.id,
        players.get(0).id,
        47,
        false,
        kickoff.plusSeconds(47 * 60)
      )
    );
    for (int i = 0; i < 14; i++) {
      int base = i < 7 ? 0 : 7;
      for (int j = base; j < base + 7; j++) {
        if (i != j) store.save(
          new Rating(
            game.id,
            players.get(i).id,
            players.get(j).id,
            3 + ((i + j) % 3),
            game.matchEndedAt.plusSeconds(3600)
          )
        );
      }
    }
  }

  @Transactional(readOnly = true)
  public GameDetail get(boolean finished) {
    if (!enabled) throw ApiException.notFound();
    Club club = store
      .first(Club.class, "from Club where demo=true")
      .orElseThrow(ApiException::notFound);
    Game game = store
      .first(
        Game.class,
        finished
          ? "from Game where clubId=:club and title='Clássico da resenha'"
          : "from Game where clubId=:club and title='A resenha tem jogo marcado'",
        "club",
        club.id
      )
      .orElseThrow(ApiException::notFound);
    GameDetail detail = games.detail(game);
    if (finished) return detail;
    // Show an upcoming illustrative date without ever mutating demo data in a GET.
    GameView g = detail.game();
    Instant date = ZonedDateTime.now(clock)
      .withZoneSameInstant(ZoneId.of("America/Sao_Paulo"))
      .with(TemporalAdjusters.next(DayOfWeek.THURSDAY))
      .withHour(20)
      .withMinute(0)
      .withSecond(0)
      .withNano(0)
      .toInstant();
    return new GameDetail(
      new GameView(
        g.id(),
        g.clubId(),
        g.title(),
        g.location(),
        date,
        g.teamCount(),
        g.teamSize(),
        g.confirmed(),
        g.waiting(),
        false,
        false,
        false,
        g.liveEnabled(),
        g.matchStatus(),
        g.matchStartedAt(),
        g.matchEndedAt(),
        g.matchDurationSeconds(),
        g.correctionOpen(),
        g.serverNow()
      ),
      detail.club(),
      detail.attendees(),
      detail.teams(),
      detail.score(),
      detail.goals(),
      detail.ratings(),
      detail.myRatings(),
      detail.ratingsVisibleAt()
    );
  }

  @RestController
  static class DemoController {

    private final Demo demo;

    DemoController(Demo demo) {
      this.demo = demo;
    }

    @GetMapping("/api/demo")
    public GameDetail get() {
      return demo.get(false);
    }

    @GetMapping("/api/demo/finished")
    public GameDetail finished() {
      return demo.get(true);
    }
  }
}
