-- Lock de job singleton entre réplicas.
--
-- Cinco @Scheduled do sistema devem rodar UMA vez globalmente, e rodavam em cada pod: a
-- importação de watchlist baixava o ZIP da OFAC/CGU/ONU cinco vezes e podia disparar cinco
-- avalanches de rescreening (cada avaliação com consulta paga de bureau); o re-KYC periódico
-- transformava o teto de 200/execução em 1000/noite, anulando o controle de custo que o teto
-- existe para dar.
--
-- É LEASE, não advisory lock do Postgres. O `pg_try_advisory_lock` é ligado à *sessão*, então
-- exigiria fixar a conexão do Hikari durante o job inteiro; e a variante `_xact_` só se solta no
-- fim da transação, o que manteria uma transação aberta pelos minutos de download de uma
-- importação — idle-in-transaction segurando vacuum. O lease é o mesmo padrão que a outbox
-- (V025) e o claim de avaliações já usam aqui, e tem a propriedade que mais importa às 3 da
-- manhã: pod que morre no meio do job não deixa lock preso, o lease simplesmente vence.
CREATE TABLE job_locks (
    job_name     VARCHAR(100) PRIMARY KEY,
    locked_until TIMESTAMPTZ  NOT NULL,
    locked_by    VARCHAR(200) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE job_locks IS
    'Lease de execução de job singleton entre réplicas; ver SingletonJobLock.';
COMMENT ON COLUMN job_locks.locked_until IS
    'Instante em que o lease vence e o job volta a ser reivindicável por qualquer réplica.';
COMMENT ON COLUMN job_locks.locked_by IS
    'Quem tomou o lease (hostname do pod). Diagnóstico: responde "qual réplica está rodando isto".';
