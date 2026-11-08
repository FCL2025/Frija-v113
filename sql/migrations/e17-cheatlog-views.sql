-- ---------------------------------------------------------------------------
-- E17：修好 cheatlog 的兩個檢視表
--
-- E17（2026-11-08）讓 CheatingOffensePersister 真的把違規寫進 cheatlog —— 以前它
-- 收集完就丟掉，所以這兩個檢視表從來沒有資料可看，也就沒人發現它們是錯的：
--
--   * 兩個都用 cl.id = c.id 去接角色表。cl.id 是 cheatlog 自己的流水號，
--     應該是 cl.characterid。舊寫法會把第 N 筆違規算到 id 為 N 的角色頭上。
--   * readable_last_hour_cheatlog 用 group by cl.id，每一筆違規自成一組，
--     numrepos 根本沒有加總。改成依角色（a.id, c.id）加總，這才是它的用意；
--     同時也符合 MySQL 8 預設的 ONLY_FULL_GROUP_BY。
--
-- sql/twmsv113.sql 已同步修改；這個檔案給已經匯入過的資料庫用。
--
-- 執行方式（資料庫名稱要跟 Settings.ini 的 tms.Url 路徑段一致，預設 twmsv113）：
--
--     mysql -u root -p twmsv113 < sql/migrations/e17-cheatlog-views.sql
--
-- 刻意「不」USE 某個資料庫。可以重複執行（CREATE OR REPLACE）。
-- 沒寫 DEFINER，所以檢視表的擁有者會是執行這個檔案的帳號。
-- ---------------------------------------------------------------------------

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `readable_cheatlog` AS
  select `a`.`name` AS `accountname`, `a`.`id` AS `accountid`, `c`.`name` AS `name`, `c`.`id` AS `characterid`,
         `cl`.`offense` AS `offense`, `cl`.`count` AS `count`, `cl`.`lastoffensetime` AS `lastoffensetime`, `cl`.`param` AS `param`
    from ((`cheatlog` `cl` join `characters` `c`) join `accounts` `a`)
   where ((`cl`.`characterid` = `c`.`id`) and (`a`.`id` = `c`.`accountid`) and (`a`.`banned` = 0));

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `readable_last_hour_cheatlog` AS
  select `a`.`name` AS `accountname`, `a`.`id` AS `accountid`, `c`.`name` AS `name`, `c`.`id` AS `characterid`,
         sum(`cl`.`count`) AS `numrepos`
    from ((`cheatlog` `cl` join `characters` `c`) join `accounts` `a`)
   where ((`cl`.`characterid` = `c`.`id`) and (`a`.`id` = `c`.`accountid`)
          and (timestampdiff(HOUR, `cl`.`lastoffensetime`, now()) < 1) and (`a`.`banned` = 0))
   group by `a`.`id`, `c`.`id`
   order by sum(`cl`.`count`) desc;

-- 驗收：兩個查詢都要能執行（空表時回 0 列，不應該報 ONLY_FULL_GROUP_BY 錯誤）。
SELECT COUNT(*) AS readable_cheatlog_rows FROM `readable_cheatlog`;
SELECT COUNT(*) AS readable_last_hour_rows FROM `readable_last_hour_cheatlog`;
