-- The two address_utxo indexes this node's own reads need.
--
-- Yaci Store 0.1.7's library migration (V0_200_1__init.sql) created them; 2.0.7 edited that migration
-- in place and no longer does -- they now live only in Yaci's dbutils index.yml, applied by
-- DBIndexProcessor, which this node switches off. Without them, every credential read
-- (UtxoRepository.findUnspentByOwnerPaymentCredential, ten call sites) and the address read
-- (findUnspentByOwnerAddr, IndexFirstUtxoSupplier) scans address_utxo in full.
--
-- Yaci's own index names, and IF NOT EXISTS, on purpose: a database that already has them -- from
-- DBIndexProcessor or created by an operator by hand -- is left alone rather than failing at boot.
-- Only these two: the stake-address, stake-credential and epoch indexes 0.1.7 also created serve no
-- read of this node's.
CREATE INDEX IF NOT EXISTS idx_address_utxo_owner_paykey_hash ON address_utxo(owner_payment_credential);
CREATE INDEX IF NOT EXISTS idx_address_utxo_owner_addr ON address_utxo(owner_addr);
