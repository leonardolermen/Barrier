-- Decisão manual (EM_REVISAO → APROVADO/REPROVADO), com trilha de quem/por quê/quando.
ALTER TABLE assessments ADD COLUMN reviewed_by   VARCHAR(200);
ALTER TABLE assessments ADD COLUMN review_reason VARCHAR(500);
ALTER TABLE assessments ADD COLUMN reviewed_at   TIMESTAMPTZ;
