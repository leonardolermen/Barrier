package com.barrier.riskengine.subject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.domain.SubjectNotFoundException;
import com.barrier.riskengine.subject.repository.interfaces.SubjectRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubjectServiceTest {

  @Mock SubjectRepository repository;

  private Subject subject() {
    return new Subject(UUID.randomUUID(), "CPF", "11144477735", "Fulano", Instant.now());
  }

  @Test
  void findOrCreateReaproveitaExistente() {
    Subject existing = subject();
    when(repository.findByDocument("CPF", "11144477735")).thenReturn(Optional.of(existing));

    Subject result = new SubjectService(repository).findOrCreate("CPF", "11144477735", "Fulano");

    assertThat(result).isEqualTo(existing);
    verify(repository, never()).save(any());
  }

  @Test
  void findOrCreateCriaQuandoNaoExiste() {
    when(repository.findByDocument("CPF", "11144477735")).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Subject result = new SubjectService(repository).findOrCreate("CPF", "11144477735", "Fulano");

    assertThat(result.document()).isEqualTo("11144477735");
    verify(repository).save(any());
  }

  @Test
  void getForTenantRetornaQuandoVinculado() {
    Subject s = subject();
    when(repository.findByDocument("CPF", "11144477735")).thenReturn(Optional.of(s));
    when(repository.isLinked("acme", s.id())).thenReturn(true);

    assertThat(new SubjectService(repository).getForTenant("acme", "CPF", "11144477735")).isEqualTo(s);
  }

  @Test
  void getForTenantSemVinculoNaoEncontra() {
    Subject s = subject();
    when(repository.findByDocument("CPF", "11144477735")).thenReturn(Optional.of(s));
    when(repository.isLinked("outra-empresa", s.id())).thenReturn(false);

    assertThatThrownBy(
            () -> new SubjectService(repository).getForTenant("outra-empresa", "CPF", "11144477735"))
        .isInstanceOf(SubjectNotFoundException.class);
  }

  @Test
  void getForTenantDocumentoInexistenteNaoEncontra() {
    when(repository.findByDocument("CPF", "11144477735")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> new SubjectService(repository).getForTenant("acme", "CPF", "11144477735"))
        .isInstanceOf(SubjectNotFoundException.class);
  }
}
