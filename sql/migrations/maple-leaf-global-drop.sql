-- ---------------------------------------------------------------------------
-- 楓葉（4001126）改為全域掉落
--
-- 2026-11 把 scripts/ 併進 Libs/scripts 之後，有兩支 NPC 用楓葉當材料：
--
--   * 9010000 楓之谷管理員 - 楓葉換楓葉武器／盾牌／楓葉頭盔／週年慶卷軸，
--     每樣要 100 ～ 2000 片
--   * 9330012 - 製作 35／42／64 等楓葉武器，每把要 1000 ～ 10000 片
--
-- 但 drop_data 裡沒有任何怪物會掉楓葉，這兩項兌換等於永遠用不到。這裡在
-- drop_data_global 加一筆：所有大陸（continent = -1）、所有怪物、每次 1 片。
--
-- chance 的分母是 1,000,000（MapleMap 用 Randomizer.nextInt(999999) < chance 判斷），
-- 而且全域掉落「不」乘伺服器的掉落倍率。100000 = 每隻怪 10%。要調整的話，
-- 改這一筆的 chance 之後在遊戲裡 !reloaddrops，或直接在管理面板的全域掉落頁面改
-- （面板存檔會自動重新載入），都不必重開伺服器。
--
-- sql/twmsv113.sql 已同步加入同一筆；這個檔案給已經匯入過的資料庫用。
--
-- 執行方式（資料庫名稱要跟 Settings.ini 的 tms.Url 路徑段一致，預設 twmsv113）：
--
--     mysql -u root -p twmsv113 < sql/migrations/maple-leaf-global-drop.sql
--
-- 刻意「不」USE 某個資料庫。可以重複執行：已經有楓葉這一筆（不論 chance 被改成
-- 多少）就什麼都不做，不會蓋掉你調過的機率。
-- ---------------------------------------------------------------------------

SET NAMES utf8mb4;

INSERT INTO `drop_data_global`
       (`continent`, `dropType`, `itemid`, `minimum_quantity`, `maximum_quantity`, `questid`, `chance`, `comments`)
SELECT -1, 0, 4001126, 1, 1, 0, 100000, '楓葉：NPC 9010000、9330012 兌換用'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `drop_data_global` WHERE `itemid` = 4001126);

-- 驗收：應該剛好一筆
SELECT `id`, `itemid`, `chance`, `comments` FROM `drop_data_global` WHERE `itemid` = 4001126;
