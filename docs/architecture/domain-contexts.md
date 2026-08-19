# Bounded contexts

Decomposição do domínio de KYC/PLD-FT. Cada contexto é candidato a um microserviço com
o esqueleto de camadas clássicas descrito na [visão geral](overview.md).

## No MVP (fase 1 — motor de risco)

| Contexto             | Responsabilidade                                                 | Estado                                  |
|----------------------|------------------------------------------------------------------|-----------------------------------------|
| **Assessment**       | Recebe o pedido, orquestra o fluxo, agrega a decisão             | ✅ módulo da Risk Engine                 |
| **Identity Verification** | Valida CPF/CNPJ, cruza com bureaus (BrasilAPI real p/ CNPJ, BigBoost real p/ CPF, Serpro esqueleto) | ✅ módulo |
| **Screening / Watchlists** | Match PEP, sanções (ONU/OFAC/CGU real) e mídia negativa (stub) | ✅ módulo |
| **Subject / Cadastro** | Identidade mínima (dedup por documento) + cadastro CMN 4.753 progressivo, gate de completude | ✅ módulo |
| **Risk Scoring**     | Motor de regras (Strategy): score 0–1000, nível e recomendação; 12 regras (identidade, assurance de identidade, PEP, sanção, inidoneidade, mídia negativa, empresa nova, CNAE sensível, quadro societário, cobertura de QSA, cobertura de screening, consistência DDD×UF). GeoIP/device/histórico/score externo são Fase 8 e **não existem** | ✅ módulo |
| **Rule Registry**    | Liga/desliga regra e define vigência sem deploy (kill switch global) | ✅ módulo |
| **Tenant Risk Config** | Override de parâmetro de regra por parceiro (não regra fixa/regulatória) | ✅ módulo |
| **Webhook Delivery** | Entrega assíncrona do resultado com HMAC, retry, idempotência   | ✅ deployable `webhook-api`              |
| **Case Management**  | Revisão manual (EDD) via `POST /v1/assessments/{id}/decision` + módulo `mesa`: filas nomeadas, ações append-only e SLA pausável (V043) | 🟡 domínio pronto; **API fora do filtro de auth** e sem UI |
| **Audit & Compliance** | Trilha imutável, retenção, evidência                           | ⏳ fase 2                                |

## Fase 2 (evolução — plataforma completa / system of record)

| Contexto                | Responsabilidade                                                | Estado |
|-------------------------|-----------------------------------------------------------------|--------|
| **Field Verification**  | Veracidade do cadastro: OTP de telefone/e-mail, nascimento conferido contra bureau; o gate exige verificado, não só preenchido | ✅ módulo `subject.profile` |
| **Ongoing Monitoring**  | Rescreening pelo delta da importação + re-KYC periódico por faixa de risco ([ADR-0019](../adr/0019-politica-de-reavaliacao.md)) | ✅ módulo `rescreening`; monitoramento **transacional** segue aberto (o módulo `behavior` só ingere o fato, nenhuma regra o lê) |
| **Risk State**          | Projeção viva do risco corrente por (subject, tenant) + evento de mudança de nível | ✅ módulo `riskstate` (V041) |
| **Pipeline Monitoring** | Alertas com baseline móvel de 7 dias sobre backlog, volume e taxas | ✅ módulo `monitoring`; canal PagerDuty **nunca exercitado ao vivo** |
| **Behavior Ingestion**  | Acervo append-only de fato comportamental do parceiro | 🟡 módulo `behavior` (V044): ingestão pronta, **API fora do filtro de auth**, zero regras consumindo |
| **Identity Assurance**  | Documentoscopia e biometria com prova de vida — guarda o **resultado**, nunca a imagem ([ADR-0016](../adr/0016-plataforma-completa-modelo-b.md)) | 🟡 ligado ao pipeline (`AssuranceSummary` no `RiskContext`, gate documentoscopia→biometria, reavaliação automática); em `prod` os providers devolvem sempre `UNAVAILABLE` — **nenhum provedor real contratado** |
| **Encryption at Rest**  | KMS/envelope para dado pessoal; pré-requisito da captura         | ⏳ aberto |
| **Regulatory Reporting**| Comunicação COAF/SISCOAF, relatórios ao BACEN                    | ⏳ aberto |
| **Data Subject Rights** | Direitos do titular (LGPD): acesso, correção, anonimização       | ⏳ aberto |

## Regras de fronteira

- Cada deployable é dono do seu schema (`public` na Risk Engine, `webhook` na Webhook API).
- **Entre deployables**, a comunicação é **só por evento** (Kafka) — a Webhook API nunca
  chama a Risk Engine diretamente.
- **Dentro** da Risk Engine (monólito modular), os módulos conversam por chamada de método
  em processo; cada módulo mantém domínio próprio e não cria ciclo com os outros (validado
  por ArchUnit). Ex.: `assessment` depende de `identity`/`screening`/`risk`, nunca o inverso.
- Contratos de evento vivem no módulo `commons` e são versionados.
