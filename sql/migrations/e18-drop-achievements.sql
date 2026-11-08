-- ---------------------------------------------------------------------------
-- E18：成就系統整套移除後，刪掉 achievements 表
--
-- 成就系統在遊戲端早就整段被註解掉了（MapleAchievements / MapleAchievement 是空殼，
-- finishAchievement 全樹沒有一個活的呼叫端），從來不會新增任何一列。剩下的只有
-- 「登入時照 accountid 整包載入、存檔時整包刪掉再寫回」這條空轉的路徑——E10 就是
-- 在這條路徑上挖出「換頻後存檔會清空整個帳號的成就」的 bug，I4 #4 也是它
-- （charid 每存一次就被改寫成最後存檔的那隻角色）。
--
-- 2026-11-08 依使用者決定整套移除：欄位、載入／存檔／換頻複製、兩個空殼類別、
-- MapleMonster.killBy 的空 switch 與所有註解掉的呼叫；sql/twmsv113.sql 也不再建立
-- 這張表。這個檔案把舊資料庫補到和 dump 一致。
--
-- 執行方式（資料庫名稱要跟 Settings.ini 的 tms.Url 路徑段一致，預設 twmsv113）：
--
--     mysql -u root -p twmsv113 < sql/migrations/e18-drop-achievements.sql
--
-- 刻意「不」USE 某個資料庫；忘了指定就會以 "No database selected" 失敗。可以重複執行。
--
-- 【跑之前】表裡的列只可能來自更早、成就系統還活著的版本，遊戲已經讀不到它們了。
-- 想留底的話先備份：
--
--     mysqldump -u root -p --single-transaction twmsv113 achievements > achievements-backup.sql
-- ---------------------------------------------------------------------------

DROP TABLE IF EXISTS `achievements`;

-- 驗收：應該回 0。
SELECT COUNT(*) AS achievements_left
  FROM `information_schema`.`TABLES`
 WHERE `TABLE_SCHEMA` = DATABASE()
   AND `TABLE_NAME` = 'achievements';
