package br.com.pelada.groups;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Groups {

  private final Store store;

  public Groups(Store store) {
    this.store = store;
  }

  public ClubView create(UUID user, CreateClub input) {
    Club club = store.save(
      new Club(input.name().strip(), input.description().strip(), user)
    );
    store.save(new Member(club.id, user));
    return view(club);
  }

  @Transactional(readOnly = true)
  public List<ClubView> list(UUID user) {
    return store
      .list(
        Club.class,
        "select c from Club c, Member m where m.clubId=c.id and m.playerId=:user order by c.name",
        "user",
        user
      )
      .stream()
      .map(this::view)
      .toList();
  }

  public ClubView join(UUID user, UUID invite) {
    Club found = store
      .first(Club.class, "from Club where invite=:invite", "invite", invite)
      .orElseThrow(ApiException::notFound);
    Club club = store.lock(Club.class, found.id);
    writable(club);
    if (!isMember(user, club.id)) store.save(new Member(club.id, user));
    return view(club);
  }

  public Club requireMember(UUID user, UUID clubId) {
    Club club = store.get(Club.class, clubId);
    if (!isMember(user, clubId)) throw ApiException.forbidden();
    return club;
  }

  public Club requireOwner(UUID user, UUID clubId) {
    Club club = requireMember(user, clubId);
    writable(club);
    if (!club.ownerId.equals(user)) throw ApiException.forbidden();
    return club;
  }

  public void writable(Club club) {
    if (club.demo) throw new ApiException(
      403,
      "O grupo de demonstração é somente para consulta."
    );
  }

  private boolean isMember(UUID user, UUID clubId) {
    return store
      .first(
        Member.class,
        "from Member where clubId=:club and playerId=:user",
        "club",
        clubId,
        "user",
        user
      )
      .isPresent();
  }

  public ClubView view(Club club) {
    long members = store
      .list(
        Long.class,
        "select count(m) from Member m where clubId=:club",
        "club",
        club.id
      )
      .getFirst();
    return new ClubView(
      club.id,
      club.name,
      club.description,
      club.ownerId,
      club.demo ? null : club.invite,
      members,
      club.demo
    );
  }
}
