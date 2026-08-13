package com.barrier.riskengine.assurance.client.interfaces;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.client.DocumentSubmission;
import java.util.UUID;

/**
 * Documentoscopia: autenticidade do documento apresentado (padrões de impressão, fontes, campos
 * adulterados) e extração dos dados dele.
 *
 * <p>A implementação real <b>não recebe a imagem por aqui</b>: recebe a referência de um upload
 * feito direto do dispositivo para o provedor. Imagem que não trafega pela nossa infraestrutura é
 * mais forte que imagem que trafega e não é guardada — ver ADR-0016.
 */
public interface DocumentVerificationProvider {

  AssuranceCheck verify(UUID subjectId, String tenantId, DocumentSubmission submission);

  String name();
}
