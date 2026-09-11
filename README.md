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
Launcher.bat cashshop   改跑 tools.wztosql.CashShopDumper
```

`build.xml` 是 NetBeans 產生的殼，會 `<import>` 未納入版控的 `nbproject\build-impl.xml`，
所以從乾淨的 checkout 直接跑 `ant` 是不會動的；請改用 `Launcher.bat`。

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

`dist\lib\` 內的相依：MINA 1.x、Nashorn（JDK 15 起已從 JDK 移除，改由 `nashorn-core` 提供）、
Nashorn 需要的 asm、MySQL Connector/J、slf4j。

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
只讀一次。剩下最大的一塊是頻道：每個頻道都要為 `tms.Events` 裡約 100 支腳本各開一個 Nashorn
引擎並編譯，這部分已改為平行編譯。

總時間會隨機器核心數、`tms.Count`（頻道數）與 `tms.Events` 的長度而變。
