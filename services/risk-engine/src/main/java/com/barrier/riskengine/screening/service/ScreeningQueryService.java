package com.barrier.riskengine.screening.service;

import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.repository.interfaces.ScreeningResultRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Leitura de um screening pelo id — o portão do módulo {@code screening} para quem precisa recuperar
 * a evidência <b>exata</b> que sustentou uma decisão, junto do snapshot de versões de lista
 * ({@code sources_json}) que torna um {@code CLEAR} verificável meses depois.
 *
 * <p>Separado de {@link ScreeningService} de propósito: aquele consulta as listas e <b>grava</b>;
 * este só lê. Um replay que dependesse do outro carregaria consigo a capacidade de disparar
 * consulta nova, e a garantia de não gastá-la viraria disciplina em vez de tipo.
 */
@Service
public class ScreeningQueryService {

  private final ScreeningResultRepository repository;

  public ScreeningQueryService(ScreeningResultRepository repository) {
    this.repository = repository;
  }

  public Optional<ScreeningResult> findById(UUID id) {
    return id == null ? Optional.empty() : repository.findById(id);
  }
}
