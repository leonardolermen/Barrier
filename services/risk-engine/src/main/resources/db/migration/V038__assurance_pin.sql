-- Fluxo assíncrono por PIN (Datavalid/Serpro): criar o PIN não produz desfecho — o cidadão
-- captura a selfie depois, no app gov.br — então o check nasce PENDING e o desfecho final chega
-- mais tarde, trazido por um poller (mesmo padrão de lease do outbox, V000 em commons).
--
-- `pin` é credencial de sessão do cidadão, não identificador de auditoria: nunca aparece em log
-- nem em resposta de listagem (ver AssuranceCheck.pin). `pin_expires_at` é até quando o poller
-- deve continuar tentando; depois disso ele desiste e marca UNAVAILABLE. `claimed_at` é a lease
-- do poller — mesmo mecanismo de `outbox.claimed_at`, para réplicas concorrentes reivindicarem
-- conjuntos disjuntos com `FOR UPDATE SKIP LOCKED` sem I/O externo dentro do lock.
--
-- VARCHAR(16), não um número redondo arbitrário: sondagem ao vivo contra o ambiente de
-- demonstração do Serpro confirmou que o PIN tem exatamente 9 caracteres (HTTP 400,
-- "pin : valor deve possuir exatamente 9 caracteres" ao tentar um PIN de 6). 9 fixos bastariam,
-- mas VARCHAR(16) dá folga sem comprometer a validação — o formato exato não é documentado, só
-- observado, e não vale travar a coluna no primeiro valor visto.
ALTER TABLE identity_assurance_checks
    ADD COLUMN pin             VARCHAR(16),
    ADD COLUMN pin_expires_at  TIMESTAMPTZ,
    ADD COLUMN claimed_at      TIMESTAMPTZ;

CREATE INDEX idx_assurance_pending_biometric
    ON identity_assurance_checks (kind, outcome, claimed_at)
    WHERE outcome = 'PENDING';
