package br.com.pelada;

import static org.assertj.core.api.Assertions.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.auth.*;
import br.com.pelada.career.Career;
import br.com.pelada.career.CareerContracts.CardInput;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.groups.Groups;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class AccountProfileTest {

  @Autowired
  AccountProfile profile;

  @Autowired
  Accounts accounts;

  @Autowired
  Career career;

  @Autowired
  Groups groups;

  @Autowired
  Store store;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  TransactionTemplate tx;

  UUID owner, member, stranger, club;

  @BeforeEach
  void setup() {
    jdbc.execute("TRUNCATE spring_session,players CASCADE");
    List<UUID> ids = tx.execute(s ->
      List.of(
        store
          .save(new Player("Alice", "alice@profile.invalid", "!disabled"))
          .id,
        store.save(new Player("Beto", "beto@profile.invalid", "!disabled")).id,
        store.save(new Player("Carol", "carol@profile.invalid", "!disabled")).id
      )
    );
    owner = ids.get(0);
    member = ids.get(1);
    stranger = ids.get(2);
    var group = groups.create(owner, new CreateClub("Turma", ""));
    club = group.id();
    groups.join(member, group.invite());
  }

  @Test
  void nameChangesPersistAndReachCardsWithoutChangingLoginOrOthers() {
    var view = profile.rename(owner, "  Alice da turma  ");
    assertThat(view.name()).isEqualTo("Alice da turma");
    assertThat(view.email()).isEqualTo("alice@profile.invalid");
    assertThat(
      accounts.loadUserByUsername(view.email()).getUsername()
    ).isEqualTo(view.email());
    assertThat(career.mine(owner, club).card().name()).isEqualTo(view.name());
    assertThat(
      tx.<String>execute(s -> store.get(Player.class, member).name)
    ).isEqualTo("Beto");
    for (String name : List.of("   ", "x".repeat(81), "Alice\nBeto")) {
      assertThatThrownBy(() -> profile.rename(owner, name)).isInstanceOf(
        ApiException.class
      );
    }
    assertThat(career.mine(owner, club).card().name()).isEqualTo(view.name());
  }

  @Test
  void photosAreNormalizedReplacedAndRemoved() throws Exception {
    var first = profile.upload(owner, image(800, 600, "png"));
    UUID v1 = version(first);
    var decoded = ImageIO.read(
      new ByteArrayInputStream(profile.photo(owner, owner, v1))
    );
    assertThat(decoded.getWidth()).isEqualTo(512);
    assertThat(decoded.getHeight()).isEqualTo(512);
    assertThat(career.mine(owner, club).card().photoUrl()).isEqualTo(
      first.photoUrl()
    );
    var second = profile.upload(owner, image(20, 40, "jpeg"));
    assertThat(second.photoUrl()).isNotEqualTo(first.photoUrl());
    assertThatThrownBy(() -> profile.photo(owner, owner, v1)).isInstanceOf(
      ApiException.class
    );
    assertThat(profile.photo(owner, owner, version(second))).isNotEmpty();
    assertThat(
      jdbc.queryForObject("select count(*) from player_photos", Integer.class)
    ).isEqualTo(1);
    assertThat(profile.remove(owner).photoUrl()).isNull();
    assertThat(profile.remove(owner).photoUrl()).isNull();
    assertThatThrownBy(() ->
      profile.photo(owner, owner, version(second))
    ).isInstanceOf(ApiException.class);
    assertThat(
      jdbc.queryForObject("select count(*) from player_photos", Integer.class)
    ).isZero();
  }

  @Test
  void photosFollowSharedCardAndCurrentMembership() throws Exception {
    var view = profile.upload(owner, image(20, 20, "png"));
    UUID version = version(view);
    assertThatThrownBy(() ->
      profile.photo(member, owner, version)
    ).isInstanceOf(ApiException.class);
    career.customize(owner, club, new CardInput(true, null, null, List.of()));
    assertThat(profile.photo(member, owner, version)).isNotEmpty();
    assertThatThrownBy(() ->
      profile.photo(stranger, owner, version)
    ).isInstanceOf(ApiException.class);
    career.customize(owner, club, new CardInput(false, null, null, List.of()));
    assertThatThrownBy(() ->
      profile.photo(member, owner, version)
    ).isInstanceOf(ApiException.class);
    career.customize(owner, club, new CardInput(true, null, null, List.of()));
    jdbc.update(
      "delete from members where club_id=? and player_id=?",
      club,
      owner
    );
    assertThatThrownBy(() ->
      profile.photo(member, owner, version)
    ).isInstanceOf(ApiException.class);
    assertThat(profile.photo(owner, owner, version)).isNotEmpty();
  }

  @Test
  void invalidAndOversizedFilesDoNotReplaceSavedPhoto() throws Exception {
    var saved = profile.upload(owner, image(20, 20, "png"));
    for (byte[] bytes : List.of(
      new byte[0],
      "<svg/>".getBytes(),
      new byte[2 * 1024 * 1024 + 1],
      Arrays.copyOf(image(20, 20, "png").getBytes(), 40)
    )) {
      assertThatThrownBy(() ->
        profile.upload(
          owner,
          new MockMultipartFile("file", "fake.png", "image/png", bytes)
        )
      ).isInstanceOf(ApiException.class);
    }
    assertThat(profile.photo(owner, owner, version(saved))).isNotEmpty();
  }

  @Test
  void dimensionsAreCheckedBeforeDecodingImage() throws Exception {
    byte[] bytes = image(20, 20, "png").getBytes();
    ByteBuffer.wrap(bytes, 16, 8).putInt(100_000).putInt(100_000);
    var crc = new CRC32();
    crc.update(bytes, 12, 17);
    ByteBuffer.wrap(bytes, 29, 4).putInt((int) crc.getValue());
    assertThatThrownBy(() ->
      profile.upload(
        owner,
        new MockMultipartFile("file", "huge.png", "image/png", bytes)
      )
    )
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("pixels");
    assertThat(
      jdbc.queryForObject("select count(*) from player_photos", Integer.class)
    ).isZero();
  }

  static UUID version(UserView user) {
    return UUID.fromString(user.photoUrl().split("v=")[1]);
  }

  static MockMultipartFile image(int width, int height, String format)
    throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(
      new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB),
      format,
      out
    );
    return new MockMultipartFile(
      "file",
      "photo." + format,
      "image/" + format,
      out.toByteArray()
    );
  }
}
