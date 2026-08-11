-- Segredo HMAC por tenant, com janela de rotação.
--
-- `barrier.webhook.secret` era um só para todos: quem conhecesse o segredo — um parceiro, um
-- ex-integrador, um vazamento de config — conseguia forjar um callback de KYC válido para
-- QUALQUER outro tenant, inclusive um "APROVADO". Endereçar a entrega por tenant (V004) resolveu
-- para onde o resultado vai; isto resolve quem consegue provar que ele veio do Barrier.
--
-- `previous_secret` + `previous_secret_until` existem para a rotação não ter downtime: durante a
-- janela, a entrega leva também a assinatura pelo segredo anterior (header
-- `X-Barrier-Signature-Previous`), então o cliente troca a chave quando puder, sem combinar um
-- instante exato com a gente. Sem isso, rotacionar é escolher entre reusar segredo comprometido e
-- derrubar a verificação do cliente.
--
-- O segredo fica em texto na coluna porque assinar exige o valor (diferente das API keys dos
-- tenants, que são guardadas como hash — lá basta comparar). Criptografia em repouso é item da
-- Fase 6 e vale para esta coluna.
--
-- Nulo é permitido: as linhas que já existiam continuam caindo no segredo global, que é o
-- comportamento de desenvolvimento. Em produção o destino global já é proibido, então todo tenant
-- entregue tem registro — e o registro novo nasce com segredo próprio.
ALTER TABLE webhook_endpoints ADD COLUMN secret VARCHAR(120);
ALTER TABLE webhook_endpoints ADD COLUMN previous_secret VARCHAR(120);
ALTER TABLE webhook_endpoints ADD COLUMN previous_secret_until TIMESTAMPTZ;
