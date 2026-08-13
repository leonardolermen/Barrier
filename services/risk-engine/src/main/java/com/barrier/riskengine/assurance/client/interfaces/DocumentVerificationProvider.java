package com.barrier.riskengine.assurance.client.interfaces;

import com.barrier.riskengine.assurance.client.DocumentSubmission;
import com.barrier.riskengine.assurance.client.DocumentVerificationResult;
import java.util.UUID;

/**
 * Documentoscopia: autenticidade do documento apresentado (padrões de impressão, fontes, campos
 * adulterados) e extração dos dados dele.
 *
 * <p>A implementação real <b>não recebe a imagem por aqui</b>: recebe a referência de um upload
 * feito direto do dispositivo para o provedor. Imagem que não trafega pela nossa infraestrutura é
 * mais forte que imagem que trafega e não é guardada — ver ADR-0016.
 *
 * <p>Devolve os campos extraídos junto com o desfecho — não sabe nada de consentimento, essa é
 * obrigação do serviço (ver {@code AssuranceService}).
 */
public interface DocumentVerificationProvider {

  DocumentVerificationResult verify(UUID subjectId, String tenantId, DocumentSubmission submission);

  String name();
}
