# Frija v113

Frija 是台服（TWMS）v113 私服的伺服器端，免費、開源。

名字取自北歐神話的 Frija，她是奧丁的妻子，而本專案源自 OdinMS。她的名字和英文的 free、friend 同源，原意都是「愛」。

這份文件只說明如何把伺服器架起來。

## 事前準備

| 項目 | 版本 | 下載 | 說明 |
|---|---|---|---|
| Windows | 10 / 11 | | 啟動腳本 `Launcher.bat` 是批次檔 |
| JDK | **25**（LTS）以上 | [Eclipse Temurin 25](https://adoptium.net/temurin/releases/?version=25&os=windows&arch=x64&package=jdk)／[Oracle JDK 25](https://www.oracle.com/java/technologies/downloads/#java25) | 必須是 **JDK**，不是 JRE（啟動前會自動編譯）。建議裝長期支援版 25，Oracle JDK 或任一 OpenJDK 25 發行版皆可 |
| MySQL | 8.0 以上 | [MySQL Community Server](https://dev.mysql.com/downloads/mysql/) | |
| 遊戲客戶端 | v113 台服 | [v113.7z](https://archive.org/download/twms-maplestory/v113.7z) | 用 [7-Zip](https://www.7-zip.org/) 解壓縮。需能連到你的伺服器 IP，**不包含在專案裡** |
| WZ 資料 | v113 台服 | 從上面的客戶端匯出 | 匯出成 XML 格式，**不包含在專案裡**，需自行準備，見[第 3 步](#3-放入-wz-資料) |
| 登入器 | 最新版 | [TMSLauncher](https://github.com/zhyonc/TMSLauncher) | 讓客戶端連到你的伺服器，見[第 6 步](#6-連線進遊戲) |
| Node.js | 20 以上（選用） | [Node.js](https://nodejs.org/) | 只有網頁管理面板需要，不裝也能正常開服 |

伺服器以 4 GB 記憶體啟動，建議機器至少有 8 GB。

## 架設步驟

### 1. 安裝 JDK 25

安裝完成後，開一個新的命令提示字元確認版本：

```
java -version
```

要看到 `25`（或更新的版本）。如果電腦裡裝了不只一個 Java，請把系統環境變數 `JAVA_HOME` 設成 JDK 25
的安裝資料夾（例如 `C:\Program Files\Java\jdk-25`）。

### 2. 安裝 MySQL 並匯入資料庫

安裝 MySQL 8，記下 root 密碼。接著在專案根目錄開啟**命令提示字元（cmd）**，執行：

```
mysql -u root -p < sql\twmsv113.sql
```

- 這會自動建立 `twmsv113` 資料庫，不需要先手動建立。
- PowerShell 不支援 `<` 這種寫法，請用 cmd。
- 找不到 `mysql` 指令時，它在 MySQL 安裝目錄的 `bin\` 底下（例如
  `C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe`），可以打完整路徑或加進 PATH。
  也可以改用 MySQL Workbench 的 File → Run SQL Script 匯入同一個檔案。

### 3. 放入 WZ 資料

用 WZ 工具（例如 HaRepacker）把 v113 台服客戶端的 `.wz` 檔匯出成 XML，放進專案根目錄的
`wz\` 資料夾。每個 `X.wz` 都是一個**資料夾**，裡面是 `.img.xml` 檔，例如：

```
wz\Base.wz\
wz\Character.wz\
wz\Effect.wz\
wz\Etc.wz\
wz\Item.wz\
wz\Map.wz\
wz\Mob.wz\
wz\Morph.wz\
wz\Npc.wz\
wz\Quest.wz\
wz\Reactor.wz\
wz\Skill.wz\
wz\Sound.wz\
wz\String.wz\
wz\TamingMob.wz\
wz\UI.wz\
```

### 4. 建立設定檔

在專案根目錄複製範本：

```
copy Settings.ini.example Settings.ini
```

打開 `Settings.ini`，**至少**要填 MySQL 的帳號密碼：

```
tms.User          = root
tms.Pass          = 你的MySQL密碼
```

其他常用設定：

| 設定 | 說明 |
|---|---|
| `tms.IP` | 客戶端要連線的 IP。只在本機玩填 `127.0.0.1`；要給其他電腦連線，填這台主機的區網 IP 或公網 IP |
| `tms.Count` | 頻道數量，預設 3 |
| `tms.Exp` / `tms.Meso` / `tms.Drop` | 經驗、楓幣、掉寶倍率 |
| `tms.ServerName` | 伺服器名稱 |

存檔時請保持 UTF-8 編碼（Windows 記事本預設就是）。

伺服器開著的時候也可以直接改這個檔案：存檔後幾秒內就會自動套用，不用重啟——
包括頻道數、各頻道的埠、倍率、資料庫帳密等。唯一的例外是 `tms.Url`（資料庫連線網址），
改了要重啟伺服器才會生效。

### 5. 啟動伺服器

雙擊專案根目錄的 `Launcher.bat`。第一次會先編譯（約 10 秒），接著啟動伺服器。
看到下面這一行就代表開服完成：

```
Loaded Complete ::: 啟動完成於 ...
```

關服時請在伺服器視窗按 **Ctrl+C**，伺服器會先存檔再結束；直接關掉視窗可能來不及存檔。

### 6. 連線進遊戲

台服客戶端原本只會連官方伺服器，而且帶有 HackShield 和 CRC 檢查，直接開
`MapleStory.exe` 連不進私服。請搭配 zhyonc 製作的登入器
[**TMSLauncher**](https://github.com/zhyonc/TMSLauncher)（支援台服 v113–v194）。
它會把客戶端的連線導向你的伺服器、拿掉 HackShield 和 CRC 檢查、跳過廣告視窗，
也修正了舊客戶端在新螢幕上的視窗模式問題。

1. 到 [Releases](https://github.com/zhyonc/TMSLauncher/releases) 下載 `Release.zip`，
   把裡面的 `Launcher.exe` 和 `Hook.dll` 放進客戶端資料夾（和 `MapleStory.exe` 同一層）。
2. 之後都從 `Launcher.exe` 進遊戲。

`Release.zip` 裡的版本固定連 `127.0.0.1:8484`，只適合伺服器和客戶端在同一台電腦。
要連別台主機，就要修改原始碼 `Hook\Resources\Config.h` 的 `ServerAddr`（伺服器的 IP 或網域），
再用 Visual Studio 以 `Release x86` 編譯，因為這個位址是編譯時寫死的，沒有設定檔可以改。
`Config.h` 裡另外兩項請維持預設：Frija 不支援連線金鑰和封包 XOR，開了會在第一個封包就被伺服器斷線。

| `Config.h` 設定 | 值 |
|---|---|
| `ServerAddr` | 伺服器的 IP 或網域 |
| `LoginServerPort` | `8484`（維持預設） |
| `IsSendConnectKey` | `false`（維持預設） |
| `RecvXOR` / `SendXOR` | `0x00`（維持預設） |

選頻道和進商城時，登入器會照伺服器回傳的 `tms.IP` 連線；只有 `tms.IP` 是 `127.0.0.1` 時，
才改連 `ServerAddr`。所以 `tms.IP` 照第 4 步填就可以。

連線需要的埠和帳號：

- 登入伺服器固定在 **8484** 埠。
- 要讓其他電腦連線，防火牆需開放以下 TCP 埠（只在本機玩不用開）：

  | 埠 | 用途 |
  |---|---|
  | 8484 | 登入 |
  | 8586 起，每個頻道一個 | 頻道（3 個頻道就是 8586、8587、8588） |
  | 8596 | 購物商城 |

- `Settings.ini` 的 `tms.AutoRegister = true` 時，在登入畫面輸入一組**還不存在**的帳號與密碼，
  就會自動建立帳號並登入。從伺服器本機連線（127.0.0.1）註冊的帳號數量不限；
  從其他 IP 連線，每個 IP 只能自動註冊一個帳號。

## 設定 GM（選用）

GM 等級 `3` 是管理員，可以在遊戲內使用 `!` 開頭的 GM 指令。角色在線上或離線都可以改，以下任一種方式：

- **管理面板**（見下一節）：「帳號與角色」→ 點角色 → 把「GM 等級」改成 `3` → 儲存。角色在線上時立即生效。
- **遊戲內指令**（需要已經是 GM）：`!setgm 角色名稱 3`。對方在線上時立即生效，離線則下次登入生效。
- **資料庫**（角色要離線）：

  ```sql
  UPDATE twmsv113.characters SET gm = 3 WHERE name = '角色名稱';
  ```

第一個 GM 請用管理面板或資料庫來設定。

## 管理面板（選用）

需要先安裝 Node.js 20 以上，然後只要做一次：

```
cd panel
npm install
```

之後照常啟動 `Launcher.bat`，它會自動建置面板。伺服器開好後，用瀏覽器開
<http://127.0.0.1:8080/>，登入用的 Token 會印在伺服器視窗裡。面板只能從伺服器本機開啟。

## 常見問題

**啟動後停在開頭，很久都沒有進展**
MySQL 的帳號密碼錯誤，或 MySQL 服務沒有啟動。帳密錯誤時伺服器不會立刻報錯，而是安靜地重試約
30 分鐘。請檢查 `Settings.ini` 的 `tms.User` / `tms.Pass`。

**一啟動就自己結束，訊息提到 `auth_server_channel_ip`**
資料庫沒有匯入成功。請重做第 2 步。

**出現 `ERROR: no JDK found` 或 `that is a JRE, not a JDK`**
沒有安裝 JDK 25，或 `JAVA_HOME` 指到了錯誤的位置。請重做第 1 步。

**出現 `error: release version 25 not supported`**
找到的 JDK 比 25 舊。請安裝 JDK 25，並把 `JAVA_HOME` 指向它。

**可以登入，但選頻道後斷線或連不上**
`tms.IP` 填錯，或防火牆沒有開放頻道的埠。
