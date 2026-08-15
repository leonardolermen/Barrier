# ADR-0018: Custo de navegação societária (UBO) — ordem, short-circuit e a proibição de cortar por profundidade

- **Status:** Aceito
- **Data:** 2026-08-15

## Contexto

A [etapa 4 do ADR-0016](0016-plataforma-completa-modelo-b.md) define a frente de UBO em uma
linha: *"UBO até 3º grau, com provider de relacionamentos atrás de interface e stub em dev;
UBO indeterminado força REVIEW em vez de passar em silêncio"*. O desenho de **verificação**
está certo. Não há **nenhuma** palavra sobre custo.

Navegação societária é o caso mais explosivo que existe no produto, e por uma razão estrutural:
**cada nó da árvore é uma consulta paga, e a árvore não tem tamanho conhecido de antemão.**
Todos os controles de custo que o Barrier construiu até aqui pressupõem que o trabalho de uma
avaliação é limitado — um documento, uma consulta. O UBO quebra essa premissa: uma única PJ
pode gerar dezenas de consultas, e ninguém sabe quantas antes de começar a navegar. A R$0,04
por consulta na BigBoost ([ADR-0014](0014-bureau-cpf-bigboost.md)), uma árvore larga que
ninguém limitou é uma fatura que ninguém aprovou.

Escrever a estratégia de custo **depois** do código é escrevê-la depois da fatura. O
ecossistema Origem já navegou essa árvore em produção e registrou as decisões no
[`adr-derivacao-quadro-custos.md`][origem]; o desenho de lá é melhor que o nosso em três
pontos, e este ADR os traz literalmente. Ver [lições do Origem][licoes], prioridade 6.

## Decisão

Vamos fixar **três restrições não-negociáveis** sobre a navegação societária, e elas valem
antes de existir código:

### 1. Ordem por custo/benefício

Dentro de cada PJ, consultar os **CPFs de beneficiários antes dos sócios PJ** — é o mais
barato e o mais provável de barrar cedo (uma pessoa em lista de sanção encerra a subárvore
inteira; uma PJ só abre outra subárvore). Esgotada a camada de CPF, seguir por
**profundidade primeiro**: esgotar completamente a subárvore de um sócio PJ antes de começar a
do irmão.

Buscar em largura parece mais justo e é mais caro: gasta a camada inteira antes de ter chance
de encontrar o motivo de parada.

### 2. Short-circuit com propagação ascendente

Sócio reprovado marca **todos** os PJs da pilha de navegação como recusados, com motivo
`socio_reprovado:<documento>`, e os sócios ainda não analisados ficam em `analise_suspensa` —
**sem reconsulta de bureau**.

A árvore para de custar no instante em que a resposta já está determinada. Continuar navegando
depois de encontrar o motivo de reprovação é pagar por informação que não muda a decisão.
`analise_suspensa` existe para que a trilha distinga "não analisado porque a resposta já estava
dada" de "não analisado porque falhou" — a mesma distinção entre *rodou e passou* e *estava
desligada* que o Barrier já faz nas regras de risco.

### 3. Sem teto de profundidade

**A parada é permitida só por reprovação — nunca por largura, profundidade ou percentual de
participação.**

Esta é a restrição que mais custa dinheiro e a única que não se negocia. Cortar por
profundidade seria mais barato e **erraria**: o beneficiário final costuma estar exatamente no
fundo, e uma estrutura desenhada para esconder o controlador é precisamente uma estrutura
profunda. Um limite de profundidade não é uma otimização de custo, é um bug de compliance que
se apresenta como economia — e um que a trilha registraria como "navegou e não achou" em vez
de "não navegou".

O "até 3º grau" do ADR-0016 é a leitura da **obrigação regulatória mínima**, não um teto de
navegação. Onde a cadeia continuar acima de 3º grau e o custo permitir, navega-se; onde a
navegação for interrompida por qualquer motivo que não seja reprovação, o resultado é UBO
indeterminado → REVIEW, como o ADR-0016 já manda.

## O que isto exige do que já existe

- **O reuso de verificação de identidade (V040) é o que torna a navegação viável.** Um sócio
  que aparece em duas árvores dentro do TTL não paga duas vezes; sem ele, cada avaliação de PJ
  reabre a árvore inteira do zero. Mas o reuso hoje é **só CPF**: `IdentityResult.company` é
  transiente, não é persistido no `identity_check`, e reusar um check de PJ devolveria
  `company == null`.

  **Consequência direta:** reidratar o `CompanyProfile` a partir do `raw_response` deixa de ser
  opcional quando o UBO entrar. Hoje está registrado como fora de escopo; passa a ser
  pré-requisito da etapa 4.

- **`CorporateStructureCoverageRiskRule` (V039) é o guard que impede a árvore vazia de virar
  aprovação silenciosa** — bureau confirma a PJ mas devolve `partners()` vazio → REVIEW. Ele
  já cobre o 1º grau; a navegação profunda herda o mesmo princípio fail-closed em cada nível,
  e é o que faz "UBO indeterminado força REVIEW" ser verdade em vez de intenção.

- **A cota por tenant ([ADR-0015](0015-ingestao-em-massa-faixa-separada.md)) é o teto real.**
  Sem teto de profundidade, a única defesa contra uma árvore patológica é a cota — reuso ataca
  repetição, cota ataca volume, e o UBO é volume imprevisível. Uma avaliação de PJ precisa
  consumir cota **por nó navegado**, não por avaliação; contar 1 seria contar errado por uma
  ordem de grandeza.

## Alternativas consideradas

- **Teto de profundidade configurável por tenant.** Barato de implementar, controlável
  comercialmente, e a forma mais provável de o limite aparecer sem ninguém notar que é
  compliance. Descartada pela restrição 3: o parâmetro existiria para ser reduzido sob pressão
  de custo, e a redução silenciaria justamente o caso que justifica o controle.
- **Navegar em largura e paralelizar.** Melhora latência, piora custo — gasta a camada inteira
  antes de ter chance de encontrar o motivo de parada, e a paralelização torna o short-circuit
  ineficaz (as consultas já saíram quando a reprovação chega).
- **Não navegar: comprar UBO pronto de um provider de KYB.** Legítima e possivelmente mais
  barata, e é o que a interface de provider do ADR-0016 permite. Não elimina este ADR: um
  provider que devolve a árvore pronta cobra por profundidade do mesmo jeito, e as restrições 2
  e 3 continuam valendo sobre o que se faz com o resultado.

## Consequências

- **Positivas:** o custo da frente mais cara do roadmap fica limitado por desenho, não por
  parâmetro; o short-circuit é o mecanismo de economia, e ele economiza exatamente onde a
  informação não muda a decisão; a proibição de cortar por profundidade fica registrada antes
  de alguém propô-la como otimização em code review.
- **Negativas / custos:** uma árvore larga e limpa (nenhuma reprovação, muitos sócios) é o pior
  caso e não tem defesa além da cota — navegação completa, custo integral. É deliberado: é o
  caso em que a resposta *não* estava determinada.
- **Riscos e mitigações:** sem teto, uma cadeia cíclica ou um provider com dado ruim pode
  navegar indefinidamente. Mitigação — que **não** é teto de profundidade: memoização por
  documento dentro de uma mesma navegação (nó já visitado não é revisitado), o que resolve
  ciclo sem esconder profundidade legítima.

[origem]: ../../../Backend/bmp-origem-back/Docs/Cadastro%20Unico/adr-derivacao-quadro-custos.md
[licoes]: ../implementation/licoes-do-origem.md
