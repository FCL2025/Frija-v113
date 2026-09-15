# MapleStory-v113-server
台服 113 伺服器

原始來源為網路


已修正

多人遊戲

廣播

商城

多數技能

修正部分計時器殘留問題


## 建置與執行

需要 **JDK 26**。原始碼是 UTF-8 且含有中文識別字，`javac` 必須帶 `-encoding UTF-8`。

整個專案只有一個 `Launcher.bat`，它會在啟動前自動建置：只要 `src\`、`JarLib\` 或
`manifest.mf` 裡有任何檔案比 `dist\TMS113.jar` 新，就先編譯打包再啟動；沒有改動就直接啟動。
工作目錄必須是專案根目錄。

```
Launcher.bat            需要的話先建置，然後啟動伺服器
Launcher.bat run        不建置，直接啟動
Launcher.bat build      強制重新建置，建置完就結束（不啟動伺服器）
Launcher.bat lint       只編譯，依類別印出 -Xlint:all 的警告統計後結束
Launcher.bat test       編譯 src\ 與 test\，跑 JUnit 5 測試
Launcher.bat cashshop   改跑 tools.wztosql.CashShopDumper
```

建置結束會印一行 `[build] N warnings`。`test` 用的是 `JarLib\test\` 底下的
`junit-platform-console-standalone` 單一 jar —— 它只在測試 classpath 上，
不在 `manifest.mf`、也不在 `dist\lib\`，伺服器執行時碰不到它。測試原始碼在
`test\`，目前是 216 個測試（0 個 `@Disabled`），全部是零 I/O 的純函式：加解密往返、
little-endian 讀寫往返（含緩衝區成長、seek 邊界、讀過封包尾端）、`StringUtil` 的
BIG5 位元組寬度補位、`LoginCrypto`、面板的 `IniFile` 與 `Json`、opcode 載入、
`GameConstants` / `ItemConstants` / `JobConstants` / `MapConstants` 的 id 運算、
`KoreanDateUtil`。

專案裡沒有 ant 或 IDE 的專案檔：NetBeans 留下的 `build.xml` 只是個殼，它 `<import>` 的
`nbproject\build-impl.xml` 從未納入版控、從來跑不起來，已經移除。建置流程就是 `Launcher.bat` 本身。

執行前需要：

- `Settings.ini`：這個檔案不進版控（裡面有資料庫帳密）。從範本複製一份再填：

  ```
  copy Settings.ini.example Settings.ini
  ```

- MySQL：`mysql -u root -p < sql/twmsv113.sql`（這個檔案會自己建立並選用 `twmsv113` 資料庫），
  再把帳密填進 `Settings.ini` 的 `tms.User` / `tms.Pass`

  帳密填錯時伺服器不會報錯：`tms.Url` 裡的 `maxReconnects=999` 會讓它安靜重試約 30 分鐘，
  期間沒有任何輸出。啟動後卡住沒反應，先檢查這兩行。
  
  資料庫名稱沒有獨立的設定項，它是 `tms.Url` 這條 JDBC 網址裡 `3306/` 後面那一段。
  要改名字就改那一行，並把 `sql/twmsv113.sql` 開頭的 `CREATE DATABASE` / `USE` 改成一樣。
- `wz/`（XML 解出來的 WZ 資料，未納入版控）

`dist\lib\` 內的相依：MINA 2.2、Nashorn（JDK 15 起已從 JDK 移除，改由 `nashorn-core` 提供）、
Nashorn 需要的 asm、MySQL Connector/J、slf4j 2.0。

日誌走 `java.lang.System.Logger`（JDK 內建，不加 jar），後端是 `java.util.logging`，
而 slf4j-jdk14 把 MINA 的 slf4j 也導到同一組 handler。設定在 `Libs/logging.properties`，
由 `Launcher.bat` 以 `-Djava.util.logging.config.file=` 帶入。慣例寫在 `src/tools/Logging.java`
的 javadoc 裡。注意：那個參數指向不存在的檔案時不會退回 JDK 預設、也不會報錯，
console 會完全安靜——`Launcher.bat` 因此會先檢查並警告。

## 啟動時間

啟動過程中每個階段都會印出花費的毫秒數，最後一行 `Loaded Complete :::` 會標上完成時間與總秒數：

```
[啟動] 資料庫連線             741 ms
[啟動] 世界與計時器            29 ms
[啟動] 遊戲資料檔            1253 ms
[啟動] 排行榜與家族           142 ms
[啟動] 購物商城道具           251 ms
[啟動] 登入伺服器              58 ms
[啟動] 頻道伺服器            3592 ms
[啟動] 購物商城伺服器          35 ms
Loaded Complete ::: 啟動完成於 2026-09-11 14:46:40，共花費 6.18 秒
```

原本啟動要花約 29 秒，其中 20 秒耗在 `CashItemFactory`：它對 `Commodity.img` 裡約 7800 個道具
各做一次 `getPackageItems()`，而每一次都把整份 `CashPackage.img` 重新解析成 DOM；同時還對每個
SN 各發一次 `SELECT … WHERE serial = ?`，等於為一張 6653 列的表跑了 7800 次查詢。現在兩者都改成
只讀一次。頻道那一段本來是剩下最大的一塊——每個頻道都要為 `tms.Events` 裡約 100 支腳本各開一個 Nashorn
引擎並編譯。先改成平行編譯，2026-09 又改成「全樹共用一個引擎、每支腳本只編譯一次、
每段對話只建自己的 Global」，頻道啟動因此由 3.7 秒降到約 2.0 秒，整體啟動約 5.6 秒。

總時間會隨機器核心數、`tms.Count`（頻道數）與 `tms.Events` 的長度而變。

## 管理面板

伺服器啟動後，用瀏覽器開 <http://127.0.0.1:8080/>。登入 Token 印在啟動輸出裡：

```
[面板] 管理面板：http://127.0.0.1:8080/  （8 條處理執行緒）
[面板] 登入 Token：kGBVMZDI_2fwO3C8S3WJLya1YAObcCDO
```

面板活在遊戲伺服器的同一個 JVM 裡（`src/server/panel/`，用 JDK 內建的 `com.sun.net.httpserver`，
沒有新增任何 jar），隨 `server.Start` 一起啟動、隨 `ShutdownServer` 一起關閉；**伺服器沒開就沒有面板，
面板也無法重啟伺服器**。目前有：

| 分頁 | 內容 |
|---|---|
| 儀表板 | 線上人數、各頻道人數、登入連線數、資料庫連線池、JVM 記憶體與執行緒、啟動耗時 |
| 頻道 / 線上玩家 / 日誌 | `ChannelServer` 狀態與倍率、各頻道的線上角色（含帳號與 IP）、`Logs/*.rtf` 的 tail |
| 即時操作 | 改倍率（四種倍率同時套用到每個頻道並寫回 ini）、廣播、頻道公告、七組 `!reload…`、優雅關機 |
| 設定檔 | `Settings.ini` 逐項檢視與修改，保留 BOM、CRLF、註解與對齊；每項標明「即時 / 記憶體 / 需重啟」 |
| 帳號與角色 | `accounts` / `characters` 的查詢與編輯（密碼欄位不會離開伺服器）、踢人、IP / MAC 封鎖清單；線上角色拒絕寫入，因為記憶體才是真相 |
| 掉寶與商店 | `drop_data` / `drop_data_global` / `shops` + `shopitems` / `cashshop_modified_items` 的編輯器，道具、怪物、NPC 都用名稱搜尋；每次寫入自動清掉對應的快取 |
| 腳本 | `Libs/scripts/` 的 2456 支腳本：檔案瀏覽、全文搜尋、CodeMirror 編輯；以 UTF-8 編輯、存檔轉回 BIG5，無法以 BIG5 表示的字元會擋下並指出行列 |

設定項（都在 `Settings.ini`，見 `Settings.ini.example` 的說明）：`tms.PanelPort`（預設 8080，`0` 停用）、
`tms.PanelThreads`、`tms.PanelToken`（固定 Token；不設就每次啟動隨機產生）。

面板只綁 `127.0.0.1`，但這不等於安全——同一台機器上任何網頁都能對 localhost 發請求。所以每個 `/api/*`
都要 `Authorization: Bearer <token>`，並檢查 `Host` / `Origin`；密碼類欄位（`tms.Pass`、`tms.PanelToken`）
永遠不會送到瀏覽器。不要把這個 port 開到公網。

### 前端建置

前端在 `panel/`（Vite + React + TypeScript + Tailwind + shadcn/ui），建置產物 `panel/dist` 會被
`Launcher.bat` 複製進 `build\classes\server\panel\web\` 一起打進 jar。第一次要先裝依賴：

```
cd panel
npm install
```

之後照常跑 `Launcher.bat` 即可：只要 `panel\` 底下（`node_modules\` 與 `dist\` 除外）有檔案比
`panel\dist\index.html` 新，就會先 `npm run build` 再打包；只改 `.java` 時不會碰 npm，只是把現成的
`panel\dist` 再複製一次。沒有 Node 或沒有 `node_modules` 時會印出警告並跳過前端建置，伺服器照常
建置與啟動，面板首頁會顯示「前端尚未建置」。

開發前端時可以 `npm run dev`，Vite 會把 `/api` 轉給 127.0.0.1:8080 的伺服器。
