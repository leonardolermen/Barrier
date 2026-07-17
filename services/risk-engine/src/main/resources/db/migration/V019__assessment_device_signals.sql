-- Sinais de rede opcionais do intake: IP e identificador de device (fingerprint/deviceId do
-- cliente), usados pelas regras de risco de GeoIP e reuso de device. Nullable: continuam
-- opcionais para não quebrar clientes existentes.
ALTER TABLE assessments ADD COLUMN ip VARCHAR(45);
ALTER TABLE assessments ADD COLUMN device_id VARCHAR(200);
