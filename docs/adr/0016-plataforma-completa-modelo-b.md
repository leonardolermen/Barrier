# ADR-0016: Virada para plataforma completa (Modelo B), em três frentes sequenciadas

- **Status:** Aceito
- **Data:** 2026-08-12
- **Substitui parcialmente:** [ADR-0005](0005-product-model-risk-engine.md) — o "começar pelo
  Modelo A" continua descrevendo o que existe hoje; a decisão de *quando* ir para B é esta.

## Contexto

O ADR-0005 escolheu o Modelo A (motor de risco, parceiro dono do cadastro, Barrier **operador**
LGPD) e desenhou a arquitetura prevendo a evolução para o Modelo B (plataforma completa, Barrier
**controlador** de dado sensível). A decisão de produto agora é fazer essa virada.

O que o motor já faz: identidade contra bureau real (CPF/CNPJ), screening em OFAC/CSNU/CGU/PEP com
match por documento e por nome, KYB de 1º grau, cadastro CMN 4.753, EDD com trilha e monitoramento
contínuo. O que falta para chamar isso de KYC completo são três coisas de naturezas diferentes,
e tratá-las como um bloco só é o erro a evitar:

1. **Veracidade do dado declarado.** `RegistrationCompleteness` verifica se o campo está
   preenchido, não se é verdadeiro. Cadastro preenchido com dados plausíveis e inventados satisfaz
   o gate e **libera aprovação automática**. Não envolve dado sensível novo.
2. **Prova de que a pessoa é o titular.** Documentoscopia (OCR + autenticidade do documento) e
   biometria facial com prova de vida. É o que hoje não existe: o motor confirma que o CPF é
   regular e que o nome bate, não que quem está do outro lado é o titular.
3. **Beneficiário final (UBO).** Cadeia societária além do 1º grau com percentual. Depende de
   dataset de relacionamentos contratado; não envolve dado sensível de biometria.

A assimetria que ordena tudo: **guardar biometria é irreversível**. Base biométrica vazada não se
revoga — ninguém troca de rosto. É por isso que a etapa 3 abaixo guarda o resultado da verificação
e não a imagem: o acervo que não existe não vaza, e o que se perde com isso está registrado lá.

O que continua valendo mesmo sem imagem: o resultado da verificação, os campos extraídos do
documento e o cadastro seguem sendo dado pessoal, e hoje o projeto ainda tem `raw_response` do
bureau e o segredo de HMAC do webhook **em texto puro** no banco (pendência conhecida da Fase 6).

## Decisão

Vamos para o Modelo B, em quatro etapas nesta ordem, e **não** vamos gravar documento ou biometria
antes da etapa 2 estar pronta:

1. **Veracidade do dado declarado** — OTP de telefone/e-mail, validação de endereço e nascimento
   conferido contra o bureau. `SubjectProfile` passa a distinguir campo *declarado* de campo
   *verificado*, e o gate de completude passa a exigir verificação nos campos que a sustentam.
   Escolhida como primeira porque é a que fecha a porta de aprovação automática com dado falso, e
   não aumenta a exposição de dado sensível em nada.
2. **Cifragem em repouso** (KMS + envelope encryption), aplicada antes de qualquer captura, e
   retroativa ao que já existe: `raw_response`, segredo de HMAC, cadastro. É pré-requisito, não
   item paralelo.
3. **Documentoscopia + biometria, guardando o **resultado**, não o acervo.** Atrás de interface de
   provider (`DocumentVerificationProvider`, `BiometricVerificationProvider`), com consentimento
   explícito registrado.

   **A imagem não é armazenada** — nem a foto do documento, nem a selfie, nem o template
   biométrico. O que fica é o desfecho (bateu / não bateu / inconclusivo), o score, o provedor, o
   identificador da consulta no provedor e os campos extraídos que o cadastro já usaria de qualquer
   forma (nome, documento, nascimento). É o mesmo padrão que `BureauTrace` já aplica ao bureau:
   ponteiro para a cópia íntegra que o provedor mantém sob o controle de acesso dele.

   Consequência disso, e é a razão de estar escrito aqui: **o Barrier deixa de ser controlador de
   dado sensível**. Biometria é dado sensível pelo art. 5º, II da LGPD; resultado de comparação
   não é. Some a exigência de tratamento de dado sensível, o RIPD encolhe para dado pessoal comum,
   e o pior cenário de vazamento deixa de existir — base biométrica vazada não se revoga, porque
   ninguém troca de rosto.

   **O que se perde, e o preço é real:** sem a imagem, não dá para reprocessar a comparação com
   outro algoritmo nem reapresentar a prova numa contestação anos depois. "A face bateu 98%" passa
   a ser afirmação nossa sobre nós mesmos, sustentada por um identificador em um sistema de
   terceiro — e se o contrato com o provedor acabar, a evidência vai junto. Mitigações: exigir em
   contrato retenção e acesso à evidência compatíveis com os 10 anos regulatórios; guardar o
   **hash** da imagem submetida, que não é dado biométrico mas permite provar que uma imagem
   apresentada depois é a mesma que foi analisada; e registrar a versão do modelo/algoritmo do
   provedor junto do resultado, sem a qual o score não significa nada seis meses depois.

   A captura, quando possível, vai **direto do dispositivo para o provedor** (upload assinado), de
   modo que a imagem não transite pela infraestrutura do Barrier em momento nenhum — não trafegar
   é mais forte que trafegar e não guardar.
4. **UBO até 3º grau**, com provider de relacionamentos atrás de interface e stub em dev; UBO
   indeterminado força REVIEW em vez de passar em silêncio.

   A estratégia de **custo** desta etapa está no
   [ADR-0018](0018-custo-de-navegacao-ubo.md) — ordem por custo/benefício, short-circuit com
   propagação ascendente e a proibição de cortar a navegação por profundidade. Ele vale antes
   de existir código aqui: "até 3º grau" é a obrigação mínima, não um teto de navegação.

## Alternativas consideradas

- **As três frentes em paralelo** — entrega mais rápida no papel. Descartada: colocaria captura de
  biometria em produção antes da cifragem, que é exatamente a ordem que não dá para corrigir depois.
- **Documento e biometria primeiro (é o que "parece" KYC)** — é a frente mais visível
  comercialmente, mas deixaria de pé o furo que já permite aprovação automática com dado inventado.
  Prova de vida sobre um cadastro não verificado prova que alguém está vivo, não que o cadastro é
  daquela pessoa.
- **Continuar em Modelo A e integrar KYC de terceiro sem guardar nada** — mantém a exposição baixa
  e é uma saída legítima (o provider guarda, o Barrier referencia). Descartada por decisão de
  produto: sem acervo próprio não há system of record, que é o ponto da plataforma. Fica registrada
  como plano B se a exposição de controlador se mostrar cara demais.

## Consequências

- **Positivas:** produto completo de KYC/PLD-FT; o acervo próprio sustenta auditoria e a
  reapresentação de prova anos depois; UBO fecha o KYB que hoje para no 1º grau.
- **Negativas / custos:** o Barrier passa a ser controlador de **dado pessoal** (cadastro,
  resultados de verificação), com base legal, consentimento por finalidade, direitos do titular e
  retenção de 10 anos como obrigação própria. Não de dado **sensível**, porque não guarda imagem
  nem template — essa é a diferença que a etapa 3 compra. Custo de KMS e de consulta por
  verificação (documentoscopia e biometria são cobradas por chamada).
- **Riscos e mitigações:** o risco dominante deixa de ser vazamento de biometria e passa a ser
  **dependência do provedor para a evidência** — mitigado por contrato de retenção compatível com
  os 10 anos, pelo hash da imagem submetida e pelo registro da versão do algoritmo junto do
  resultado. O risco secundário é regulatório: enquanto as bases legais e o registro de
  consentimento não existirem, a etapa 3 fica bloqueada mesmo com o código pronto — e isso é
  intencional.

## Revisão (2026-08-12) — implementação da frente de assurance

O texto de "Decisão" acima **não foi apagado**: é o raciocínio que valia quando este ADR foi
escrito, e a ordenação que ele descreve (etapa 2 como pré-requisito bloqueante da etapa 3) era
correta para o cenário que ele considerava — guardar a imagem. O autor original revê a própria
decisão à luz de uma escolha que já estava registrada no próprio texto (item 3: "a imagem não é
armazenada"), mas cujo efeito sobre o *sequenciamento* não tinha sido puxado até o fim. Corrige-se
aqui, e não se reescreve acima, porque um ADR serve exatamente para preservar o "por quê" de antes
e mostrar o que mudou.

**1. A etapa 2 (cifragem em repouso) deixa de ser pré-requisito bloqueante da etapa 3.**

O sequenciamento original existia porque uma base biométrica em texto puro é catastrófica — base
vazada não se revoga, ninguém troca de rosto. Isso continua verdade para *imagem* e *template*.
Mas a decisão já tomada nesta mesma seção é não guardar nem uma coisa nem outra: o que fica no
banco é o desfecho (bateu/não bateu/inconclusivo), o score, o provedor e a referência da consulta
no provedor — mesmo padrão de `BureauTrace`, ponteiro para a cópia íntegra que o provedor mantém.
Isso é **dado pessoal comum**, do mesmo nível do `raw_response` do bureau (V031) e do segredo de
HMAC do webhook (V005) — ambos já persistidos sem cifragem, como pendência conhecida da Fase 6, e
sem que isso tenha bloqueado a construção do resto do sistema. Não há motivo para tratar o
resultado de assurance de forma mais restritiva do que dado que já está em produção sem cifragem.

Cifragem em repouso continua necessária e continua no plano (Fase 6) — a mudança é só que deixa de
ser bloqueio para a etapa 3 poder ser construída e ligada. O risco que a sequência original evitava
(capturar biometria antes de cifrar) segue evitado por outro mecanismo: **a imagem nunca é
armazenada**, então não existe acervo biométrico para cifrar em primeiro lugar.

**2. A etapa 1 (veracidade do dado declarado) consta como substancialmente implementada — não
concluída.**

`subject_field_verifications` (migration V034), `FieldVerification`, `VerificationMethod` e
`FieldVerificationService` implementam a distinção entre campo declarado e campo verificado; OTP
cobre telefone/e-mail, nascimento é verificável tanto contra o bureau (`recordBirthDateFromBureau`)
quanto contra a documentoscopia (`recordBirthDateFromDocument`, etapa 3), e o gate de completude
passou a exigir verificação nos campos que essas fontes sustentam.

> **Correção (revisão final):** esta seção afirmava a etapa 1 concluída; é otimista. Falta
> **validação de endereço** — `VerifiableField.ADDRESS` e `VerificationMethod.ADDRESS_LOOKUP` estão
> definidos e nunca usados, e `FieldVerificationService.challenge` recusa `ADDRESS` explicitamente.
> Cadastro plausível e inventado ainda satisfaz o gate **para o campo endereço** especificamente —
> o furo original está fechado para telefone, e-mail e nascimento, não para todo campo verificável.
> Por isso o [backlog de produto](../product/backlog.md) mantém o item "Verificar dados, não
> só presença" **aberto**: o plano de remediação está certo, este ADR estava otimista.

**3. O bloqueio por consentimento passa a estar atendido.**

A etapa 3 previa "consentimento explícito registrado" como parte do desenho. Isso está implementado
na submissão: `AssuranceConsent` é obrigatório na assinatura do serviço (`AssuranceService.
verifyDocument`/`verifyBiometrics`), gravado junto do `AssuranceCheck` (colunas em V036), e a
ausência de consentimento recusa com 400 antes de qualquer chamada ao provedor — nunca silenciosa,
nunca depois do fato.

**Consequência prática:** a etapa 3 (documentoscopia + biometria) deixou de estar bloqueada pela
etapa 2 e foi implementada — `AssuranceService`, `IdentityAssuranceRiskRule`,
`AssuranceRecordedListener`/`AssuranceReassessmentTrigger` e a reavaliação automática. O que segue
em aberto é exatamente o que a seção "Fora de escopo" da spec de implementação já registrava:
cifragem em repouso (Fase 6, agora sem bloquear esta frente), o subsistema completo de
consentimento (revogação, expiração, gestão por finalidade) e UBO além do 1º grau (etapa 4 deste
ADR).
