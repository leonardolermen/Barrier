# ADR-0016: Virada para plataforma completa (Modelo B), em três frentes sequenciadas

- **Status:** Proposto
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
2. **Prova de que a pessoa é o titular.** Documento (OCR + autenticidade) e biometria facial com
   prova de vida. Envolve **dado sensível** (art. 5º, II da LGPD) e é o que muda o papel da empresa.
3. **Beneficiário final (UBO).** Cadeia societária além do 1º grau com percentual. Depende de
   dataset de relacionamentos contratado; não envolve dado sensível de biometria.

A assimetria que ordena tudo: **guardar biometria é irreversível do ponto de vista regulatório**.
No instante em que a primeira selfie é gravada, valem cifragem em repouso, gestão de consentimento,
direitos do titular e retenção de 10 anos — e uma base de biometria em texto puro não se conserta
depois, porque o dado já vazou se vazar. Hoje o projeto ainda tem `raw_response` do bureau e o
segredo de HMAC do webhook **em texto puro** no banco (pendência conhecida da Fase 6).

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
3. **Documento + biometria**, atrás de interface de provider (`DocumentProvider`,
   `BiometricProvider`), com consentimento explícito registrado e retenção declarada por tipo de
   dado. Serviço próprio, base própria: dado sensível não compartilha tabela com dado cadastral.
4. **UBO até 3º grau**, com provider de relacionamentos atrás de interface e stub em dev; UBO
   indeterminado força REVIEW em vez de passar em silêncio.

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
- **Negativas / custos:** o Barrier vira **controlador** de dado sensível — DPO, relatório de
  impacto (RIPD), base legal e consentimento por finalidade, direitos do titular (acesso, exclusão,
  portabilidade) e retenção de 10 anos passam a ser obrigação própria, não do parceiro. Custo de
  infraestrutura de KMS e de provedor de biometria por consulta.
- **Riscos e mitigações:** o risco dominante é vazamento de base biométrica — mitigado pela ordem
  acima (cifragem antes de captura), por serviço e base separados, e por guardar o *template*
  biométrico e o resultado da comparação em vez da imagem sempre que o provider permitir. O risco
  secundário é regulatório: enquanto o RIPD e as bases legais não existirem, a etapa 3 fica
  bloqueada mesmo com o código pronto — e isso é intencional.
