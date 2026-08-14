package com.barrier.riskengine.assurance.client.interfaces;

import com.barrier.riskengine.assurance.client.BiometricSubmission;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import java.util.Optional;
import java.util.UUID;

/**
 * Biometria facial com prova de vida: a face apresentada é a do documento, e é de uma pessoa
 * presente — não de uma foto de foto, máscara ou vídeo.
 *
 * <p>Prova de vida e comparação andam juntas de propósito: comparar face sem prova de vida aprova
 * quem tem a foto do titular, que é o ataque mais barato que existe.
 *
 * <p><b>Duas fases, não uma.</b> Provedores síncronos (stub de desenvolvimento, provedor de
 * emergência de produção) decidem tudo na hora. O Datavalid/Serpro não: {@code requestVerification}
 * só emite um PIN — o cidadão captura a selfie depois, no app gov.br, minutos a horas mais tarde —
 * e o desfecho só existe quando {@code pollResult} é chamado de novo, mais tarde, por um poller.
 * Forçar isso numa única chamada síncrona exigiria bloquear a requisição HTTP do parceiro até o
 * cidadão terminar no celular, o que é inviável.
 */
public interface BiometricVerificationProvider {

  /**
   * Inicia a verificação. Provedores síncronos devolvem o desfecho final direto (PASS/FAIL/
   * INCONCLUSIVE/UNAVAILABLE); provedores assíncronos devolvem {@link
   * com.barrier.riskengine.assurance.domain.AssuranceOutcome#PENDING} com o PIN anexado (ver
   * {@link AssuranceCheck#pendingWithPin}).
   *
   * @param document CPF do subject (dígitos), resolvido pelo {@code AssuranceService} via {@code
   *     SubjectService.findById} antes de chamar o provider. Necessário para o Datavalid/Serpro
   *     (o PIN é emitido <b>por CPF</b>, ver {@code POST pessoa-fisica/app/pin}) — nem {@code
   *     BiometricSubmission} nem {@code subjectId} carregam o documento, então entra aqui como o
   *     bureau já recebe {@code documentDigits} em {@code BureauQuery} em vez de resolvê-lo
   *     sozinho: quem resolve identificador→documento é sempre quem chama o provider, nunca o
   *     `client`
   */
  AssuranceCheck requestVerification(
      UUID subjectId, String tenantId, String document, BiometricSubmission submission);

  /**
   * Consulta o desfecho de um check ainda {@code PENDING} deste provedor. Chamado <b>só</b> pelo
   * {@code AssuranceResultPoller}, nunca no caminho síncrono da requisição HTTP.
   *
   * @param document CPF do subject, resolvido pelo <b>chamador</b> a partir de {@code
   *     pending.subjectId()}/{@code pending.tenantId()} — nunca pelo provider. Corrige um defeito
   *     real: a primeira versão deste provider guardava a associação {@code pin → CPF} num mapa
   *     em memória preenchido em {@code requestVerification}, o que funciona só quando a mesma
   *     instância que emitiu o PIN também roda o poller. Este serviço roda replicado por desenho
   *     (é a razão de existir lease/{@code FOR UPDATE SKIP LOCKED} no outbox e no processor de
   *     avaliações) — no cenário normal de produção, a réplica que emite o PIN quase nunca é a
   *     que poleia, e o mapa em memória fazia a biometria <b>nunca</b> completar, não só depois de
   *     restart. E o desfecho errado (`UNAVAILABLE`, "provedor indisponível") mentia na trilha: o
   *     Serpro respondeu normalmente, quem perdeu o dado fomos nós. O provider não deve resolver
   *     isso sozinho porque isso o faria depender do módulo {@code subject} — integração externa
   *     só conhece o pacote {@code client}; é o {@code AssuranceResultPoller} quem resolve via
   *     {@code SubjectService.findById(subjectId, tenantId)} (nunca só por {@code subjectId} — o
   *     tipo do método é a defesa contra vazar subject de outro tenant) e repassa aqui.
   * @return vazio enquanto o cidadão não completou a captura; presente com o desfecho final assim
   *     que ele completar, ou quando o PIN expirar sem resposta
   * @throws UnsupportedOperationException em provedores síncronos — eles nunca produzem um check
   *     {@code PENDING}, então o poller nunca deveria chamá-los aqui. Lançar em vez de devolver
   *     {@code Optional.empty()} silenciosamente é deliberado: um poller que chamasse isto por
   *     engano ficaria tentando para sempre em vez de estourar no primeiro uso indevido.
   */
  Optional<AssuranceCheck> pollResult(AssuranceCheck pending, String document);

  String name();
}
