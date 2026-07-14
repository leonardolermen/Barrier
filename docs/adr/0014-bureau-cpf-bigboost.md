# ADR-0014: Bureau real de CPF via BigBoost, desligado por padrão

- **Status:** Aceito
- **Data:** 2026-07-14

## Contexto

`StubBureauProvider` sempre confirma qualquer CPF sintaticamente válido — não há verificação de
existência/regularidade real. A alternativa mais óbvia, Serpro (Consulta CPF/Datavalid), exige
contrato B2B e **CNPJ para contratar**; o produto ainda está sendo formalizado (usuário buscando
CNPJ no momento desta decisão — ver contexto de produto), então essa via fica bloqueada por
pré-requisito legal, não técnico.

BigBoost (BigDataCorp) oferece o dataset `basic_data` (nome, CPF, data de nascimento, nome da
mãe, status do CPF na Receita) com **API Reference pública** e modelo de cobrança **self-service
por consulta** (R$0,04/consulta na faixa inicial, sem contrato mínimo aparente na doc), o que
permite contratar antes mesmo de ter o CNPJ formalizado — a confirmar com o time comercial deles,
mas o modelo de acesso já é bem mais aberto que o do Serpro.

## Decisão

Implementar `BigBoostBureauProvider` (`identity/client/`) como bureau real de CPF, seguindo
exatamente o mesmo padrão do `BrasilApiBureauProvider` (RestClient + mapeamento para
`BureauResult`), mas **desligado por padrão** (`barrier.identity.bigboost.enabled=false`) —
dev/testes continuam usando `StubBureauProvider`. Quando as credenciais (`AccessToken`/
`TokenId`) estiverem disponíveis, basta setar as env vars `BIGBOOST_ACCESS_TOKEN`/
`BIGBOOST_TOKEN_ID` e a flag, sem mudar código.

- `@Order(20)` — depois do bureau real de CNPJ (`BrasilApiBureauProvider`, `@Order(10)`), antes
  do stub (`@Order(100)`). Quando habilitado, `IdentityService` tenta BigBoost primeiro e cai
  para o stub só se o bureau real estiver indisponível.
- Schema de request/response modelado a partir do exemplo oficial da API Reference
  (`POST https://plataforma.bigdatacorp.com.br/pessoas`, dataset `basic_data`) — ver DTOs
  `BigBoostBasicDataRequest`/`BigBoostBasicDataResponse`. `Result` vazio → `NOT_FOUND`; não-vazio
  → `MATCH`. O status do CPF na Receita (regular/irregular, que daria `MISMATCH`) é descrito na
  doc mas o nome exato do campo não foi confirmado no exemplo público (truncado) — a confirmar
  quando a API key for contratada; até lá, qualquer CPF encontrado na base conta como `MATCH`.
- Testes (`BigBoostBureauProviderTest`) usam `MockRestServiceServer` com o JSON de exemplo
  oficial da doc — não dependem de credenciais reais nem de rede.

## Alternativas consideradas

- **Aguardar o CNPJ e contratar Serpro diretamente** — mais "oficial" (é a fonte primária,
  Receita Federal), mas trava o bureau real de CPF indefinidamente até a formalização da
  empresa. Rejeitado por ora; nada impede de adicionar `SerproBureauProvider` como mais uma
  opção no futuro (a interface `BureauProvider` já suporta múltiplos providers por tipo).
- **Mock local com dataset fixo (sem nenhum provider real)** — resolve o "testar em dev" mas não
  avança o gap de produção. Rejeitado: o `StubBureauProvider` já cumpre esse papel, e o ganho
  aqui é justamente ter uma opção real pronta para ligar assim que o contrato existir.

## Consequências

- **Positivas:** bureau real de CPF pronto para produção sem depender do CNPJ estar pronto;
  troca de ambiente é só configuração (flag + credenciais), sem deploy de código novo.
- **Negativas / custos:** custo por consulta (R$0,04 na faixa inicial) quando habilitado; campo
  de status do CPF na Receita não confirmado, então `MISMATCH` (CPF irregular/cancelado) ainda
  não é detectado — só `MATCH`/`NOT_FOUND` por ora.
- **Riscos e mitigações:** se a BigBoost mudar o schema da resposta, o parsing falha
  silenciosamente para campos desconhecidos (`@JsonIgnoreProperties(ignoreUnknown = true)`) mas
  os testes com o payload oficial pegam quebras de campos usados. Revisitar o mapeamento de
  status assim que a API key for contratada e o schema completo puder ser confirmado.
