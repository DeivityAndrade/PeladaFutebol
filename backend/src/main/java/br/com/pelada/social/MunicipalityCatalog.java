package br.com.pelada.social;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class MunicipalityCatalog {

  public record Municipality(
    String code,
    String name,
    String uf,
    double latitude,
    double longitude
  ) {
    public String label() {
      return name + " — " + uf;
    }
  }

  private final List<Municipality> all;
  private final Map<String, Municipality> byCode;

  public MunicipalityCatalog() {
    List<Municipality> rows = new ArrayList<>();
    Map<String, Municipality> index = new HashMap<>();
    try (
      BufferedReader reader = new BufferedReader(
        new InputStreamReader(
          new ClassPathResource("geo/municipalities.csv").getInputStream(),
          StandardCharsets.UTF_8
        )
      )
    ) {
      reader.readLine();
      for (String line; (line = reader.readLine()) != null; ) {
        List<String> fields = parse(line);
        if (fields.size() != 5) throw new IllegalStateException(
          "Linha inválida no catálogo municipal."
        );
        Municipality municipality = new Municipality(
          fields.get(0),
          fields.get(1),
          fields.get(2),
          Double.parseDouble(fields.get(3)),
          Double.parseDouble(fields.get(4))
        );
        rows.add(municipality);
        index.put(municipality.code(), municipality);
      }
    } catch (IOException error) {
      throw new IllegalStateException(
        "Não foi possível carregar o catálogo municipal do IBGE.",
        error
      );
    }
    if (rows.size() < 5000) throw new IllegalStateException(
      "Catálogo municipal incompleto."
    );
    all = List.copyOf(rows);
    byCode = Map.copyOf(index);
  }

  public Optional<Municipality> find(String code) {
    return Optional.ofNullable(byCode.get(code));
  }

  public List<Municipality> search(String query) {
    String term = normalize(query);
    if (term.length() < 2) return List.of();
    return all
      .stream()
      .filter(m -> normalize(m.label()).contains(term))
      .sorted(
        Comparator.comparing(Municipality::name).thenComparing(Municipality::uf)
      )
      .limit(30)
      .toList();
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
      .replaceAll("\\p{M}+", "")
      .toLowerCase(Locale.ROOT)
      .strip();
  }

  private static List<String> parse(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder value = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char current = line.charAt(i);
      if (current == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          value.append('"');
          i++;
        } else quoted = !quoted;
      } else if (current == ';' && !quoted) {
        fields.add(value.toString());
        value.setLength(0);
      } else value.append(current);
    }
    fields.add(value.toString());
    return fields;
  }
}
