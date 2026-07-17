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
| **Risk Scoring**     | Motor de regras (Strategy): score 0–1000, nível e recomendação; ~15 regras (identidade, PEP, sanção, mídia negativa, empresa nova, CNAE sensível, quadro societário, consistência, GeoIP, device, telefone, email, histórico, score externo) | ✅ módulo |
| **Rule Registry**    | Liga/desliga regra e define vigência sem deploy (kill switch global) | ✅ módulo |
| **Tenant Risk Config** | Override de parâmetro de regra por parceiro (não regra fixa/regulatória) | ✅ módulo |
| **Webhook Delivery** | Entrega assíncrona do resultado com HMAC, retry, idempotência   | ✅ deployable `webhook-api`              |
| **Case Management**  | Revisão manual (EDD) via `POST /v1/assessments/{id}/decision`; fila dedicada de analistas ainda não existe | 🟡 parcial |
| **Audit & Compliance** | Trilha imutável, retenção, evidência                           | ⏳ fase 2                                |

## Fase 2 (evolução — plataforma completa / system of record)

| Contexto                | Responsabilidade                                                |
|-------------------------|-----------------------------------------------------------------|
| **Document Management** | Acervo de documentos, OCR, autenticidade                        |
| **Biometrics**          | Liveness, biometria facial (dado sensível LGPD)                 |
| **Ongoing Monitoring**  | Reavaliação periódica, monitoramento de transações, alertas     |
| **Regulatory Reporting**| Comunicação COAF/SISCOAF, relatórios ao BACEN                   |
| **Data Subject Rights** | Direitos do titular (LGPD): acesso, correção, anonimização      |

## Regras de fronteira

- Cada deployable é dono do seu schema (`public` na Risk Engine, `webhook` na Webhook API).
- **Entre deployables**, a comunicação é **só por evento** (Kafka) — a Webhook API nunca
  chama a Risk Engine diretamente.
- **Dentro** da Risk Engine (monólito modular), os módulos conversam por chamada de método
  em processo; cada módulo mantém domínio próprio e não cria ciclo com os outros (validado
  por ArchUnit). Ex.: `assessment` depende de `identity`/`screening`/`risk`, nunca o inverso.
- Contratos de evento vivem no módulo `commons` e são versionados.
