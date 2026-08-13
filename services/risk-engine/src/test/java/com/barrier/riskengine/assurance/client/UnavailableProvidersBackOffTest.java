package com.barrier.riskengine.assurance.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/**
 * IMPORTANT da re-revisão: sem {@code @ConditionalOnMissingBean}, o dia em que um provedor real
 * de documentoscopia/biometria for contratado (outro {@code @Component} de {@code prod}
 * implementando a mesma interface) derrubaria o contexto inteiro em produção com
 * {@code NoUniqueBeanDefinitionException} — a mesma classe de falha que
 * {@code UnavailableDocumentVerificationProvider}/{@code UnavailableBiometricVerificationProvider}
 * foram criados para fechar, com a causa invertida (e é exatamente o cenário que o próprio
 * {@code AssuranceProviderReadinessGuardTest.naoLancaComProviderRealHipotetico} assume como já
 * suportado). Este teste trava a anotação em regressão de build: apagar
 * {@code @ConditionalOnMissingBean} de qualquer um dos dois pinta este teste de vermelho.
 */
class UnavailableProvidersBackOffTest {

  @Test
  void documentoscopiaRecuaQuandoJaHaUmProviderRegistrado() {
    ConditionalOnMissingBean anotacao =
        UnavailableDocumentVerificationProvider.class.getAnnotation(ConditionalOnMissingBean.class);

    assertThat(anotacao)
        .as(
            "UnavailableDocumentVerificationProvider precisa de @ConditionalOnMissingBean — "
                + "sem ela, um provedor real contratado no futuro derrubaria o contexto de prod "
                + "com NoUniqueBeanDefinitionException")
        .isNotNull();
    assertThat(anotacao.value())
        .as("a condição tem de mirar a interface, não a própria classe concreta")
        .containsExactly(DocumentVerificationProvider.class);
  }

  @Test
  void biometriaRecuaQuandoJaHaUmProviderRegistrado() {
    ConditionalOnMissingBean anotacao =
        UnavailableBiometricVerificationProvider.class.getAnnotation(
            ConditionalOnMissingBean.class);

    assertThat(anotacao).as("UnavailableBiometricVerificationProvider precisa da mesma proteção").isNotNull();
    assertThat(anotacao.value()).containsExactly(BiometricVerificationProvider.class);
  }
}
