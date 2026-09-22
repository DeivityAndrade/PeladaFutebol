package br.com.pelada.auth;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.Player;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Accounts implements UserDetailsService {

  private final Store store;
  private final PasswordEncoder encoder;

  public Accounts(Store store, PasswordEncoder encoder) {
    this.store = store;
    this.encoder = encoder;
  }

  public static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  @Override
  @Transactional(readOnly = true)
  public UserDetails loadUserByUsername(String email) {
    Player player = store
      .first(
        Player.class,
        "from Player where email=:email",
        "email",
        normalize(email)
      )
      .orElseThrow(() ->
        new UsernameNotFoundException("Credenciais inválidas")
      );
    return User.withUsername(player.email)
      .password(player.password)
      .roles("USER")
      .build();
  }

  @Transactional
  public UserView register(Register input) {
    if (
      input.password().getBytes(StandardCharsets.UTF_8).length > 72
    ) throw new ApiException(400, "A senha deve ter no máximo 72 bytes.");
    String email = normalize(input.email());
    if (
      store
        .first(Player.class, "from Player where email=:email", "email", email)
        .isPresent()
    ) throw ApiException.conflict(
      "Não foi possível cadastrar esse e-mail. Tente entrar ou use outro endereço."
    );
    Player player = store.save(
      new Player(input.name().strip(), email, encoder.encode(input.password()))
    );
    store.flush();
    return view(player);
  }

  @Transactional(readOnly = true)
  public Player current(Authentication auth) {
    if (auth == null) throw new ApiException(
      401,
      "Entre na sua conta para continuar."
    );
    return store
      .first(
        Player.class,
        "from Player where email=:email",
        "email",
        auth.getName()
      )
      .orElseThrow(ApiException::forbidden);
  }

  public static UserView view(Player player) {
    return new UserView(player.id, player.name, player.email);
  }
}
