-- Origem da avaliação: quem pediu por ela.
--
-- Até aqui toda avaliação vinha de um POST do parceiro, então a pergunta não existia. Com o
-- rescreening (Circular 3.978), o motor passa a criar avaliações por conta própria quando uma
-- lista restritiva muda — e uma avaliação que aparece sozinha na fila do analista, sem nada que
-- explique por que existe, é pior que não existir: o analista não sabe se o parceiro reenviou,
-- se houve retrabalho ou se o cliente foi sancionado ontem.
--
-- ONBOARDING  — submetida pelo parceiro (todo o histórico existente).
-- RESCREENING — criada pelo monitoramento contínuo, com a fonte e a versão da lista que a
--               disparou em origin_detail, que é o ponteiro para a entrada nova que a causou.
ALTER TABLE assessments ADD COLUMN origin VARCHAR(20) NOT NULL DEFAULT 'ONBOARDING';

ALTER TABLE assessments ADD COLUMN origin_detail VARCHAR(200);

-- Fila do analista e relatório regulatório perguntam "o que o monitoramento levantou": sem índice
-- isso é varredura completa da tabela que mais cresce no sistema.
CREATE INDEX idx_assessments_origin ON assessments (origin, created_at DESC);
