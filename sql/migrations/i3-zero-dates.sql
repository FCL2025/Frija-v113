-- ---------------------------------------------------------------------------
-- I3：accounts.birthday / accounts.tempban 的零日期 → NULL
--
-- 這是一個「需要資料遷移」的變更，不是單純改 schema 就好。**跑之前先備份**：
--
--     mysqldump -u root -p --single-transaction twmsv113 accounts > accounts-backup.sql
--
-- 執行方式（資料庫名稱要跟 Settings.ini 的 tms.Url 路徑段一致，預設 twmsv113）：
--
--     mysql -u root -p twmsv113 < sql/migrations/i3-zero-dates.sql
--
-- 這個檔案刻意「不」自己 USE 某個資料庫，因為資料庫名稱是可以改的（見 CLAUDE.md
-- 的 Configuration 一節）。忘了在命令列指定的話，第 2 步會直接以
-- "No database selected" 失敗，不會誤傷別的 schema。
--
-- ---------------------------------------------------------------------------
-- 【為什麼要做這件事】
--
-- accounts 這兩個欄位是 2016 年那份 phpMyAdmin dump 帶下來的：
--
--     `birthday` date      NOT NULL DEFAULT '0000-00-00'
--     `tempban`  timestamp NOT NULL DEFAULT '0000-00-00 00:00:00'
--
-- 於是每一個「從來沒被暫時封鎖過」的帳號，tempban 欄位裡都存著一個零日期。
-- MySQL 8 預設的 sql_mode 帶 NO_ZERO_DATE / NO_ZERO_IN_DATE，Connector/J 8 預設的
-- zeroDateTimeBehavior 是 EXCEPTION，兩邊合起來造成三件事：
--
--   1. rs.getTimestamp("tempban") 會丟 "Zero date value prohibited"，
--      rs.getLong("tempban") 會丟 "Unsupported conversion from TIMESTAMP to
--      java.lang.Long"。所以 MapleClient.getTempBanTime 只能改用 getString 讀，
--      再比對 "0000-00-00" 前綴。這一段在**登入路徑**上，壞掉就是全服都進不來。
--   2. MapleClient.getLoginState 只能寫成 SELECT `birthday` + 0 來迴避同一個陷阱。
--   3. accounts 這張表在預設 sql_mode 底下根本 ALTER 不動 —— MySQL 在 ALTER 時會
--      重新檢查**每一個**欄位定義，而這兩個 DEFAULT 本身就是 NO_ZERO_DATE 不收的
--      值，所以連「加一個無關的欄位」都會被擋在 Invalid default value 上。
--
-- 把零日期換成 NULL，這三件事就都不存在了。
--
-- ---------------------------------------------------------------------------
-- 【重要：程式碼側刻意維持「零日期與 NULL 兩者都相容」】
--
-- 搭配這次遷移的 Java 版本同時認得零日期與 SQL NULL：
--
--   * MapleClient.getTempBanTime()  ── rawTempban == null 或 "0000-00-00" 開頭
--                                      都回傳 Instant.EPOCH（＝沒有暫時封鎖）。
--   * MapleClient.getLoginState()   ── `birthday` + 0 對 NULL 算出 NULL，
--                                      rs.getInt 讀 NULL 得到 0，跟零日期一樣。
--   * AccountsApi.accountRow()      ── zeroToNull() / tempban 前綴判斷都吃 null。
--   * AccountsApi.updateAccount()   ── 「解除暫時封鎖」仍然寫 '1970-01-02 00:00:00'
--                                      而**不是** NULL，因為還沒跑遷移的資料庫那個
--                                      欄位是 NOT NULL，寫 NULL 會直接 SQLException。
--
-- 也就是說：**先更新程式碼、後跑遷移**和**先跑遷移、後更新程式碼**都不會壞。
-- 這是刻意的，因為登入路徑不能賭。等到你確認所有資料庫都跑過這個檔案之後，才可以
-- 回頭把那幾個 workaround 拿掉（每一處原始碼上都有註解標明可以拿掉的時機）。
--
-- ---------------------------------------------------------------------------
-- 【風險】
--
--   * ALTER TABLE accounts 會重建整張表（ALGORITHM=COPY 等級的成本）。帳號數量大的
--     話會鎖住這張表一段時間，請在伺服器關機時做。
--   * 遊戲執行中絕對不要跑：ServerProperties 開機時查 auth_server_channel_ip、
--     登入路徑每次都讀 accounts，重建表的期間這些查詢會被卡住。
--   * 第 2 步之前 tempban 是 NOT NULL，所以「先 UPDATE 成 NULL 再 ALTER」是做不到的，
--     順序一定是「先 ALTER 放寬成可為 NULL，再 UPDATE 把零日期換掉」。
--   * 這個檔案可以重複執行（idempotent）：第二次跑時第 3、4 步會是 0 rows affected。
-- ---------------------------------------------------------------------------

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 第 1 步：放寬本連線的 sql_mode，並記下原值
--
-- 一定要做。理由有兩個，而且兩個都會讓整份檔案在預設 sql_mode 下失敗：
--   (a) 第 2 步的 ALTER 會重新檢查 birthday / tempban 現有的零日期 DEFAULT，
--       NO_ZERO_DATE 會以 "Invalid default value for 'birthday'" 擋下來。
--   (b) 第 3 步 WHERE 子句裡的 '0000-00-00' 字面值，在 STRICT_TRANS_TABLES +
--       NO_ZERO_DATE 底下會直接丟 ERROR 1525 Incorrect TIMESTAMP value。
--
-- 'NO_AUTO_VALUE_ON_ZERO' 就是 sql/twmsv113.sql 開頭那一行用的值（它同樣是為了
-- 讓這兩個欄位定義能被接受）。這裡只影響**這一條連線**，不動全域設定。
-- ---------------------------------------------------------------------------

SET @i3_old_sql_mode = @@SESSION.sql_mode;
SET SESSION sql_mode = 'NO_AUTO_VALUE_ON_ZERO';

-- 先看一眼會影響幾列（純唯讀，可以先單獨跑這一段確認範圍）。
SELECT COUNT(*)                                        AS accounts_total,
       SUM(`birthday` = '0000-00-00')                  AS birthday_zero,
       SUM(`tempban`  = '0000-00-00 00:00:00')         AS tempban_zero,
       SUM(`tempban` <> '0000-00-00 00:00:00'
           AND `tempban` < '1970-01-03 00:00:00')      AS tempban_1970_sentinel
  FROM `accounts`;

-- ---------------------------------------------------------------------------
-- 第 2 步：把兩個欄位改成可為 NULL、預設 NULL
--
-- 這一步只改欄位定義，不動任何一列的值 —— 重建之後零日期還在原位，只是欄位從此
-- 允許 NULL。順序不能顛倒（見上面的風險說明）。
--
-- 注意 timestamp 要寫明 `NULL DEFAULT NULL`：在 explicit_defaults_for_timestamp
-- 關閉的舊設定下，一個沒寫 NULL 屬性的 TIMESTAMP 欄位會被自動補上
-- NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP，那會讓
-- tempban 每次 UPDATE accounts 都自己跳成現在時間，等於全服被暫時封鎖。
-- ---------------------------------------------------------------------------

ALTER TABLE `accounts`
  MODIFY `birthday` date      NULL DEFAULT NULL,
  MODIFY `tempban`  timestamp NULL DEFAULT NULL;

-- ---------------------------------------------------------------------------
-- 第 3 步：把現有的零日期換成 NULL
-- ---------------------------------------------------------------------------

UPDATE `accounts` SET `birthday` = NULL WHERE `birthday` = '0000-00-00';
UPDATE `accounts` SET `tempban`  = NULL WHERE `tempban`  = '0000-00-00 00:00:00';

-- ---------------------------------------------------------------------------
-- 第 4 步（選用，但建議）：把面板寫的 '1970-01-02 00:00:00' 也正規化成 NULL
--
-- 管理面板的「解除暫時封鎖」以前只能寫這個哨兵值，因為欄位是 NOT NULL 而
-- NO_ZERO_DATE 不收零日期。語意上它跟 NULL 完全一樣（getTempBanTime 會判定它已經
-- 過期而回傳 Instant.EPOCH），所以換成 NULL 不會改變任何行為，只是讓資料乾淨。
--
-- 不想動可以把這一段整個註解掉：讀取端（MapleClient 與 AccountsApi）仍然認得
-- 1970-01-0x，那一段相容碼不會因為這次遷移拿掉。
--
-- 範圍取 < '1970-01-03 00:00:00' 而不是等於某個字面值，是因為 TIMESTAMP 存的是
-- UTC、讀的是連線時區，寫進去和讀出來的字串會差一個時區偏移量。TIMESTAMP 的合法
-- 下限是 1970-01-01 00:00:01 UTC，所以這個範圍不可能誤傷真正的暫時封鎖。
-- 第 3 步之後零日期已經是 NULL，而 NULL 不會被 < 比較選中。
-- ---------------------------------------------------------------------------

UPDATE `accounts` SET `tempban` = NULL WHERE `tempban` < '1970-01-03 00:00:00';

-- ---------------------------------------------------------------------------
-- 第 5 步：還原 sql_mode，並驗收
-- ---------------------------------------------------------------------------

SET SESSION sql_mode = @i3_old_sql_mode;

-- 欄位定義：兩列都應該是 IS_NULLABLE = YES、COLUMN_DEFAULT = NULL、EXTRA 空白。
-- EXTRA 若出現 on update CURRENT_TIMESTAMP 就是第 2 步寫錯了，務必檢查。
SELECT `COLUMN_NAME`, `COLUMN_TYPE`, `IS_NULLABLE`, `COLUMN_DEFAULT`, `EXTRA`
  FROM `information_schema`.`COLUMNS`
 WHERE `TABLE_SCHEMA` = DATABASE()
   AND `TABLE_NAME`   = 'accounts'
   AND `COLUMN_NAME` IN ('birthday', 'tempban');

-- 資料：zero_left 必須是 0（或整張表是空的時候是 NULL）。
--
-- 這裡刻意用 CAST(... AS CHAR) LIKE 而不是 = '0000-00-00'：sql_mode 在上一行已經
-- 還原成預設值，而預設值帶 NO_ZERO_DATE，一個零日期**字面值**在 WHERE／SELECT 裡
-- 就會直接丟 ERROR 1525 Incorrect DATE value（不管有沒有列符合）。CAST 成字串再比
-- 對則兩種 sql_mode 下都能跑。
SELECT COUNT(*)                                          AS accounts_total,
       SUM(`birthday` IS NULL)                           AS birthday_null,
       SUM(`tempban`  IS NULL)                           AS tempban_null,
       SUM(CAST(`birthday` AS CHAR) LIKE '0000-00-00%')
         + SUM(CAST(`tempban` AS CHAR) LIKE '0000-00-00%')
                                                         AS zero_left
  FROM `accounts`;

-- 最後一道確認：這兩句在遷移前會以 "Invalid default value for 'birthday'" 失敗
-- （MySQL 在複製欄位定義時會重新驗證每一個 DEFAULT），遷移後會成功。它完全不碰
-- accounts 本身 —— 只是照它的欄位定義開一張暫時的空表再丟掉。
--
-- 換句話說：跑到這裡沒有報錯，就代表 CLAUDE.md 裡「accounts 在預設 sql_mode 下
-- ALTER 不動」那個限制已經解除了。
CREATE TABLE `i3_zero_dates_probe` LIKE `accounts`;
DROP TABLE `i3_zero_dates_probe`;

-- ---------------------------------------------------------------------------
-- 【回復（rollback）】
--
-- 只有在你需要把資料庫退回舊程式碼也能用的狀態時才跑。實務上**不需要**：本次的
-- Java 兩種格式都認得，所以遷移後的資料庫跑舊 jar 也不會壞（舊 jar 的
-- getString(...) == null 檢查同樣存在）。真的要退，整段取消註解後執行：
--
--   SET SESSION sql_mode = 'NO_AUTO_VALUE_ON_ZERO';
--   UPDATE `accounts` SET `birthday` = '0000-00-00'          WHERE `birthday` IS NULL;
--   UPDATE `accounts` SET `tempban`  = '0000-00-00 00:00:00' WHERE `tempban`  IS NULL;
--   ALTER TABLE `accounts`
--     MODIFY `birthday` date      NOT NULL DEFAULT '0000-00-00',
--     MODIFY `tempban`  timestamp NOT NULL DEFAULT '0000-00-00 00:00:00';
-- ---------------------------------------------------------------------------
