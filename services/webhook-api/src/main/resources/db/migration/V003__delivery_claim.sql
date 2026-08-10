-- Posse (lease) de uma entrega, no mesmo padrão da V023/V025 do risk-engine.
--
-- `findDue` varria as entregas vencidas e saía postando, sem lock, sem lease e sem versão. Dois
-- problemas, e o segundo acontece com uma instância só:
--
-- 1. RÉPLICAS: todas leem as mesmas linhas e todas fazem o POST. O cliente recebe o veredito de
--    KYC duplicado. A idempotência por `event_id` não cobre isso — ela impede duas LINHAS, não
--    dois POSTs da mesma linha.
--
-- 2. CORRIDA COM O PRÓPRIO INTAKE: `Delivery.create` nascia com `next_attempt_at = created_at`,
--    ou seja, já vencida. O listener do Kafka gravava a linha e só então fazia o POST (até 10s).
--    Nesse intervalo o scheduler — que roda a cada 5s — encontrava a linha PENDING vencida e
--    postava em paralelo. Entrega dobrada sem nenhuma réplica envolvida.
--
-- A criação agora já nasce reivindicada (`claimed_at = created_at`), porque quem cria é quem
-- tenta em seguida; se essa instância morrer no meio, a lease expira e o poller assume.
ALTER TABLE deliveries ADD COLUMN claimed_at TIMESTAMPTZ;

DROP INDEX IF EXISTS idx_deliveries_status_next_attempt;

CREATE INDEX idx_deliveries_claimable ON deliveries (status, next_attempt_at, claimed_at);
