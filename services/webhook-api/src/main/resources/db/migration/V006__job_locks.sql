-- Lease de job singleton, no schema da Webhook API.
--
-- Mesma estrutura da V045 da Risk Engine, e a duplicação é DELIBERADA: cada deployable é dono do
-- seu schema (regra de fronteira em docs/architecture/domain-contexts.md), e uma tabela
-- compartilhada entre os dois criaria acoplamento de banco onde hoje só existe acoplamento por
-- evento. O código do lease é único (`SingletonJobLock`, no commons); o que se repete é a tabela,
-- porque o escopo do lock também é por serviço: as 5 réplicas da webhook-api coordenam entre si,
-- não com as da risk-engine.
--
-- Motivação: `DeliveryReconciliationJob` relê o tópico a cada 15 minutos com um consumidor avulso
-- para criar entrega para toda decisão que ficou sem uma. Com 5 réplicas, eram 5 consumidores
-- varrendo a mesma janela de 6 horas do tópico simultaneamente — trabalho multiplicado por cinco
-- sobre o broker e sobre o banco, no mesmo instante.
CREATE TABLE job_locks (
    job_name     VARCHAR(100) PRIMARY KEY,
    locked_until TIMESTAMPTZ  NOT NULL,
    locked_by    VARCHAR(200) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE job_locks IS
    'Lease de execução de job singleton entre réplicas da Webhook API; ver SingletonJobLock.';
