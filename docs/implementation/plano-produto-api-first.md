# Plano de produto — motor de decisão API-first (posicionamento A)

Documento vivo. Decisão de produto tomada em 2026-08-22, depois da auditoria de
[plano-auditoria-2026-08-18.md](plano-auditoria-2026-08-18.md).

**Como usar:** igual aos outros planos — critério de pronto **verificável**, não "implementar X"
mas "como sabemos que X funciona". Marque `[x]` só com prova, e registre o commit ao lado.

---

## A decisão

O objetivo declarado é **plataforma de KYC/onboarding rodando o fluxo para parceiros B2B**. O que
existe hoje é a **camada de decisão atrás** desse fluxo — e a diferença não é backlog, é uma
camada inteira.

A evidência está no contrato: `SubmitDocumentRequest` **não carrega imagem**, só um
`captureReference` de um upload feito direto do dispositivo para o provedor (ADR-0016). Quem
conduz a jornada do cliente final é o parceiro. Não há frontend algum no repositório.

**Posicionamento escolhido: A — motor de decisão API-first.** O parceiro já tem a jornada dele e
compra decisão explicável e trilha auditável. O que fica para depois (posicionamento B) é a
jornada embarcada: hosted page/SDK, UI da mesa, console visual.

### Por que A antes de B

- **A vende o que já existe.** A auditoria registrou que a trilha do Barrier é *"melhor que a de
  fornecedores estabelecidos"*. É o único ponto onde não se compete de baixo para cima.
- **B compete de frente com incumbente** (Unico, Idwall, CAF) exatamente na parte que ainda não
  existe — captura, liveness, três superfícies de frontend — e contra quem tem bureau próprio.
- **A financia B.** Os primeiros parceiros pagam para descobrir o que B precisa ter, em vez de
  adivinharmos.

⚠️ **A restrição de arquitetura a segurar se B for construído um dia:** hoje o sistema **nunca toca
em dado biométrico** — a imagem vai do dispositivo ao provedor sem passar por aqui. Biometria é
dado pessoal **sensível** na LGPD, e não armazená-la encurta o questionário de segurança e reduz o
impacto de um vazamento em ordens de grandeza. Uma hosted page pode preservar essa propriedade se o
upload continuar indo **direto ao provedor**, com o JavaScript apenas orquestrando. É mais trabalho,
e é a diferença entre um incidente caro e um incidente que encerra a empresa. Decidir isso depois de
construir é decidir errado.

### O que isto NÃO é

Barrier vende para instituição regulada; **não é** ele o regulado. A responsabilidade pela Circular
3.978 continua sendo da instituição contratante — ela terceiriza a execução, nunca a
responsabilidade. Não há autorização a pedir ao BACEN, nem capital mínimo. O portão real é a
**due diligence de fornecedor** da compradora, que opera sob a Resolução CMN 4.893 e a Resolução
BCB 85 (cláusulas de acesso do regulador e dos auditores, subcontratação, localização dos dados,
continuidade, devolução/eliminação no encerramento).

⚠️ Redação de cláusula e interpretação de norma são trabalho de advogado de compliance regulatório.
Este documento registra a **forma** da exigência para orientar arquitetura, não o texto dela.

---

## Fase 0 — Destravar (semanas 1–2, quase sem código)

Nada aqui é engenharia, e tudo aqui bloqueia o resto.

- [ ] **Empresa aberta**
  Ser CLT em geral não impede, mas **leia a cláusula de exclusividade/não-concorrência do seu
  contrato** antes — o produto é do mesmo setor do empregador. Desenvolvimento de software
  historicamente não cabe em MEI, então provavelmente é ME no Simples; o custo real é o contador
  mensal, não o registro.
  *Pronto quando:* CNPJ emitido, conta PJ aberta, contador contratado.

- [ ] **Resolver a contradição sobre o bureau de CPF**
  [ADR-0014](../adr/0014-bureau-cpf-bigboost.md) escolheu a BigBoost porque seria self-service sem
  CNPJ (R$0,04/consulta) e fecha com *"a confirmar com o time comercial deles"* — nunca confirmado.
  [bureau-simulado.md](bureau-simulado.md) afirma o oposto: *"não existe bureau real de CPF
  contratável sem CNPJ"*. **A decisão de arquitetura do bureau de PF está apoiada num "a
  confirmar", e o outro documento já supõe o contrário.**
  *Pronto quando:* resposta por escrito do comercial arquivada em `docs/`, e **um dos dois
  documentos corrigido** — a contradição não pode sobreviver a esta fase.

- [ ] **Verificar ao vivo as três fontes gratuitas de watchlist**
  CGU (CEIS/CNEP/PEP), OFAC (SDN+ALT) e CSNU/ONU são download público, sem cadastro e sem contrato.
  O 403 registrado no `CLAUDE.md` foi falta de egress no ambiente de dev, **não** barreira
  comercial. São a cobertura inteira de `SANCTION` em produção, e o layout do CSV da CGU nunca foi
  conferido contra o portal real — o `PepWatchlistSource` avisa isso no próprio Javadoc.
  *Pronto quando:* cada fonte baixada e importada num ambiente com egress, contagem de linhas
  registrada, e **um contract test com fixture do arquivo real** de cada uma — para que mudança de
  layout quebre o build, e não a cobertura em produção.

---

## Fase 1 — "um dev externo integra sozinho" (semanas 2–6)

**Em A, o produto é a integração.** Esta fase é o produto, não a documentação dele.

- [ ] **OpenAPI gerado, versionado e publicado**
  Hoje existe só um comentário no `pom.xml` dizendo "Fase 5".
  ⚠️ springdoc 2.x é para Boot 3; Boot 4 exige a linha 3.x — confirmar que resolve **antes** de
  planejar em cima.
  *Pronto quando:* `/v3/api-docs` cobre os dois serviços **e** um teste reflexivo falha se algum
  `@RequestMapping` de `/v1` não estiver documentado — no padrão do `ApiRouteCoverageTest`, que já
  existe e resolveu exatamente esta classe de esquecimento.

- [ ] **Sandbox — que já existe e ninguém sabe**
  O `FakeCpfBureauProvider` atende qualquer CPF válido e usa o prefixo `999` + dígito seletor para
  escolher o cenário (falecido, suspensa, nula, indisponível). **Isso é um sandbox completo**, com
  tabela documentada em [bureau-simulado.md](bureau-simulado.md). Falta expor como produto:
  ambiente público, credencial de teste self-service, e a tabela de cenários na doc externa.
  *Pronto quando:* um terceiro obtém credencial de sandbox sem falar com ninguém e reproduz os seis
  desfechos de identidade.

- [ ] **Guia de integração público**
  Quickstart de intake → webhook; **como verificar o HMAC** e o que fazer com
  `X-Barrier-Signature-Previous` durante a rotação; semântica de `Idempotency-Key`; catálogo de
  reason codes; versão externa do [event-catalog.md](../architecture/event-catalog.md).
  *Pronto quando:* um dev externo integra intake + webhook lendo só a doc pública, sem contato — e
  isso foi **observado com uma pessoa real**, não presumido.

- [ ] **Listagem paginada de avaliações**
  Não há listagem: o parceiro que perdeu um webhook não tem como reconciliar, e hoje a resposta é
  SQL — suporte nível 3 virando produto.
  *Pronto quando:* cursor estável em toda coleção, com teste de página sob inserção concorrente.

⚠️ **HMAC sem timestamp entra aqui, não depois.** Um payload capturado é replayável para sempre
contra o cliente. Adicionar `t=` assinado com tolerância (padrão Stripe) é barato **agora** e vira
quebra de contrato depois que houver parceiro integrado.

---

## Fase 2 — "dá para deixar isso na internet" (semanas 6–10)

- [ ] **Cota e rate limit por tenant**
  Fecha três coisas de uma vez: DoS trivial (290 req/s medidos de ingestão contra nenhuma
  barreira), noisy neighbor, e fatura de bureau ilimitada.
  ⚠️ **Subiu de prioridade por causa da branch `perf/paralelismo-pipeline`.** O P2 dizia
  explicitamente que paralelizar vem *depois* da cota; foi feito antes. Hoje o único limite de
  consultas pagas é o semáforo, que é **por pod** — com 5 réplicas são 20 simultâneas, sem
  isolamento por parceiro. Paralelizar sem cota acelerou a fatura.
  *Pronto quando:* cota por tenant no intake **e** no lote de processamento; teste provando que
  tenant em bulk não atrasa o p99 do vizinho. Fecha junto o item do re-KYC periódico, que documenta
  a mesma limitação ("o teto é global e a ordem por antiguidade não isola tenants").

- [ ] **Criptografia em repouso**
  Grep por `encrypt|Cipher` no projeto: **zero**. Em claro hoje: CPF/CNPJ, nome, nascimento,
  endereço, `raw_response` do bureau, e **o segredo HMAC na coluna**. Este último não é só exposição
  de PII: um dump de backup permite **forjar o callback de KYC de qualquer tenant** — exatamente o
  ataque que o segredo por tenant existe para impedir.
  *Pronto quando:* campos de PII e o segredo HMAC cifrados, com rotação de chave testada.

- [ ] **Retenção com expiração e legal hold**
  Sem política, sem job, sem coluna de prazo. O `raw_response` guarda dado pessoal indefinidamente.
  *Pronto quando:* prazo por categoria de dado, job de expiração com teste, e legal hold que
  suspende a expiração de um caso sob investigação.

---

## Fase 3 — o diferencial (semanas 10–13)

- [ ] **Replay de decisão**
  Maior retorno por esforço do repositório, e quase pronto sem ninguém notar: `evaluated_json` (com
  regras suprimidas e parâmetro efetivo), `config_history` (V033), snapshot de versão de lista,
  `identity_check_id`/`screening_result_id` exatos e `ENGINE_VERSION` **já são gravados**. Os dados
  estão lá; a capacidade, não.
  **É o primeiro pedido de um fiscal, e é o que transforma "mais um KYC" em categoria própria.**
  *Pronto quando:* `POST /v1/assessments/{id}/replay` reproduz o desfecho histórico bit a bit e
  aponta a diferença quando o motor atual decide outra coisa.

---

## Fora de escopo, com motivo

Recusa com critério, no padrão do schema registry (F9) — para a pergunta não voltar em três meses
sem o racional junto.

| Item | Por que fica de fora de A |
|---|---|
| Hosted page / SDK de captura | É o posicionamento B. Muda a exposição LGPD (ver ⚠️ acima) |
| UI da mesa de análise | B. Em A o analista é do parceiro, na ferramenta do parceiro |
| Biometria e documentoscopia | Provedor é B2B com contrato; sem CNPJ não há caminho. Pipeline já pronto |
| UBO / dataset de QSA | Pago. **Sem ele, toda PJ atendida pelo bureau real vai a revisão manual** — se o primeiro parceiro for PJ, isto volta a ser bloqueador |
| SSO/OIDC, RBAC, 4-eyes | Enterprise. Chega com o comprador que exige, não antes |
| COAF/SISCOAF | Obrigação da instituição contratante, não do fornecedor, nesta etapa |

⚠️ **`SerproBureauProvider` é esqueleto morto** — sem `@Component`, `check()` só lança
`BureauUnavailableException`. Não conte com ele em estimativa nenhuma.

---

## O limite que nenhum item acima resolve

Registrado porque metade do valor de um plano é o que ele admite.

Uma pessoa não vende KYC para instituição regulada. Não por causa do código — por causa de plantão
24/7 (pipeline parado às 3h é receita parada do cliente), *bus factor* (está em todo questionário de
fornecedor, e "sou eu" reprova), custo de pentest e certificação, e ciclo de venda de 6 a 18 meses a
atravessar sendo CLT.

A auditoria já tinha escrito a mesma frase: *"a distância entre production-ready e enterprise-ready
não é técnica, é organizacional — e é onde o projeto de uma pessoa encontra o limite real."*

**Consequência prática para o sequenciamento:** o primeiro parceiro não deve ser banco. Deve ser
quem tem obrigação real de KYC e due diligence de fornecedor leve — operadores de aposta (Lei
14.790/2023) e VASPs (Lei 14.478/2022) são as duas janelas abertas hoje. E procurar um sócio
comercial ou de compliance destrava mais itens desta lista do que qualquer coisa que se possa
programar.
