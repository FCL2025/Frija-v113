-- ---------------------------------------------------------------------------
-- E16：刪掉問卷（Poll）功能留下的 game_poll_reply 表
--
-- 問卷是一個被 ServerConstants.PollEnabled = false 關掉的半成品：伺服器從不發問卷，
-- 只有 ADMIN 的 !test 會把寫死的英文 "Are you mudkiz?" 送給自己，客戶端回答時
-- inGame_Poll 也什麼都不做。2026-11-08 依使用者決定整組移除（常數、處理器、封包、
-- !test、三個 opcode 與 ini 行），sql/twmsv113.sql 也不再建立這張表。
-- 這個檔案把舊資料庫補到和 dump 一致。
--
-- 執行方式（資料庫名稱要跟 Settings.ini 的 tms.Url 路徑段一致，預設 twmsv113）：
--
--     mysql -u root -p twmsv113 < sql/migrations/e16-drop-game-poll-reply.sql
--
-- 刻意「不」USE 某個資料庫；忘了指定就會以 "No database selected" 失敗。
-- 可以重複執行。表裡若有列（只有問卷真的開過才會有），想留底請先 mysqldump。
-- ---------------------------------------------------------------------------

DROP TABLE IF EXISTS `game_poll_reply`;

-- 驗收：應該回 0。
SELECT COUNT(*) AS game_poll_reply_left
  FROM `information_schema`.`TABLES`
 WHERE `TABLE_SCHEMA` = DATABASE()
   AND `TABLE_NAME` = 'game_poll_reply';
