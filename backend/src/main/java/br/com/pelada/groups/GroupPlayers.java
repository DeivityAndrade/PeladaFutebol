package br.com.pelada.groups;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GroupPlayers {

  private static final Set<String> POSITIONS = Set.of(
    "GOALKEEPER",
    "DEFENSE",
    "MIDFIELD",
    "ATTACK",
    "VERSATILE"
  );
  private final Store store;
  private final Groups groups;

  public GroupPlayers(Store store, Groups groups) {
    this.store = store;
    this.groups = groups;
  }

  public List<GroupPlayer> list(UUID user, UUID clubId) {
    groups.requireMember(user, clubId);
    return store
      .list(Member.class, "from Member where clubId=:club", "club", clubId)
      .stream()
      .map(m ->
        new GroupPlayer(
          m.playerId,
          store.get(Player.class, m.playerId).name,
          m.primaryPosition,
          m.secondaryPosition,
          m.skillLevel
        )
      )
      .sorted(
        Comparator.comparing(
          GroupPlayer::name,
          String.CASE_INSENSITIVE_ORDER
        ).thenComparing(GroupPlayer::playerId)
      )
      .toList();
  }

  public GroupPlayer classify(
    UUID user,
    UUID clubId,
    UUID playerId,
    ClassificationInput input
  ) {
    groups.requireOwner(user, clubId);
    Member member = store
      .first(
        Member.class,
        "from Member where clubId=:club and playerId=:player",
        "club",
        clubId,
        "player",
        playerId
      )
      .orElseThrow(ApiException::notFound);
    member = store.lock(Member.class, member.id);
    boolean empty =
      input.primaryPosition() == null &&
      input.skillLevel() == null &&
      input.secondaryPosition() == null;
    if (
      !empty &&
      (input.primaryPosition() == null ||
        !POSITIONS.contains(input.primaryPosition()) ||
        input.skillLevel() == null ||
        input.skillLevel() < 1 ||
        input.skillLevel() > 5 ||
        (input.secondaryPosition() != null &&
          (!POSITIONS.contains(input.secondaryPosition()) ||
            input.secondaryPosition().equals(input.primaryPosition()))))
    ) throw new ApiException(
      400,
      "Informe posição e nível de 1 a 5. A posição secundária deve ser diferente."
    );
    member.primaryPosition = input.primaryPosition();
    member.secondaryPosition = input.secondaryPosition();
    member.skillLevel = input.skillLevel();
    return new GroupPlayer(
      playerId,
      store.get(Player.class, playerId).name,
      member.primaryPosition,
      member.secondaryPosition,
      member.skillLevel
    );
  }
}
