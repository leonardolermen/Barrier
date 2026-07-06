# Bounded contexts

Decomposição do domínio de KYC/PLD-FT. Cada contexto é candidato a um microserviço com
o esqueleto de camadas clássicas descrito na [visão geral](overview.md).

## No MVP (fase 1 — motor de risco)

| Contexto             | Responsabilidade                                                 | Fora de escopo agora                    |
|----------------------|------------------------------------------------------------------|-----------------------------------------|
| **Onboarding / Assessment** | Recebe o pedido de avaliação, orquestra o fluxo, agrega decisão | Cadastro persistente do cliente final   |
| **Identity Verification** | Valida CPF/CNPJ, cruza com bureaus (Serpro/Receita, Serasa)  | Guarda de documentos                     |
| **Screening / Watchlists** | Match PEP, sanções (ONU/OFAC/CGU), mídia adversa            | —                                        |
| **Risk Scoring**     | Classificação de risco por abordagem baseada em risco            | —                                        |
| **Case Management**  | EDD, fila de analistas, decisão manual                           | —                                        |
| **Audit & Compliance** | Trilha imutável, retenção, evidência                           | Retenção completa de 10 anos (fase 2)   |

## Fase 2 (evolução — plataforma completa / system of record)

| Contexto                | Responsabilidade                                                |
|-------------------------|-----------------------------------------------------------------|
| **Document Management** | Acervo de documentos, OCR, autenticidade                        |
| **Biometrics**          | Liveness, biometria facial (dado sensível LGPD)                 |
| **Ongoing Monitoring**  | Reavaliação periódica, monitoramento de transações, alertas     |
| **Regulatory Reporting**| Comunicação COAF/SISCOAF, relatórios ao BACEN                   |
| **Data Subject Rights** | Direitos do titular (LGPD): acesso, correção, anonimização      |

## Regras de fronteira

- Nenhum contexto compartilha banco com outro. Cada serviço é dono do seu schema.
- Comunicação entre contextos é **só por evento** (Kafka). Sem chamada síncrona
  serviço-a-serviço no fluxo de avaliação.
- Contratos de evento vivem no módulo `commons` e são versionados.
