# Bounded contexts

Decomposição do domínio de KYC/PLD-FT. Cada contexto é candidato a um microserviço com
o esqueleto de camadas clássicas descrito na [visão geral](overview.md).

## No MVP (fase 1 — motor de risco)

| Contexto             | Responsabilidade                                                 | Estado                                  |
|----------------------|------------------------------------------------------------------|-----------------------------------------|
| **Assessment**       | Recebe o pedido, orquestra o fluxo, agrega a decisão             | ✅ módulo da Risk Engine                 |
| **Identity Verification** | Valida CPF/CNPJ, cruza com bureaus (Serpro/Serasa)          | ✅ módulo (bureau real ainda stub)       |
| **Screening / Watchlists** | Match PEP, sanções (ONU/OFAC/CGU)                          | ✅ módulo (listas reais ainda stub)      |
| **Risk Scoring**     | Motor de regras: score 0–1000, nível e recomendação             | ✅ módulo (regras: sanção/PEP/identidade)|
| **Webhook Delivery** | Entrega assíncrona do resultado com HMAC, retry, idempotência   | ✅ deployable `webhook-api`              |
| **Case Management**  | EDD, fila de analistas, decisão manual                           | ⏳ fase 2                                |
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
