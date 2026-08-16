package com.barrier.riskengine.subject.profile.service;

import java.util.Set;
import java.util.UUID;

/**
 * Reage a uma alteração <b>material</b> do cadastro de um cliente.
 *
 * <p><b>A interface mora aqui, e quem reage vive em {@code rescreening}</b> — mesmo padrão de
 * {@code AssuranceRecordedListener} e {@code WatchlistImportListener}. Se {@code subject.profile}
 * chamasse {@code AssessmentService} direto, fecharia o ciclo {@code subject → assessment →
 * subject} (o {@code AssessmentService} já depende de {@code SubjectService} para achar-ou-criar o
 * subject), e o ArchUnit ({@code sem_ciclos_entre_modulos}) barra. {@code rescreening} já depende
 * de {@code assessment} e de {@code subject}, então é lá que a reação cabe sem aresta nova.
 *
 * <p>Notificado <b>depois do commit</b> do cadastro: a reavaliação lê o perfil que acabou de ser
 * gravado, e reagir antes leria o valor antigo.
 *
 * @param changedFields nomes dos campos materiais alterados — nunca os valores
 */
public interface SubjectProfileUpdatedListener {

  void onMaterialChange(UUID subjectId, String tenantId, Set<String> changedFields);
}
