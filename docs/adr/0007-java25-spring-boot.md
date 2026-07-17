# ADR-0007: Java 25 + Spring Boot 3

- **Status:** Aceito
- **Data:** 2026-07-04

> **Nota:** o projeto atualizou para **Spring Boot 4.0** (Spring Framework 7) durante a
> implementação, antes de qualquer código de produto ser escrito sobre o 3.x. A decisão de
> framework abaixo (Spring Boot vs. alternativas) continua válida; só a versão mudou. Ver
> `pom.xml` para a versão vigente.

## Contexto

Precisamos escolher linguagem e framework para o core regulatório. O setor financeiro
brasileiro tem ecossistema maduro em Java; o time prefere Java. Foi considerado Go para o
core e Quarkus como framework alternativo.

## Decisão

Vamos usar **Java 25 (LTS)** com **Spring Boot 3.x**.

- Java 25 (LTS, set/2025): virtual threads maduras, pattern matching, records — ideal para
  código de orquestração enxuto e para consumidores Kafka concorrentes.
- Spring Boot: maior base de mercado no setor financeiro BR, ecossistema e libs amplos.

## Alternativas consideradas

- **Go no core** — enxuto e performático, ótimo para gateways/workers de alta vazão, mas o
  domínio de negócio complexo e a preferência do time favorecem Java. Pode ser reconsiderado
  para componentes específicos de alta vazão no futuro.
- **Quarkus** — startup rápido e imagem nativa, mas menor base de mercado e libs que Spring.
  Descartado como framework principal.
- **Java 21 LTS** — opção segura e válida; escolhido o 25 por ser o LTS mais recente com
  ganhos relevantes de linguagem e virtual threads maduras.

## Consequências

- **Positivas:** mercado e libs amplos; produtividade do time; virtual threads simplificam
  concorrência.
- **Negativas / custos:** footprint de memória e startup maiores que Go/Quarkus nativo.
- **Mitigações:** aceitável para o perfil de serviço; revisitar por serviço se algum exigir
  footprint muito baixo.
