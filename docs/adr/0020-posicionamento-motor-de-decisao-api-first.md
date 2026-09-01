# ADR-0020: Posicionamento — motor de decisão API-first, antes da jornada embarcada

- **Status:** Aceito
- **Data:** 2026-08-22 (registrado como ADR em 2026-08-31)

## Contexto

O objetivo declarado do Barrier é *plataforma de KYC/onboarding rodando o fluxo para parceiros
B2B*. O que existe é a **camada de decisão atrás** desse fluxo — e a diferença entre as duas não é
backlog, é uma camada inteira.

A evidência não está em opinião, está no contrato: `SubmitDocumentRequest` **não carrega imagem**,
só um `captureReference` de um upload feito direto do dispositivo para o provedor
([ADR-0016](0016-plataforma-completa-modelo-b.md)). Quem conduz a jornada do cliente final é o
parceiro. Não há frontend algum no repositório, e a auditoria externa de `e141669` deu 3,5 em
*product readiness* justamente por medir o produto contra a promessa, não contra o código.

O [ADR-0005](0005-product-model-risk-engine.md) já previa uma evolução A→B. Este ADR fixa **que A
vem inteiro antes de B**, e por quê.

## Decisão

Vamos vender e construir o **posicionamento A: motor de decisão API-first**. O parceiro já tem a
jornada dele e compra de nós **decisão explicável e trilha auditável**. Em A, **o produto é a
integração** — a documentação, o sandbox e o contrato não são acessórios do produto, são ele.

Fica explicitamente para depois o **posicionamento B**, a jornada embarcada: hosted page, SDK de
captura, UI da mesa de análise, console visual.

Três razões, em ordem de peso:

1. **A vende o que já existe.** A auditoria registrou que a trilha do Barrier é *"melhor que a de
   fornecedores estabelecidos"*. É o único ponto do produto onde não se compete de baixo para cima.
2. **B compete de frente com incumbente** (Unico, Idwall, CAF) exatamente na parte que ainda não
   existe — captura, liveness, três superfícies de frontend — e contra quem tem bureau próprio.
3. **A financia B.** Os primeiros parceiros pagam para descobrir o que B precisa ter, em vez de
   adivinharmos.

### A restrição de arquitetura a segurar se B for construído

Hoje o sistema **nunca toca em dado biométrico**: a imagem vai do dispositivo ao provedor sem
passar por aqui. Biometria é dado pessoal **sensível** na LGPD, e não armazená-la encurta o
questionário de segurança e reduz o impacto de um vazamento em ordens de grandeza.

Uma hosted page pode preservar essa propriedade **se o upload continuar indo direto ao provedor**,
com o JavaScript apenas orquestrando. É mais trabalho, e é a diferença entre um incidente caro e um
incidente que encerra a empresa. Decidir isso depois de construir é decidir errado — por isso está
neste ADR e não no backlog.

### O que este posicionamento **não** é

Barrier vende para instituição regulada; **não é** ele o regulado. A responsabilidade pela Circular
BCB 3.978 continua sendo da instituição contratante — ela terceiriza a execução, nunca a
responsabilidade. Não há autorização a pedir ao BACEN, nem capital mínimo.

O portão real é a **due diligence de fornecedor** da compradora, que opera sob a Resolução CMN
4.893 e a Resolução BCB 85: cláusulas de acesso do regulador e dos auditores, subcontratação,
localização dos dados, continuidade, devolução/eliminação no encerramento.

⚠️ Redação de cláusula e interpretação de norma são trabalho de advogado de compliance
regulatório. Este ADR registra a **forma** da exigência, para orientar arquitetura — não o texto
dela.

## Alternativas consideradas

- **B primeiro (jornada embarcada: hosted page + SDK + UI da mesa).** Recusada: é a parte que não
  existe, contra concorrentes com bureau próprio, e muda o perfil de exposição LGPD antes de haver
  receita que justifique o risco.
- **A e B em paralelo.** Recusada por capacidade, não por mérito. Com uma pessoa, paralelizar duas
  frentes de produto produz duas metades — e a auditoria já nomeou o padrão de risco do projeto:
  *construir pelo problema interessante em vez do problema bloqueante*.
- **Vender KYC diretamente a banco.** Recusada por sequenciamento comercial, não técnico: plantão
  24/7, *bus factor* de um, pentest e certificação, e ciclo de venda de 6 a 18 meses. O primeiro
  parceiro deve ser quem tem obrigação real de KYC e due diligence de fornecedor mais leve —
  operadores de aposta (Lei 14.790/2023) e VASPs (Lei 14.478/2022) são as duas janelas abertas.

## Consequências

- **Positivas:** o esforço vai para onde já há vantagem (trilha, explicabilidade, replay); a
  propriedade de nunca tocar em biometria fica preservada por decisão, não por acidente; o escopo
  de frontend sai do caminho crítico.
- **Negativas / custos:** o comprador que quer "KYC completo com selfie" não é atendido só por A;
  documentoscopia e biometria seguem dependendo de provedor contratado, e o gate documento→biometria
  faz a ausência de provedor travar a frente inteira, não metade.
- **Riscos e mitigações:** o maior risco é A ser confundido com "só uma API" e virar commodity de
  preço. A mitigação é o replay de decisão e a política versionada com vigência e autoria — a
  capacidade que transforma trilha em categoria própria e que nenhum concorrente de bureau vende
  hoje. É por isso que ela é o primeiro item do backlog, e não o mais barato.

## Referências

- Plano original, com as fases de execução: [plano-produto-api-first.md](../implementation/archive/plano-produto-api-first.md) (arquivado)
- Backlog vivo derivado desta decisão: [docs/product/backlog.md](../product/backlog.md)
- [ADR-0005](0005-product-model-risk-engine.md) — modelo de produto A→B
- [ADR-0016](0016-plataforma-completa-modelo-b.md) — resultado, não acervo
