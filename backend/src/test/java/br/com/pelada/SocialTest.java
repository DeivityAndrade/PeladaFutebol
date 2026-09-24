package br.com.pelada;

import static org.assertj.core.api.Assertions.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.ApiException;
import br.com.pelada.domain.Domain.Player;
import br.com.pelada.domain.Store;
import br.com.pelada.groups.Groups;
import br.com.pelada.social.MunicipalityCatalog;
import br.com.pelada.social.Social;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class SocialTest {

  @Autowired
  Social social;

  @Autowired
  Groups groups;

  @Autowired
  Store store;

  @Autowired
  MunicipalityCatalog municipalities;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  TransactionTemplate tx;

  UUID host, guest, member, outsider, hostClub, guestClub;

  @BeforeEach
  void setup() {
    jdbc.execute(
      "TRUNCATE spring_session,finance_receipt_files,finance_charges,barbecue_attendance,barbecues,barbecue_series,social_messages,social_matches,social_listings,participations,teams,games,members,clubs,players CASCADE"
    );
    List<UUID> players = tx.execute(status -> {
      List<UUID> created = new ArrayList<>();
      for (int i = 0; i < 4; i++) created.add(
        store
          .save(
            new Player(
              "Social " + i,
              "social" + i + "@test.invalid",
              "!disabled"
            )
          )
          .id
      );
      return created;
    });
    host = players.get(0);
    guest = players.get(1);
    member = players.get(2);
    outsider = players.get(3);
    ClubView hostView = groups.create(
      host,
      new CreateClub("Pelada da Casa", "")
    );
    ClubView guestView = groups.create(
      guest,
      new CreateClub("Time do Bairro", "")
    );
    hostClub = hostView.id();
    guestClub = guestView.id();
    groups.join(member, hostView.invite());
    publish(host, hostClub, "Pelada da Casa");
    publish(guest, guestClub, "Time do Bairro");
  }

  @Test
  void municipalSearchIsLocalAndOnlyReturnsPublishedGroupsWithinRadius() {
    assertThat(municipalities.search("sao paulo"))
      .extracting(MunicipalityCatalog.Municipality::code)
      .contains("3550308");

    List<SocialSearchResult> results = social.search(
      host,
      "3550308",
      10,
      "FIXED_TEAM",
      "INTERMEDIATE",
      List.of("SAT"),
      List.of("EVENING")
    );
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().listing().clubId()).isEqualTo(guestClub);
    assertThat(results.getFirst().distanceKm()).isZero();
    assertThat(results.getFirst().scheduleCompatible()).isTrue();
    assertThat(results.getFirst().levelSimilar()).isTrue();
    assertThatThrownBy(() ->
      social.search(outsider, "3550308", 10, null, null, null, null)
    )
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", 403);
  }

  @Test
  void profileCanBePausedAndRemovedByItsOwner() {
    social.saveListing(guest, guestClub, listing("Time do Bairro", false));
    assertThat(
      social.search(host, "3550308", 10, null, null, null, null)
    ).isEmpty();
    social.saveListing(guest, guestClub, listing("Time do Bairro", true));
    social.removeListing(guest, guestClub);
    assertThat(social.mine(guest).getFirst().listing()).isNull();
  }

  @Test
  void invitationChatAndFriendlyRequireBothOwnersAndSyncUpdatesToBothGroups() {
    Instant initialDate = Instant.now().plusSeconds(7 * 86400);
    SocialMatchView invitation = social.invite(
      host,
      new SocialInviteInput(
        hostClub,
        guestClub,
        initialDate,
        "Arena da Rua Reservada 123",
        "Vamos jogar?"
      )
    );
    assertThatThrownBy(() ->
      social.invite(
        guest,
        new SocialInviteInput(
          guestClub,
          hostClub,
          initialDate,
          "Outro endereço",
          ""
        )
      )
    )
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", 409);
    assertThatThrownBy(() -> social.messages(member, invitation.id()))
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", 403);

    social.accept(guest, invitation.id());
    social.sendMessage(
      host,
      invitation.id(),
      new SocialMessageInput("Endereço enviado só aqui.")
    );
    assertThat(social.messages(guest, invitation.id()))
      .extracting(SocialMessageView::body)
      .contains("Endereço enviado só aqui.");
    assertThat(social.schedule(host, hostClub)).isEmpty();

    assertThat(social.confirm(host, invitation.id()).status()).isEqualTo(
      "NEGOTIATING"
    );
    assertThat(social.schedule(host, hostClub)).isEmpty();
    assertThat(social.confirm(guest, invitation.id()).status()).isEqualTo(
      "SCHEDULED"
    );
    assertThat(social.schedule(host, hostClub)).hasSize(1);
    assertThat(social.schedule(guest, guestClub)).hasSize(1);

    Instant updatedDate = Instant.now().plusSeconds(8 * 86400);
    assertThat(
      social
        .propose(
          host,
          invitation.id(),
          new SocialProposalInput(
            updatedDate,
            "Quadra atualizada, endereço reservado"
          )
        )
        .status()
    ).isEqualTo("CHANGE_PENDING");
    assertThat(social.schedule(host, hostClub).getFirst().location()).isEqualTo(
      "Arena da Rua Reservada 123"
    );
    social.confirm(guest, invitation.id());
    assertThat(social.schedule(host, hostClub).getFirst().location()).isEqualTo(
      "Quadra atualizada, endereço reservado"
    );
    assertThat(social.cancel(guest, invitation.id()).status()).isEqualTo(
      "CANCELLED"
    );
    assertThat(social.schedule(host, hostClub).getFirst().status()).isEqualTo(
      "CANCELLED"
    );
    assertThat(social.schedule(guest, guestClub).getFirst().status()).isEqualTo(
      "CANCELLED"
    );
  }

  @Test
  void pendingInvitationExpiresAndReleasesThePairForANewProposal() {
    Instant startsAt = Instant.now().plusSeconds(10 * 86400);
    SocialMatchView invitation = social.invite(
      host,
      new SocialInviteInput(hostClub, guestClub, startsAt, "Arena", "")
    );
    jdbc.update(
      "UPDATE social_matches SET expires_at = now() - interval '1 second' WHERE id = ?",
      invitation.id()
    );
    assertThat(social.accept(guest, invitation.id()).status()).isEqualTo(
      "EXPIRED"
    );
    assertThat(social.invitations(guest).getFirst().status()).isEqualTo(
      "EXPIRED"
    );
    assertThat(
      social
        .invite(
          guest,
          new SocialInviteInput(
            guestClub,
            hostClub,
            startsAt,
            "Outra quadra",
            ""
          )
        )
        .status()
    ).isEqualTo("PENDING");
  }

  private void publish(UUID owner, UUID club, String groupName) {
    social.saveListing(owner, club, listing(groupName, true));
  }

  private SocialListingInput listing(String groupName, boolean published) {
    return new SocialListingInput(
      List.of("FIXED_TEAM"),
      "3550308",
      groupName + " Arena",
      "Centro",
      "Grupo aberto a amistosos.",
      "INTERMEDIATE",
      List.of("SAT"),
      List.of("EVENING"),
      published
    );
  }
}
