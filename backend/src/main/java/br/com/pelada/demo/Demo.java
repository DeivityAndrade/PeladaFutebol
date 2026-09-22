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
    if (
      store.first(Club.class, "from Club where demo=true").isPresent()
    ) return;
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
  }

  @Transactional(readOnly = true)
  public GameDetail get() {
    if (!enabled) throw ApiException.notFound();
    Club club = store
      .first(Club.class, "from Club where demo=true")
      .orElseThrow(ApiException::notFound);
    Game game = store
      .first(
        Game.class,
        "from Game where clubId=:club order by startsAt desc",
        "club",
        club.id
      )
      .orElseThrow(ApiException::notFound);
    GameDetail detail = games.detail(game);
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
        false
      ),
      detail.club(),
      detail.attendees(),
      detail.teams()
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
      return demo.get();
    }
  }
}
