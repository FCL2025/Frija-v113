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

```
build.bat        編譯 src\ 並打包成 dist\TMS113.jar
Launcher.bat     啟動伺服器（工作目錄必須是專案根目錄）
```

`build.xml` 是 NetBeans 產生的殼，會 `<import>` 未納入版控的 `nbproject\build-impl.xml`，
所以從乾淨的 checkout 直接跑 `ant` 是不會動的；請改用 `build.bat`。

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
