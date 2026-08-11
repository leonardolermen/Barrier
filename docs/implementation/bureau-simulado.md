# Bureau de CPF simulado (dev/teste)

Não existe bureau real de CPF contratável sem CNPJ — Serpro exige, e as alternativas
self-service ou não declaram a origem dos dados, ou exigem contrato de suboperador para LGPD
(ver [ADR-0014](../adr/0014-bureau-cpf-bigboost.md)). Enquanto isso, o `FakeCpfBureauProvider`
permite exercitar o pipeline inteiro sem provider pago e sem enviar CPF de terceiro para lugar
nenhum.

Ele **não sobe em `prod`** (`@Profile("!prod")`) e **nunca é autoritativo**
(`BureauProvider.authoritative() == false`), então também não serve de fallback para um bureau real
indisponível — indisponibilidade continua virando `UNAVAILABLE` → revisão humana, nunca "verificado".

## Como escolher o desfecho

Qualquer CPF válido é atendido. **CPF comum é sempre `REGULAR`** — os documentos que você já usa em
teste continuam funcionando como antes.

Para exercitar um cenário específico, use um CPF que comece com **`999`**: o **quarto dígito**
seleciona o desfecho. É a convenção dos cartões de teste da Stripe, e pelo mesmo motivo — quem
testa quer escolher o resultado, não descobri-lo.

| CPF | Sem máscara | Cenário | Desfecho no bureau | Decisão esperada |
|---|---|---|---|---|
| `999.000.000-05` | `99900000005` | REGULAR | `MATCH` | APROVADO (se o cadastro estiver completo) |
| `999.100.000-32` | `99910000032` | TITULAR_FALECIDO | `DECEASED` | **REPROVADO** — `IDENTITY_DECEASED` |
| `999.200.000-78` | `99920000078` | OBITO_SEM_STATUS | `DECEASED` | **REPROVADO** — situação REGULAR com indicação de óbito |
| `999.300.000-03` | `99930000003` | SUSPENSA | `MISMATCH` | EM_REVISAO — `IDENTITY_MISMATCH` |
| `999.400.000-49` | `99940000049` | PENDENTE | `MISMATCH` | EM_REVISAO |
| `999.500.000-84` | `99950000084` | NULA | `NOT_FOUND` | **REPROVADO** — `IDENTITY_NOT_FOUND` |
| `999.600.000-10` | `99960000010` | NAO_ENCONTRADO | `NOT_FOUND` | **REPROVADO** |
| `999.700.000-55` | `99970000055` | INDISPONIVEL | lança `BureauUnavailableException` | EM_REVISAO — `IDENTITY_UNAVAILABLE` |

O último é o mais fácil de esquecer e o mais importante de exercitar: é o único jeito de verificar
que um bureau fora do ar **não** cai no simulado e vira aprovação.

Qualquer CPF `999X…` com `X` fora da faixa também é `REGULAR` — um documento que por acaso comece
com `999` não deve virar "falecido" sem alguém ter pedido.

## O que ele não testa

Ele valida que **a aplicação** reage certo a cada desfecho. Ele **não** valida que a BigBoost
responde no formato que o `BigBoostBureauProvider` espera — isso é
`BigBoostBureauProviderTest`, que passa o JSON documentado pelo parser real, incluindo o
mapeamento de `TaxIdStatus` e `HasObitIndication`.

Confundir "temos mock" com "temos integração verificada" é como o CSV da CGU entrou no
repositório sem verificação.

## A armadilha ao ligar um bureau de verdade

Apontar `barrier.identity.bigboost.base-url` para `localhost` torna o provider **autoritativo** e
desarma o `CpfBureauReadinessGuard` — a trava que impede produção sem KYC de PF real deixaria de
disparar, sem ninguém ter decidido isso. O guard recusa a subida em `prod` nesse caso; a checagem
existe justamente porque é config de dev copiada por engano.
