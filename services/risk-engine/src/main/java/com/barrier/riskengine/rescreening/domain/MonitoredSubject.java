package com.barrier.riskengine.rescreening.domain;

import java.util.List;
import java.util.UUID;

/**
 * Um cliente sob monitoramento contínuo, com os tenants que o enxergam.
 *
 * <p>Projeção deliberadamente magra: o rescreening compara nome e documento de toda a base de
 * clientes contra as entradas novas de cada importação, e carregar o agregado inteiro para isso
 * traria o cadastro junto — dado pessoal que esta etapa não usa.
 *
 * @param tenantIds tenants vinculados ao subject. Um subject é global (dedup por documento), mas a
 *     reavaliação é <b>por tenant</b>: cada parceiro tem a própria decisão, a própria fila de
 *     revisão e o próprio webhook. Reavaliar "o subject" uma vez só entregaria o resultado a um
 *     parceiro e deixaria os outros com uma decisão que a lista já contradiz.
 */
public record MonitoredSubject(
    UUID id, String documentType, String document, String name, List<String> tenantIds) {

  public MonitoredSubject {
    tenantIds = List.copyOf(tenantIds);
  }
}
