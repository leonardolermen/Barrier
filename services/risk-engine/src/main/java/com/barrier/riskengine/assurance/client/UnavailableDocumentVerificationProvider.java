package com.barrier.riskengine.assurance.client;

import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Provedor de documentoscopia para produção sem contrato real firmado.
 *
 * <p>Existe porque {@code StubDocumentVerificationProvider} é {@code @Profile("!prod")} e é a
 * única outra implementação de {@code DocumentVerificationProvider} — sem esta classe, o
 * construtor obrigatório de {@code AssuranceService} (e de {@code AssessmentProcessor}, que o
 * recebe) não encontra bean nenhum em produção, e o contexto inteiro falha na subida
 * ({@code UnsatisfiedDependencyException}) por causa de um módulo que talvez nem esteja em uso
 * pelo parceiro.
 *
 * <p>Devolve sempre {@code UNAVAILABLE} — nunca {@code PASS} nem {@code FAIL}: indisponibilidade
 * de provedor não é culpa do cliente (mesmo modelo dos bureaus, ver {@code
 * IdentityStatus.UNAVAILABLE}), e {@code IdentityAssuranceRiskRule} já tem tratamento para esse
 * desfecho — pontua pouco e não opina, em vez de travar quem tentou se verificar. Quem avisa que
 * não há provedor real contratado é {@code AssuranceProviderReadinessGuard}, não uma exceção de
 * startup.
 *
 * <p>{@code @ConditionalOnMissingBean}: no dia em que um provedor real for contratado, ele chega
 * como outro {@code @Component} de {@code prod} implementando a mesma interface. Sem esta
 * condição, dois beans do mesmo tipo em {@code prod} derrubariam o contexto com
 * {@code NoUniqueBeanDefinitionException} — a mesma classe de falha (contexto inteiro caindo por
 * causa de assurance) que este provedor foi criado para fechar, só que com a causa invertida.
 * Com ela, o provedor real "vence" por só existir um bean visível para injetar.
 */
@Component
@Profile("prod")
@ConditionalOnMissingBean(DocumentVerificationProvider.class)
public class UnavailableDocumentVerificationProvider implements DocumentVerificationProvider {

  private final Clock clock;

  public UnavailableDocumentVerificationProvider(Clock clock) {
    this.clock = clock;
  }

  @Override
  public DocumentVerificationResult verify(
      UUID subjectId, String tenantId, DocumentSubmission submission) {
    Instant now = clock.instant();
    AssuranceCheck check =
        new AssuranceCheck(
            UUID.randomUUID(),
            subjectId,
            tenantId,
            AssuranceKind.DOCUMENT,
            AssuranceOutcome.UNAVAILABLE,
            null,
            name(),
            null,
            null,
            submission.submittedHash(),
            "nenhum provedor real de documentoscopia contratado",
            Set.of(),
            now,
            null);
    // UNAVAILABLE nunca sustenta reconciliação contra o cadastro — sem PASS, não há dado
    // extraído confiável (ver DocumentVerificationResult).
    return new DocumentVerificationResult(check, null);
  }

  @Override
  public String name() {
    return "documentoscopia-indisponivel";
  }
}
