package com.barrier.riskengine.assessment.domain;

/** Estado do ciclo de vida de uma avaliação. */
public enum AssessmentStatus {
  /** Recebida, aguardando processamento. */
  EM_ANALISE,
  /** Concluída com aprovação automática. */
  APROVADO,
  /** Concluída com reprovação. */
  REPROVADO,
  /** Encaminhada para análise manual (EDD) — decisão em Case Management (fase 2). */
  EM_REVISAO,
  /**
   * Risco aprovado, cadastro incompleto: falta dado obrigatório da CMN 4.753.
   *
   * <p>Existe para separar duas coisas que caíam na mesma fila com a mesma severidade: "é PEP" e
   * "faltou o endereço". A primeira exige julgamento humano; a segunda exige um campo. Enquanto as
   * duas eram {@code EM_REVISAO}, a fila de EDD enchia de casos que não pedem analista nenhum — na
   * prática, o volume que o time de operações mais via era o que menos precisava dele.
   *
   * <p><b>Não é reprovação.</b> Reprovar por falta de dado mentiria na trilha (a recusa não teria
   * um fator de risco que a justificasse pelo nome), contaminaria a taxa de recusa que o regulador
   * lê como indicador de PLD-FT, e seria terminal — o cliente que mandasse o dado depois precisaria
   * de uma avaliação nova, sem vínculo com esta.
   *
   * <p>Sai deste estado pelo mesmo caminho que entrou: completar o cadastro
   * ({@code PUT /v1/subjects/{documento}/profile}) e submeter nova avaliação. Não é decidível por
   * revisor — {@code Assessment.decide} continua exigindo EM_REVISAO, para ninguém "aprovar" um
   * cadastro que continua incompleto.
   */
  SOLICITAR_DOCUMENTO,
  /**
   * Não foi possível concluir o processamento após as tentativas previstas.
   *
   * <p>Existe para tirar a avaliação do limbo: antes, uma falha repetida a deixava EM_ANALISE
   * para sempre, sendo reprocessada a cada 2 segundos, e o cliente não tinha como distinguir
   * "ainda processando" de "nunca vai concluir". É estado terminal automático — a retomada é
   * operacional (corrigir a causa e reenfileirar), não silenciosa.
   */
  FALHA_PROCESSAMENTO
}
