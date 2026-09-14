package com.barrier.riskengine.policy;

import java.time.Instant;

/**
 * O estado do registry para uma regra num instante: se estava ligada, com que criticidade, em que
 * vigência — e <b>quem</b> deixou assim.
 *
 * <p>É a metade que a trilha da decisão não conta. {@code evaluated_json} registra que uma regra
 * ficou {@code SUPPRESSED}, o que já é muito mais do que a maioria dos motores guarda, mas não diz
 * quem a desligou nem quando. Ligar e desligar regra de risco é a operação mais sensível do sistema
 * — foi o que motivou a V033 a acrescentar {@code updated_by} ao registry, a única tabela de
 * controle que ainda não tinha autoria.
 *
 * <p>⚠️ <b>A autoria é autodeclarada.</b> {@code changedBy} é o que o chamador escreveu no corpo do
 * {@code PUT}, e a chave de admin é única e global — ela não identifica pessoa nenhuma. Numa
 * fiscalização isso sustenta "a política mudou nesta data" e <b>não</b> sustenta "foi esta pessoa
 * quem mudou". Fechar essa distância é o item de identidade de operador do backlog, e enquanto ele
 * não fechar este campo vale como registro operacional, não como prova de autoria.
 *
 * @param changedBy autor <b>declarado</b> da alteração; {@code null} em estado de semente ou em
 *     linha anterior à V033
 * @param changedAt instante da alteração que produziu este estado
 */
public record RegistryPolicyState(
    String ruleCode,
    boolean enabled,
    String criticality,
    String description,
    Instant validFrom,
    Instant validUntil,
    String changedBy,
    Instant changedAt,
    PolicyProvenance provenance) {}
