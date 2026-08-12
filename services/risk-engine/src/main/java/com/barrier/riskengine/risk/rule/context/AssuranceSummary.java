package com.barrier.riskengine.risk.rule.context;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;

/**
 * O que documentoscopia e biometria disseram sobre esta pessoa, resumido para as regras de risco.
 *
 * @param biometricAttempts quantas verificações biométricas houve. Cinco tentativas até uma passar
 *     não é a mesma coisa que uma que passou: é o sinal clássico de quem está testando artefato
 *     (foto de foto, máscara, deepfake) até vencer o detector
 */
public record AssuranceSummary(
    AssuranceCheck document, AssuranceCheck biometric, long biometricAttempts) {

  public boolean documentFailed() {
    return document != null && document.outcome() == com.barrier.riskengine.assurance.domain.AssuranceOutcome.FAIL;
  }

  public boolean biometricFailed() {
    return biometric != null && biometric.outcome() == com.barrier.riskengine.assurance.domain.AssuranceOutcome.FAIL;
  }

  public boolean anyInconclusive() {
    return (document != null && document.inconclusive())
        || (biometric != null && biometric.inconclusive());
  }
}
