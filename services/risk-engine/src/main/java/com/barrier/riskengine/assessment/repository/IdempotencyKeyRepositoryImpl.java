package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import com.barrier.riskengine.assessment.domain.IdempotencyReservation;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Implementação JDBC — a reserva é uma operação de linha, sem agregado a mapear. */
@Repository
class IdempotencyKeyRepositoryImpl implements IdempotencyKeyRepositoryImpl.IdempotencyKeyRepository {

  /**
   * Toma a posse da chave em uma única instrução: insere se não existe e reaproveita se a existente
   * já saiu da janela.
   *
   * <p>É um comando só de propósito. Duas requisições concorrentes com a mesma chave — que é o caso
   * que a idempotência precisa acertar, não o raro — serializam no índice único: a segunda espera a
   * primeira commitar, cai no {@code ON CONFLICT}, encontra a linha dentro da janela e o {@code
   * WHERE} do {@code DO UPDATE} a impede de sobrescrever. Verificar antes com um SELECT deixaria a
   * janela entre a leitura e a escrita, que é justamente onde nascem as duas avaliações.
   *
   * <p>Um retorno de 1 linha afetada significa posse (inserção nova ou retomada de chave expirada);
   * 0 significa que existe reserva válida de outra requisição.
   */
  private static final String TAKE =
      """
      INSERT INTO idempotency_keys (tenant_id, idempotency_key, request_hash, assessment_id)
      VALUES (?, ?, ?, NULL)
      ON CONFLICT (tenant_id, idempotency_key) DO UPDATE
         SET request_hash  = EXCLUDED.request_hash,
             assessment_id = NULL,
             created_at    = now()
       WHERE idempotency_keys.created_at < now() - (? * interval '1 second')
      """;

  private static final String SELECT =
      "SELECT request_hash, assessment_id FROM idempotency_keys"
          + " WHERE tenant_id = ? AND idempotency_key = ?";

  private final JdbcTemplate jdbc;

  IdempotencyKeyRepositoryImpl(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public IdempotencyReservation reserve(
      String tenantId, String key, String requestHash, Duration window) {
    int affected = jdbc.update(TAKE, tenantId, key, requestHash, window.toSeconds());
    if (affected == 1) {
      return IdempotencyReservation.taken(requestHash);
    }
    // Perdeu a disputa: existe reserva válida. Se ela sumiu no intervalo (a outra transação
    // desfez), tenta tomar a posse mais uma vez em vez de responder conflito por uma linha que
    // não existe mais.
    return find(tenantId, key)
        .orElseGet(
            () -> {
              int retry = jdbc.update(TAKE, tenantId, key, requestHash, window.toSeconds());
              return retry == 1
                  ? IdempotencyReservation.taken(requestHash)
                  : find(tenantId, key).orElseGet(() -> IdempotencyReservation.taken(requestHash));
            });
  }

  @Override
  public void bind(String tenantId, String key, AssessmentId assessmentId) {
    jdbc.update(
        "UPDATE idempotency_keys SET assessment_id = ?"
            + " WHERE tenant_id = ? AND idempotency_key = ?",
        assessmentId.value(),
        tenantId,
        key);
  }

  @Override
  public void release(String tenantId, String key) {
    jdbc.update(
        "DELETE FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = ?", tenantId, key);
  }

  @Override
  public Optional<IdempotencyReservation> find(String tenantId, String key) {
    return jdbc
        .query(
            SELECT,
            (rs, rowNum) -> {
              UUID assessmentId = rs.getObject("assessment_id", UUID.class);
              return new IdempotencyReservation(
                  rs.getString("request_hash").trim(),
                  assessmentId == null ? null : new AssessmentId(assessmentId),
                  false);
            },
            tenantId,
            key)
        .stream()
        .findFirst();
  }

  @Override
  public int purgeOlderThan(Duration age) {
    return jdbc.update(
        "DELETE FROM idempotency_keys WHERE created_at < now() - (? * interval '1 second')",
        age.toSeconds());
  }

  /** Chaves de idempotência do intake, por tenant. */
  public static interface IdempotencyKeyRepository {

    /**
     * Tenta tomar a posse da chave para esta requisição.
     *
     * <p>Devolve uma reserva {@code fresh} quando a chave não existia ou já estava fora da janela — a
     * requisição deve então criar a avaliação e chamar {@link #bind}. Caso contrário devolve a
     * reserva existente, para o chamador decidir entre repetir a resposta e recusar.
     *
     * @param window por quanto tempo uma chave usada continua valendo como repetição
     */
    IdempotencyReservation reserve(String tenantId, String key, String requestHash, Duration window);

    /** Liga a chave à avaliação criada; a partir daí a repetição tem o que devolver. */
    void bind(String tenantId, String key, AssessmentId assessmentId);

    /** Devolve a chave ao estado livre — a submissão que a reservou não completou. */
    void release(String tenantId, String key);

    Optional<IdempotencyReservation> find(String tenantId, String key);

    /** Remove chaves fora da janela. Só higiene de tabela: a janela já é aplicada na reserva. */
    int purgeOlderThan(Duration age);
  }
}
