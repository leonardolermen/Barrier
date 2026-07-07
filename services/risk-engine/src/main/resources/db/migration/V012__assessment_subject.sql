-- Nullable: avaliações criadas antes desta feature não têm subject (não há backfill).
ALTER TABLE assessments ADD COLUMN subject_id UUID;

ALTER TABLE assessments
    ADD CONSTRAINT fk_assessments_subject FOREIGN KEY (subject_id) REFERENCES subjects (id);

CREATE INDEX idx_assessments_subject ON assessments (subject_id);
