package com.barrier.riskengine.subject.profile.service;

import com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.SubjectProfilePatch;
import com.barrier.riskengine.subject.profile.repository.interfaces.SubjectProfileRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso do cadastro (CMN 4.753): atualização progressiva (cliente ou enriquecimento pelo
 * bureau) e checagem de completude, usada como gate antes da aprovação automática de uma
 * avaliação.
 *
 * <p>Toda operação é escopada por {@code tenantId}. Não existe assinatura que aceite só o
 * {@code subjectId}: era isso que permitia um parceiro ler e completar o cadastro do cliente de
 * outro (ver migration V024). O tipo do método é a defesa — um endpoint novo não tem como
 * esquecer de passar o tenant.
 */
@Service
public class SubjectProfileService {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(SubjectProfileService.class);

  private final SubjectProfileRepository repository;
  private final java.util.List<SubjectProfileUpdatedListener> listeners;

  public SubjectProfileService(
      SubjectProfileRepository repository, java.util.List<SubjectProfileUpdatedListener> listeners) {
    this.repository = repository;
    this.listeners = listeners;
  }

  /**
   * Atualização <b>declarada pelo parceiro</b> ({@code PUT /v1/subjects/{document}/profile}).
   * Alteração material dispara reavaliação (ADR-0019).
   */
  @Transactional
  public SubjectProfile update(UUID subjectId, String tenantId, SubjectProfilePatch patch) {
    SubjectProfile current = find(subjectId, tenantId);
    java.util.Set<String> changed =
        com.barrier.riskengine.subject.profile.domain.MaterialProfileChange.detect(current, patch);
    SubjectProfile saved = repository.save(patch.applyTo(current));
    if (!changed.isEmpty()) {
      notifyAfterCommit(subjectId, tenantId, changed);
    }
    return saved;
  }

  /**
   * Enriquecimento automático a partir do bureau, durante o processamento de uma avaliação.
   *
   * <p><b>Não notifica ninguém, e é isto que impede um laço infinito:</b> o
   * {@code AssessmentProcessor} grava aqui os dados objetivos que o bureau devolveu, no meio da
   * avaliação. Se este caminho disparasse reavaliação por alteração material, toda avaliação
   * geraria outra avaliação — cada uma com sua consulta paga de bureau — indefinidamente. O
   * parceiro declara; o bureau confirma. Só a declaração é fato novo.
   *
   * <p>Método separado em vez de um {@code boolean notify}: a diferença é grande demais para ficar
   * num parâmetro que se erra por omissão.
   */
  @Transactional
  public SubjectProfile enrichFromBureau(
      UUID subjectId, String tenantId, SubjectProfilePatch patch) {
    return repository.save(patch.applyTo(find(subjectId, tenantId)));
  }

  /**
   * Só depois do commit: a reavaliação vai reler o cadastro, e notificar antes entregaria o valor
   * antigo. Mesmo raciocínio do {@code AssuranceService.scheduleNotification}.
   */
  private void notifyAfterCommit(UUID subjectId, String tenantId, java.util.Set<String> changed) {
    if (listeners.isEmpty()) {
      return;
    }
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      dispatch(subjectId, tenantId, changed);
      return;
    }
    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
        new org.springframework.transaction.support.TransactionSynchronization() {
          @Override
          public void afterCommit() {
            dispatch(subjectId, tenantId, changed);
          }
        });
  }

  private void dispatch(UUID subjectId, String tenantId, java.util.Set<String> changed) {
    for (SubjectProfileUpdatedListener listener : listeners) {
      try {
        listener.onMaterialChange(subjectId, tenantId, changed);
      } catch (RuntimeException e) {
        // Falha ao reagir não pode invalidar o cadastro que já foi gravado com sucesso.
        log.error("Falha ao reagir à alteração de cadastro do subject {}", subjectId, e);
      }
    }
  }

  /** Verifica se o cadastro deste tenant cobre o checklist mínimo do tipo de documento. */
  @Transactional(readOnly = true)
  public RegistrationCompleteness completeness(UUID subjectId, String tenantId, String documentType) {
    return RegistrationCompleteness.evaluate(documentType, find(subjectId, tenantId));
  }

  /** Cadastro declarado por este tenant; em branco se ainda não houver nenhum dado preenchido. */
  @Transactional(readOnly = true)
  public SubjectProfile find(UUID subjectId, String tenantId) {
    return findDeclared(subjectId, tenantId)
        .orElseGet(() -> SubjectProfile.blank(subjectId, tenantId));
  }

  /**
   * O cadastro <b>como está no banco</b>, sem substituir ausência por um em branco.
   *
   * <p>Existe porque {@link #find} apaga uma distinção que o replay de decisão precisa fazer:
   * {@code SubjectProfile.blank} nasce com {@code updatedAt = agora}, então um cadastro inexistente
   * é indistinguível de um alterado neste instante — e o replay leria "o cadastro mudou depois da
   * decisão" para todo subject que nunca teve cadastro nenhum.
   *
   * <p>Continua exigindo o par {@code (subjectId, tenantId)}: o dossiê é do tenant que o declarou
   * (V024), e nenhuma assinatura deste service aceita só o {@code subjectId}.
   */
  public Optional<SubjectProfile> findDeclared(UUID subjectId, String tenantId) {
    return repository.findBySubjectIdAndTenantId(subjectId, tenantId);
  }
}
