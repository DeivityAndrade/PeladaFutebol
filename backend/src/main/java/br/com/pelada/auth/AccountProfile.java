package br.com.pelada.auth;

import br.com.pelada.api.Contracts.UserView;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional
public class AccountProfile {

  private final Store store;
  private final Accounts accounts;

  public AccountProfile(Store store, Accounts accounts) {
    this.store = store;
    this.accounts = accounts;
  }

  public static String photoUrl(Player player) {
    return player.photoVersion == null
      ? null
      : "/api/players/" + player.id + "/photo?v=" + player.photoVersion;
  }

  public UserView rename(UUID user, String name) {
    String normalized = name.strip();
    if (
      normalized.isEmpty() ||
      normalized.length() > 80 ||
      normalized.codePoints().anyMatch(Character::isISOControl)
    ) throw new ApiException(400, "Informe um nome de até 80 caracteres.");
    Player player = store.lock(Player.class, user);
    player.name = normalized;
    return accounts.view(player);
  }

  public UserView upload(UUID user, MultipartFile file) {
    byte[] data = normalizePhoto(file);
    Player player = store.lock(Player.class, user);
    var existing = store.first(
      PlayerPhoto.class,
      "from PlayerPhoto where playerId=:id",
      "id",
      user
    );
    if (existing.isPresent()) existing.get().data = data;
    else store.save(new PlayerPhoto(user, data));
    player.photoVersion = UUID.randomUUID();
    return accounts.view(player);
  }

  public UserView remove(UUID user) {
    Player player = store.lock(Player.class, user);
    store
      .first(
        PlayerPhoto.class,
        "from PlayerPhoto where playerId=:id",
        "id",
        user
      )
      .ifPresent(store::remove);
    player.photoVersion = null;
    return accounts.view(player);
  }

  @Transactional(readOnly = true)
  public byte[] photo(UUID viewer, UUID user, UUID version) {
    // A photo follows the visibility of the shared figurinha; private collections remain private.
    if (
      !viewer.equals(user) &&
      store
        .list(
          UUID.class,
          "select c.id from CareerCard c where c.playerId=:user and c.shared=true and c.clubId in (select m.clubId from Member m where m.playerId=:viewer) and c.clubId in (select m.clubId from Member m where m.playerId=:user)",
          "user",
          user,
          "viewer",
          viewer
        )
        .isEmpty()
    ) throw ApiException.notFound();
    Player player = store.get(Player.class, user);
    if (
      version == null || !version.equals(player.photoVersion)
    ) throw ApiException.notFound();
    return store.get(PlayerPhoto.class, user).data;
  }

  private byte[] normalizePhoto(MultipartFile file) {
    if (file.isEmpty()) throw new ApiException(
      400,
      "Escolha uma foto em JPG ou PNG."
    );
    if (file.getSize() > 2 * 1024 * 1024) throw new ApiException(
      413,
      "A foto deve ter no máximo 2 MB."
    );
    try (
      var input = ImageIO.createImageInputStream(
        new ByteArrayInputStream(file.getBytes())
      )
    ) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw invalidPhoto();
      var reader = readers.next();
      try {
        if (
          !Set.of("JPEG", "PNG").contains(
            reader.getFormatName().toUpperCase(Locale.ROOT)
          )
        ) throw invalidPhoto();
        reader.setInput(input, true, true);
        int width = reader.getWidth(0),
          height = reader.getHeight(0);
        if (
          width < 1 || height < 1 || (long) width * height > 16_000_000
        ) throw new ApiException(
          400,
          "Escolha uma foto com até 16 milhões de pixels."
        );
        BufferedImage original = reader.read(0);
        int side = Math.min(width, height),
          size = Math.min(512, side);
        BufferedImage normalized = new BufferedImage(
          size,
          size,
          BufferedImage.TYPE_INT_ARGB
        );
        var graphics = normalized.createGraphics();
        try {
          graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC
          );
          int x = (width - side) / 2,
            y = (height - side) / 2;
          graphics.drawImage(
            original,
            0,
            0,
            size,
            size,
            x,
            y,
            x + side,
            y + side,
            null
          );
        } finally {
          graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(normalized, "png", output);
        return output.toByteArray();
      } finally {
        reader.dispose();
      }
    } catch (IOException | IllegalArgumentException e) {
      throw invalidPhoto();
    }
  }

  private ApiException invalidPhoto() {
    return new ApiException(
      400,
      "Não foi possível ler essa foto. Escolha um arquivo JPG ou PNG válido."
    );
  }
}
