package com.barrier.riskengine.assurance.domain;

/**
 * Biometria acionada sem documentoscopia aprovada para o mesmo {@code (subjectId, tenantId)}.
 *
 * <p>Decisão de produto (2026-08-13): documentoscopia aprovada passa a ser pré-requisito da
 * biometria, não um passo independente. Comparar rosto contra um documento que não passou na
 * autenticidade prova pouco — por isso só {@link AssuranceOutcome#PASS} libera; {@code FAIL},
 * {@code INCONCLUSIVE} e {@code UNAVAILABLE} recusam do mesmo jeito que a ausência total de
 * checagem, porque nenhum dos três estabeleceu que o documento é genuíno.
 *
 * <p>Exceção própria, não {@code IllegalStateException} genérica: o parceiro precisa distinguir
 * "assurance desligado" (kill switch, {@code barrier.assurance.enabled=false}) de "falta
 * documentoscopia aprovada" — as duas viram 409, mas são causas diferentes e pedem reações
 * diferentes do lado de quem integra.
 *
 * <p>Consequência operacional: provedor de documentoscopia indisponível trava a frente inteira,
 * não só a metade que dependia dele. O cliente não fica preso para sempre —
 * {@code IdentityAssuranceRiskRule} já converte {@code UNAVAILABLE} em revisão humana — mas não
 * avança para a biometria sozinho enquanto não houver um {@code PASS} registrado.
 *
 * <p>Isto muda o contrato de integração: a ordem documentoscopia → biometria passa a ser
 * obrigatória, não só recomendada. É viável agora porque o endpoint de biometria ainda não está
 * em produção; feito depois, seria breaking change para parceiros já integrados.
 */
public class DocumentGateNotSatisfiedException extends RuntimeException {

  public DocumentGateNotSatisfiedException(String message) {
    super(message);
  }
}
