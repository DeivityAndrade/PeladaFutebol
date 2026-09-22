package br.com.pelada.api;

import br.com.pelada.domain.ApiException;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Errors {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> domain(ApiException ex) {
    return response(ex.status, ex.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
  })
  ResponseEntity<?> validation(Exception ex) {
    return response(400, "Confira os campos informados e tente novamente.");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception ex) {
    return response(
      409,
      "Os dados mudaram ou já estão cadastrados. Atualize a página e tente novamente."
    );
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception ex) {
    LoggerFactory.getLogger(Errors.class).error(
      "Falha ao processar requisição",
      ex
    );
    return response(
      500,
      "Não foi possível concluir agora. Tente novamente em instantes."
    );
  }

  private ResponseEntity<?> response(int status, String message) {
    return ResponseEntity.status(status).body(Map.of("message", message));
  }
}
