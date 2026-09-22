package br.com.pelada.domain;

public class ApiException extends RuntimeException {

  public final int status;

  public ApiException(int status, String message) {
    super(message);
    this.status = status;
  }

  public static ApiException notFound() {
    return new ApiException(404, "Não encontramos esse registro.");
  }

  public static ApiException forbidden() {
    return new ApiException(
      403,
      "Você não tem permissão para realizar esta ação."
    );
  }

  public static ApiException conflict(String message) {
    return new ApiException(409, message);
  }
}
