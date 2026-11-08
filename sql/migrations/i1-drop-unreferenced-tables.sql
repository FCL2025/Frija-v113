-- ---------------------------------------------------------------------------
-- I1 / I8：刪掉 sql/twmsv113.sql 已經不再建立的 11 張資料表
--
-- I1（2026-09）把這 11 張「程式碼零引用」的表從 dump 裡拿掉了，但那只影響**新**匯入的
-- 資料庫。在那之前匯入的資料庫還留著它們——本機的 twmsv113 就是（I8，2026-11-08 發現）。
-- 這個檔案把舊資料庫補到和 dump 一致。
--
-- 執行方式（資料庫名稱要跟 Settings.ini 的 tms.Url 路徑段一致，預設 twmsv113）：
--
--     mysql -u root -p twmsv113 < sql/migrations/i1-drop-unreferenced-tables.sql
--
-- 和 i3-zero-dates.sql 一樣刻意「不」USE 某個資料庫；忘了指定就會以
-- "No database selected" 失敗，不會誤傷別的 schema。
--
-- 【刪的是哪些、為什麼可以刪】
--
--   auth_server_channel / auth_server_cs / auth_server_login / auth_server_mts
--       RMI 時代各伺服器之間互相認證用的金鑰表。全部伺服器現在跑在同一個 JVM 裡，
--       B3 已刪掉讀它們的 Channel_Key / CashShop_Key / Login_Key。
--       **注意不是 auth_server_channel_ip**——那一張 ServerProperties 開機時一定要查，
--       查不到就 System.exit(0)。本檔一個字都沒碰它。
--   cashshop_limit_sell、eventstats、htsquads、zaksquads、inventorylog、mulungdojo、reports
--       src/、Libs/scripts、panel/ 全部零引用。
--
-- 【跑之前】只有 auth_server_* 四張在舊資料庫裡通常有列（金鑰），其餘多半是空的。
-- 想留底的話先備份：
--
--     mysqldump -u root -p --single-transaction twmsv113 auth_server_channel auth_server_cs --         auth_server_login auth_server_mts cashshop_limit_sell eventstats htsquads zaksquads --         inventorylog mulungdojo reports > i1-tables-backup.sql
--
-- 可以重複執行：每一行都是 DROP TABLE IF EXISTS。
-- ---------------------------------------------------------------------------

DROP TABLE IF EXISTS `auth_server_channel`;
DROP TABLE IF EXISTS `auth_server_cs`;
DROP TABLE IF EXISTS `auth_server_login`;
DROP TABLE IF EXISTS `auth_server_mts`;
DROP TABLE IF EXISTS `cashshop_limit_sell`;
DROP TABLE IF EXISTS `eventstats`;
DROP TABLE IF EXISTS `htsquads`;
DROP TABLE IF EXISTS `zaksquads`;
DROP TABLE IF EXISTS `inventorylog`;
DROP TABLE IF EXISTS `mulungdojo`;
DROP TABLE IF EXISTS `reports`;

-- 驗收：應該回 0，並且 auth_server_channel_ip 仍在（第二個查詢應回 1）。
SELECT COUNT(*) AS i1_tables_left
  FROM `information_schema`.`TABLES`
 WHERE `TABLE_SCHEMA` = DATABASE()
   AND `TABLE_NAME` IN ('auth_server_channel', 'auth_server_cs', 'auth_server_login', 'auth_server_mts',
                        'cashshop_limit_sell', 'eventstats', 'htsquads', 'zaksquads',
                        'inventorylog', 'mulungdojo', 'reports');
SELECT COUNT(*) AS auth_server_channel_ip_present
  FROM `information_schema`.`TABLES`
 WHERE `TABLE_SCHEMA` = DATABASE()
   AND `TABLE_NAME` = 'auth_server_channel_ip';
