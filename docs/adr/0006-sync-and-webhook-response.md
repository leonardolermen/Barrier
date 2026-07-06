# ADR-0006: Retorno síncrono + webhook

- **Status:** Aceito
- **Data:** 2026-07-04

## Contexto

O cliente precisa do resultado da avaliação de risco. O processamento envolve integrações
lentas (bureaus, watchlists) e, em alguns casos, análise manual (case management), que pode
levar minutos ou horas. Uma resposta puramente síncrona travaria a conexão do cliente.

## Decisão

Vamos oferecer **ambos**:

1. **Síncrono na borda** — `POST /assessments` responde imediatamente `202 { id, status:
   em_analise }`.
2. **Webhook assíncrono** — quando a avaliação conclui, um webhook dispatcher faz callback
   no endpoint do cliente, com **retry** e **assinatura HMAC**.
3. **Consulta** — `GET /assessments/{id}` permite polling a qualquer momento.

## Alternativas consideradas

- **Somente síncrono** — inviável com bureaus lentos e análise manual. Descartado.
- **Somente webhook** — obriga todo cliente a expor endpoint e complica testes/integração
  simples. Descartado como opção única.

## Consequências

- **Positivas:** borda não bloqueia; flexível para o cliente (webhook e/ou polling);
  resiliente a etapas longas.
- **Negativas / custos:** cliente precisa lidar com assíncrono; dispatcher precisa de retry,
  idempotência de entrega e segurança (assinatura).
- **Mitigações:** assinatura HMAC + `eventId` para o cliente desduplicar; política de retry
  com backoff; documentação clara do contrato de webhook.
