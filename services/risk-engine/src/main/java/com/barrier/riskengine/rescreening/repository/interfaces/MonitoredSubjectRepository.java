package com.barrier.riskengine.rescreening.repository.interfaces;

import com.barrier.riskengine.rescreening.domain.MonitoredSubject;
import java.util.Collection;
import java.util.List;

/** Leitura da base de clientes sob monitoramento contínuo. */
public interface MonitoredSubjectRepository {

  /**
   * Subjects cujo documento está no conjunto informado — o caminho barato, para as listas que
   * publicam CPF/CNPJ.
   */
  List<MonitoredSubject> findByDocuments(Collection<String> documents);

  /**
   * Página da base de clientes vinculados a pelo menos um tenant, ordenada de forma estável.
   *
   * <p>Existe para o match por nome, que não tem como ser resolvido por índice hoje: as listas de
   * sanção (OFAC, CSNU) publicam nome sem documento, então a comparação é cada entrada nova contra
   * cada cliente. Paginado porque a alternativa — carregar a base inteira de clientes na memória
   * do processo — falha exatamente quando o sistema deu certo.
   */
  List<MonitoredSubject> findLinkedPage(int page, int size);
}
