package com.barrier.riskengine.assurance.client;

import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Documentoscopia simulada para desenvolvimento.
 *
 * <p>Não aprova tudo, de propósito — foi assim que o stub de bureau escondeu por meses que ninguém
 * era verificado. A referência de captura escolhe o desfecho, então os caminhos de adulteração e de
 * foto ruim continuam exercitáveis sem provedor contratado:
 *
 * <ul>
 *   <li>prefixo {@code fail-} → documento adulterado;
 *   <li>prefixo {@code inconclusive-} → qualidade insuficiente;
 *   <li>prefixo {@code unavailable-} → provedor fora do ar;
 *   <li>qualquer outra → autêntico.
 * </ul>
 */
@Component
@Profile("!prod")
public class StubDocumentVerificationProvider implements DocumentVerificationProvider {

  private final Clock clock;

  public StubDocumentVerificationProvider(Clock clock) {
    this.clock = clock;
  }

  @Override
  public DocumentVerificationResult verify(
      UUID subjectId, String tenantId, DocumentSubmission submission) {
    String reference = submission.captureReference() == null ? "" : submission.captureReference();
    Instant now = clock.instant();
    AssuranceOutcome outcome =
        reference.startsWith("fail-")
            ? AssuranceOutcome.FAIL
            : reference.startsWith("inconclusive-")
                ? AssuranceOutcome.INCONCLUSIVE
                : reference.startsWith("unavailable-")
                    ? AssuranceOutcome.UNAVAILABLE
                    : AssuranceOutcome.PASS;
    AssuranceCheck check =
        new AssuranceCheck(
            UUID.randomUUID(),
            subjectId,
            tenantId,
            AssuranceKind.DOCUMENT,
            outcome,
            outcome == AssuranceOutcome.PASS ? 97 : 20,
            name(),
            "stub:" + reference,
            "stub/1.0.0",
            submission.submittedHash(),
            "documentoscopia simulada (" + submission.documentType() + ")",
            now,
            null);
    // Só um desfecho positivo sustenta os dados extraídos: documento reprovado ou inconclusivo
    // não dá confiança nenhuma de que o que foi lido é do titular.
    ExtractedDocumentFields extracted =
        outcome == AssuranceOutcome.PASS
            ? new ExtractedDocumentFields(
                "TITULAR SIMULADO " + reference, "00000000000", LocalDate.of(1990, 1, 1))
            : null;
    return new DocumentVerificationResult(check, extracted);
  }

  @Override
  public String name() {
    return "documentoscopia-simulada";
  }
}
