/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc> 
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package client;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import database.DatabaseConnection;
import database.DatabaseException;
import handling.MaplePacket;
import handling.cashshop.CashShopServer;
import handling.channel.ChannelServer;
import handling.world.Buddy;
import handling.world.Client;
import handling.world.Family;
import handling.world.Find;
import handling.world.Guild;
import handling.world.MapleMessengerCharacter;
import handling.world.MapleParty;
import handling.world.MaplePartyCharacter;
import handling.world.Messenger;
import handling.world.Party;
import handling.world.PartyOperation;
import handling.world.family.MapleFamilyCharacter;
import handling.world.guild.MapleGuildCharacter;
import server.maps.MapleMap;
import server.MapleTrade;
import server.shops.IMaplePlayerShop;
import scripting.ScriptScope;
import tools.FileoutputUtil;
import tools.MapleAESOFB;
import tools.packet.ChatPacket;
import tools.packet.LoginPacket;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.mina.common.IoSession;
import server.Timer.EtcTimer;
import server.Timer.PingTimer;
import server.quest.MapleQuest;

public class MapleClient {

    /**
     * See {@code tools.Logging} for the naming and level rules. This is the
     * login path, so most of what used to reach {@code System.err} here is the
     * only record that a ban, a login-state write or a MAC write was abandoned:
     * those are {@code ERROR}, not {@code WARNING}.
     */
    private static final System.Logger LOG = System.getLogger(MapleClient.class.getName());

    public static final byte LOGIN_NOTLOGGEDIN = 0,
            LOGIN_SERVER_TRANSITION = 1,
            LOGIN_LOGGEDIN = 2,
            LOGIN_WAITING = 3,
            CASH_SHOP_TRANSITION = 4,
            LOGIN_CS_LOGGEDIN = 5,
            CHANGE_CHANNEL = 6;
    public static final int DEFAULT_CHARSLOT = 6;
    public static final String CLIENT_KEY = "CLIENT";
    private MapleAESOFB send, receive;
    private IoSession session;
    private MapleCharacter player;
    private int channel = 1, accId = 1, world, birthday;
    private int charslots = DEFAULT_CHARSLOT;
    private boolean loggedIn = false, serverTransition = false;
    private Instant tempban = null;
    private String accountName;
    private long lastPong = 0, lastPing = 0;
    private boolean monitored = false, receiving = true;
    private boolean gm;
    private byte greason = 1, gender = -1;
    public short loginAttempt = 0;
    private List<Integer> allowedChar = new LinkedList<>();
    private Set<String> macs = new HashSet<>();
    /**
     * One Nashorn Global per script this client is running. A plain HashMap
     * on purpose: only this client's own handler thread ever touches it, and
     * the panel deliberately stays out of it - a live conversation belongs to
     * the player, not to an admin. What is shared process-wide is the compiled
     * form of the scripts, in {@link scripting.ScriptCache}, and that is the
     * thing the reloads drop.
     */
    private Map<String, ScriptScope> engines = new HashMap<>();
    private ScheduledFuture<?> idleTask = null;
    private String secondPassword, salt2; // To be used only on login
    private final Lock mutex = new ReentrantLock(true);
    private final Lock npc_mutex = new ReentrantLock();
    private final static Lock login_mutex = new ReentrantLock(true);

    public MapleClient(MapleAESOFB send, MapleAESOFB receive, IoSession session) {
        this.send = send;
        this.receive = receive;
        this.session = session;
    }

    public final MapleAESOFB getReceiveCrypto() {
        return receive;
    }

    public final MapleAESOFB getSendCrypto() {
        return send;
    }

    public final IoSession getSession() {
        return session;
    }

    /**
     * 送出一個封包給這個連線。整棵樹唯一直接呼叫 MINA
     * {@link IoSession#write(Object)} 的地方；其餘程式碼一律走這裡，
     * 日後更換網路層時只需動 handling/mina/。
     */
    public final void send(final MaplePacket packet) {
        session.write(packet);
    }

    public final Lock getLock() {
        return mutex;
    }

    public final Lock getNPCLock() {
        return npc_mutex;
    }

    public MapleCharacter getPlayer() {
        return player;
    }

    public void setPlayer(MapleCharacter player) {
        this.player = player;
    }

    public void createdChar(final int id) {
        allowedChar.add(id);
    }

    public final boolean login_Auth(final int id) {
        return allowedChar.contains(id);
    }

    public final List<MapleCharacter> loadCharacters(final int serverId) { // TODO make this less costly zZz
        final List<MapleCharacter> chars = new LinkedList<>();

        for (final CharNameAndId cni : loadCharactersInternal(serverId)) {
            final MapleCharacter chr = MapleCharacter.loadCharFromDB(cni.id, this, false);
            chars.add(chr);
            allowedChar.add(chr.getId());
        }
        return chars;
    }

    public List<String> loadCharacterNames(int serverId) {
        List<String> chars = new LinkedList<>();
        for (CharNameAndId cni : loadCharactersInternal(serverId)) {
            chars.add(cni.name);
        }
        return chars;
    }

    private List<CharNameAndId> loadCharactersInternal(int serverId) {
        List<CharNameAndId> chars = new LinkedList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id, name FROM characters WHERE accountid = ? AND world = ?")) {
            ps.setInt(1, accId);
            ps.setInt(2, serverId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    chars.add(new CharNameAndId(rs.getString("name"), rs.getInt("id")));
                }
            }
        } catch (SQLException e) {
            // ERROR: the list is abandoned half-built, so the character-select
            // screen silently loses characters. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "error loading characters internal" + e);
        }
        return chars;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    /**
     * The instant this account's temporary ban expires, or {@link Instant#EPOCH}
     * for "not temp-banned" - that sentinel is what the caller tests, and it is
     * the same zero the column itself uses, so it is kept rather than swapped
     * for {@code null}.
     *
     * <p>「沒有暫時封鎖」在資料庫裡有三種寫法，這裡三種都要認得，因為這段在登入
     * 路徑上，認錯一種就是所有人都進不來：
     * <ul>
     * <li>SQL {@code NULL} - 跑過 {@code sql/migrations/i3-zero-dates.sql} 之後
     * 的樣子，也是現在 {@code sql/twmsv113.sql} 新匯入時的樣子。</li>
     * <li>零日期 {@code 0000-00-00 00:00:00} - 2016 年那份 dump 留下的
     * {@code NOT NULL DEFAULT} 值，還沒遷移的資料庫裡每個沒被封過的帳號都是它。</li>
     * <li>任何已經過去的時間 - 例如管理面板的
     * {@code AccountsApi.TEMPBAN_NONE}（{@code 1970-01-02}）。這一種是靠下面
     * 「到期了就回 EPOCH」的判斷吃掉的，不需要另外列舉。</li>
     * </ul>
     */
    private Instant getTempBanTime(ResultSet rs) throws SQLException {
        // Read the column as text before anything else touches it. An account
        // that has never been banned carries the column's 0000-00-00 00:00:00
        // default, and Connector/J 8 refuses to hand that value to getTimestamp
        // ("Zero date value prohibited") or to getLong ("Unsupported conversion
        // from TIMESTAMP to java.lang.Long"). getString returns it verbatim.
        // This test used to read getLong("tempban") == 0, which worked only on
        // the pre-8 connector. Do not replace it with a java.time accessor -
        // getObject(.., LocalDateTime.class) refuses a zero date just as loudly.
        //
        // I3：等到「所有」資料庫都跑過 sql/migrations/i3-zero-dates.sql，欄位就只會
        // 是 NULL 或一個合法時間，屆時整段 getString 迂迴可以換回
        // rs.getTimestamp("tempban")（null 代表沒有封鎖）。在那之前不要拿掉
        // startsWith("0000-00-00")：少了它，未遷移的資料庫會在 getTimestamp 上丟
        // SQLException，login() 整個失敗，等於全服鎖在登入畫面外。
        final String rawTempban = rs.getString("tempban");
        if (rawTempban == null || rawTempban.startsWith("0000-00-00")) {
            return Instant.EPOCH;
        }
        // getTimestamp().getTime() rather than toInstant(): the column has no
        // fractional-seconds precision, and millisecond truncation is what the
        // Calendar this replaces did.
        final Instant lTempban = Instant.ofEpochMilli(rs.getTimestamp("tempban").getTime());
        if (Instant.now().isBefore(lTempban)) {
            return lTempban;
        }
        return Instant.EPOCH;
    }

    public Instant getTempBanTime() {
        return tempban;
    }

    public byte getBanReason() {
        return greason;
    }

    public boolean hasBannedIP() {
        boolean ret = false;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM ipbans WHERE ? LIKE CONCAT(ip, '%')")) {
            ps.setString(1, session.getRemoteAddress().toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    ret = true;
                }
            }
        } catch (SQLException ex) {
            // ERROR: the check is abandoned and ret stays false, so a banned IP
            // is let through. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error checking ip bans" + ex);
        }
        return ret;
    }

    public boolean hasBannedMac() {
        if (macs.isEmpty()) {
            return false;
        }
        boolean ret = false;
        int i = 0;
        try (Connection con = DatabaseConnection.getConnection()) {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM macbans WHERE mac IN (");
            for (i = 0; i < macs.size(); i++) {
                sql.append("?");
                if (i != macs.size() - 1) {
                    sql.append(", ");
                }
            }
            sql.append(")");
            try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
                i = 0;
                for (String mac : macs) {
                    i++;
                    ps.setString(i, mac);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        ret = true;
                    }
                }
            }
        } catch (SQLException ex) {
            // ERROR: same shape as the IP check - abandoned, ret stays false,
            // so a banned MAC is let through. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error checking mac bans" + ex);
        }
        return ret;
    }

    private void loadMacsIfNescessary() throws SQLException {
        if (macs.isEmpty()) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT macs FROM accounts WHERE id = ?")) {
                ps.setInt(1, accId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (rs.getString("macs") != null) {
                            String[] macData = rs.getString("macs").split(", ");
                            for (String mac : macData) {
                                if (!mac.equals("")) {
                                    macs.add(mac);
                                }
                            }
                        }
                    } else {
                        throw new RuntimeException("No valid account associated with this client.");
                    }
                }
            }
        }
    }

    public void banMacs() {
        try {
            loadMacsIfNescessary();
            if (this.macs.size() > 0) {
                String[] macBans = new String[this.macs.size()];
                int z = 0;
                for (String mac : this.macs) {
                    macBans[z] = mac;
                    z++;
                }
                banMacs(macBans);
            }
        } catch (SQLException e) {
            // ERROR: the MACs were never loaded, so nothing was banned.
            LOG.log(System.Logger.Level.ERROR, "Error banning MACs for account " + accId, e);
        }
    }

    public static final void banMacs(String[] macs) {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<String> filtered = new LinkedList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT filter FROM macfilters");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    filtered.add(rs.getString("filter"));
                }
            }

            try (PreparedStatement ps = con.prepareStatement("INSERT INTO macbans (mac) VALUES (?)")) {
                for (String mac : macs) {
                    boolean matched = false;
                    for (String filter : filtered) {
                        if (mac.matches(filter)) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        ps.setString(1, mac);
                        try {
                            ps.executeUpdate();
                        } catch (SQLException e) {
                            // can fail because of UNIQUE key, we dont care
                        }
                    }
                }
            }
        } catch (SQLException e) {
            // ERROR: the macbans rows were not written, so the ban did not take.
            // Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error banning MACs" + e);
        }
    }

    /**
     * Returns 0 on success, a state to be used for
     * {@link tools.packet.LoginPacket#getLoginFailed(int)} otherwise.
     *
     * @param success
     * @return The state of the login.
     */
    public int finishLogin() {
        login_mutex.lock();
        try {
            final byte state = getLoginState();
            if (state > MapleClient.LOGIN_NOTLOGGEDIN && state != MapleClient.LOGIN_WAITING) { // already loggedin
                loggedIn = false;
                return 7;
            }
            updateLoginState(MapleClient.LOGIN_LOGGEDIN, getSessionIPAddress());
        } finally {
            login_mutex.unlock();
        }
        return 0;
    }

    /**
     * True if {@code pwd} matches {@code passhash} under any of the four schemes
     * the {@code accounts} table can hold: PBKDF2-HMAC-SHA-512, the OdinMS legacy
     * {@code $H$} hash, an unsalted SHA-1, or a salted SHA-512. A null salt means
     * the row predates the salted scheme.
     *
     * The three legacy branches are untouched, and must stay that way: they are
     * the only thing that lets an account which has not logged in since PBKDF2
     * landed log in at all. {@link #login} upgrades the row on the way through.
     *
     * Used for the second password too ({@link #CheckSecondPassword}), where the
     * PBKDF2 branch never fires - {@code 2ndpassword} is still a salted SHA-512,
     * because {@code LoginCrypto.rand_r} peels it back off with a hardcoded
     * {@code substring(6, 6 + 128)} that a 123-character PBKDF2 string would run
     * off the end of. Converting that column means changing rand_s/rand_r and
     * widening {@code varchar(134)} as well, which is a separate job.
     *
     * There is deliberately no master password here. {@code GameConstants.MASTER}
     * used to short-circuit this check, which let anyone who knew that one string
     * - it is a constant published in every OdinMS-derived source tree - log into
     * any account on the server, GM accounts included.
     */
    private static boolean passwordMatches(final String passhash, final String pwd, final String salt) {
        if (passhash == null || pwd == null) {
            return false;
        }
        // PBKDF2 first and exclusively. The prefix is self-describing, so a row
        // that carries one is never re-tried against a legacy scheme - falling
        // through would be the one way a weaker check could still answer for an
        // already-upgraded account.
        if (LoginCrypto.isPbkdf2Hash(passhash)) {
            return LoginCrypto.checkPbkdf2Hash(passhash, pwd);
        }
        if (passhash.length() >= 3 && LoginCryptoLegacy.isLegacyPassword(passhash) && LoginCryptoLegacy.checkPassword(pwd, passhash)) {
            return true;
        }
        if (salt == null) {
            return LoginCrypto.checkSha1Hash(passhash, pwd);
        }
        return LoginCrypto.checkSaltedSha512Hash(passhash, pwd, salt);
    }

    public int login(String login, String pwd, boolean ipMacBanned) {
        int loginok = 5;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM accounts WHERE name = ?")) {
            ps.setString(1, login);
            try (ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                final int banned = rs.getInt("banned");
                final String passhash = rs.getString("password");
                final String salt = rs.getString("salt");

                accId = rs.getInt("id");
                secondPassword = rs.getString("2ndpassword");
                salt2 = rs.getString("salt2");
                gm = rs.getInt("gm") > 0;
                greason = rs.getByte("greason");
                tempban = getTempBanTime(rs);
                gender = rs.getByte("gender");

                if (secondPassword != null && salt2 != null) {
                    secondPassword = LoginCrypto.rand_r(secondPassword);
                }

                if (banned > 0 && !gm) {
                    loginok = 3;
                } else {
                    if (banned == -1) {
                        unban();
                    }
                    byte loginstate = getLoginState();
                    if (loginstate > MapleClient.LOGIN_NOTLOGGEDIN) { // already loggedin
                        loggedIn = false;
                        // Used to accept only an unsalted SHA-1, which stopped working
                        // for an account the moment its hash was upgraded. It has to
                        // understand every scheme the column can hold, like the login
                        // path below, or @解卡 breaks for upgraded accounts.
                        if (passwordMatches(passhash, pwd, salt)) {
                            loginok = 7;
                            unlockAcc();
                        } else {
                            loginok = 4;
                        }
                    } else {
                        if (passwordMatches(passhash, pwd, salt)) {
                            loginok = 0;
                            // Lazy upgrade. This is the only moment the plaintext is in
                            // hand, so anything not already PBKDF2 is rewritten as PBKDF2
                            // here; the account never has to be asked to change anything.
                            // It used to rewrite to a salted SHA-512, and before that it
                            // ran the other way round - both the legacy and the SHA-512
                            // branch rewrote the row as an unsalted SHA-1, so an account
                            // got *weaker* every time its owner logged in.
                            //
                            // Only `password` is written. The PBKDF2 string carries its own
                            // salt and iteration count, so `salt` is not consulted for such
                            // a row; whatever it held stays there, harmless and ignored.
                            //
                            // Note what this does NOT repair: while a row is still on a
                            // legacy hash, LoginCrypto.hashWithDigest truncates a non-ASCII
                            // password to its char count in bytes, so a *colliding* password
                            // reaches this line too - and the upgrade below would then store
                            // the PBKDF2 of that password, handing the account over. See the
                            // javadoc on hashWithDigest; it is unfixable without forcing a
                            // password reset, and is documented rather than hidden.
                            if (!LoginCrypto.isPbkdf2Hash(passhash)) {
                                try (PreparedStatement pss = con.prepareStatement("UPDATE `accounts` SET `password` = ? WHERE id = ?")) {
                                    pss.setString(1, LoginCrypto.makePbkdf2Hash(pwd));
                                    pss.setInt(2, accId);
                                    pss.executeUpdate();
                                } catch (SQLException upgrade) {
                                    // A failed upgrade must never fail the login: loginok is
                                    // already 0 and the row still holds a hash that verifies,
                                    // so the player gets in and the next login tries again.
                                    // Caught here rather than left to the outer handler so
                                    // that stays true no matter what is added below.
                                    // ERROR: the login itself carries on, but the
                                    // UPDATE was abandoned - this line is the only
                                    // record that the row is still on a weak hash.
                                    // Terse on purpose, as it was.
                                    LOG.log(System.Logger.Level.ERROR,
                                            "[login] PBKDF2 upgrade failed for account " + accId + ": " + upgrade);
                                }
                            }
                        } else {
                            loggedIn = false;
                            loginok = 4;
                        }
                    }
                }
            }
            }
        } catch (SQLException e) {
            // ERROR: the whole login query is abandoned and loginok stays 5, so
            // the client is told the account is already logged in. Terse.
            LOG.log(System.Logger.Level.ERROR, "ERROR" + e);
        }
        return loginok;
    }
    public void unlockAcc() {
        boolean unLocked = false;
        for (final MapleClient c: Client.getClients()) {
            if (c.getAccID() == accId && c.isLoggedIn()) {
                if (!c.getSession().isConnected()) {
//					c.disconnect(true, c.getChannel() == -10);
                    List<String> charName = c.loadCharacterNames(c.getWorld());
                    for (final String cha : charName) {
                        MapleCharacter chr = CashShopServer.getPlayerStorage().getCharacterByName(cha);
                        if (chr != null) {
                            chr.saveToDB(false,false);
                            CashShopServer.getPlayerStorage().deregisterPlayer(chr);
                            break;
                        }
                    }
                    for (ChannelServer cs : ChannelServer.getAllInstances()) {
                        for (final String cha : charName) {
                            MapleCharacter chr = cs.getPlayerStorage().getCharacterByName(cha);
                            if (chr != null) {
                                chr.saveToDB(false,false);
                                cs.removePlayer(chr);
                                break;
                            }
                        }
                    }
                }
                c.unLockDisconnect();
                unLocked = true;
                break;
            }
        }
        if (!unLocked) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("UPDATE accounts SET loggedin = 0 WHERE name = ?")) {
                ps.setString(1, accountName);
                ps.executeUpdate();
            } catch (SQLException se) {
                // ERROR: loggedin stays set, so the account is stuck and cannot
                // log in again until it is cleared by hand. Terse on purpose.
                LOG.log(System.Logger.Level.ERROR, "ERROR unlockAcc" + se);
            }
        }
    }
    public final void unLockDisconnect() {
        send(ChatPacket.serverNotice(1, "帳號被他人登入。"));
        disconnect(serverTransition, getChannel() == -10);
        // Three seconds so the client can show the notice before its socket
        // goes away. This used to be a bare Thread that slept, which breaks the
        // rule that every delayed job in here runs on a server.Timer pool: a
        // burst of duplicate logins would spawn one short-lived thread each,
        // and none of them were named, daemons, or cancellable.
        EtcTimer.getInstance().schedule(() -> getSession().close(), 3000);
    }
    public boolean CheckSecondPassword(String in) {
        // Check if the passwords are correct here. :B
        // No master password here either - GameConstants.MASTER used to satisfy
        // this check outright, so the second password protected nothing.
        final boolean allow = passwordMatches(secondPassword, in, salt2);
        // Only a row that is not already a salted SHA-512 needs rewriting.
        final boolean updatePasswordHash = allow && salt2 == null;

        if (updatePasswordHash) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("UPDATE `accounts` SET `2ndpassword` = ?, `salt2` = ? WHERE id = ?")) {
                final String newSalt = LoginCrypto.makeSalt();
                ps.setString(1, LoginCrypto.rand_s(LoginCrypto.makeSaltedSha512Hash(in, newSalt)));
                ps.setString(2, newSalt);
                ps.setInt(3, accId);
                ps.executeUpdate();
            } catch (SQLException e) {
                return false;
            }
        }
        return allow;
    }

    private void unban() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET banned = 0 and banreason = '' WHERE id = ?")) {
            ps.setInt(1, accId);
            ps.executeUpdate();
        } catch (SQLException e) {
            // ERROR: the account is still banned; the unban did not happen.
            // Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error while unbanning" + e);
        }
    }

    public static final byte unban(String charname) {
        try (Connection con = DatabaseConnection.getConnection()) {
            final int accid;
            try (PreparedStatement ps = con.prepareStatement("SELECT accountid from characters where name = ?")) {
                ps.setString(1, charname);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return -1;
                    }
                    accid = rs.getInt(1);
                }
            }

            try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET banned = 0 and banreason = '' WHERE id = ?")) {
                ps.setInt(1, accid);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // ERROR: abandoned, -2 goes back to the caller and the account is
            // still banned. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error while unbanning" + e);
            return -2;
        }
        return 0;
    }

    public void updateMacs(String macData) {
        for (String mac : macData.split(", ")) {
            macs.add(mac);
        }
        StringBuilder newMacData = new StringBuilder();
        Iterator<String> iter = macs.iterator();
        while (iter.hasNext()) {
            newMacData.append(iter.next());
            if (iter.hasNext()) {
                newMacData.append(", ");
            }
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET macs = ? WHERE id = ?")) {
            ps.setString(1, newMacData.toString());
            ps.setInt(2, accId);
            ps.executeUpdate();
        } catch (SQLException e) {
            // ERROR: the macs column was not updated, so a later MAC ban has
            // nothing to work from. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error saving MACs" + e);
        }
    }

    public void setAccID(int id) {
        this.accId = id;
    }

    public int getAccID() {
        return this.accId;
    }

    public final void updateLoginState(final int newstate, final String SessionID) { // TODO hide?
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET loggedin = ?, SessionIP = ?, lastlogin = CURRENT_TIMESTAMP() WHERE id = ?")) {
            ps.setInt(1, newstate);
            ps.setString(2, SessionID);
            ps.setInt(3, getAccID());
            ps.executeUpdate();
        } catch (SQLException e) {
            // ERROR: the loggedin column keeps its old value while the in-memory
            // state below moves on, which is exactly how an account gets stuck.
            // Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "error updating login state" + e);
        }
        if (newstate == MapleClient.LOGIN_NOTLOGGEDIN || newstate == MapleClient.LOGIN_WAITING) {
            loggedIn = false;
            serverTransition = false;
        } else {
            serverTransition = (newstate == MapleClient.LOGIN_SERVER_TRANSITION || newstate == MapleClient.CHANGE_CHANNEL);
            loggedIn = !serverTransition;
        }
    }

    public final void updateSecondPassword() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE `accounts` SET `2ndpassword` = ?, `salt2` = ? WHERE id = ?")) {
            final String newSalt = LoginCrypto.makeSalt();
            ps.setString(1, LoginCrypto.rand_s(LoginCrypto.makeSaltedSha512Hash(secondPassword, newSalt)));
            ps.setString(2, newSalt);
            ps.setInt(3, accId);
            ps.executeUpdate();

        } catch (SQLException e) {
            // ERROR: the new second password never reached the row. The text is
            // a copy-paste that predates this method; kept verbatim so the line
            // stays greppable. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "error updating login state" + e);
        }
    }

    public final void updateGender() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE `accounts` SET `gender` = ? WHERE id = ?")) {
            ps.setInt(1, gender);
            ps.setInt(2, accId);
            ps.executeUpdate();

        } catch (SQLException e) {
            // ERROR: the gender column was not updated. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "error updating gender" + e);
        }
    }

    /**
     * <p>{@code `birthday` + 0} 做兩件事，別把它「簡化」掉：
     * <ul>
     * <li>迴避零日期 - {@code accounts.birthday} 在還沒遷移的資料庫裡是
     * {@code NOT NULL DEFAULT '0000-00-00'}，直接 {@code getDate}／
     * {@code getTimestamp} 會被 Connector/J 以 "Zero date value prohibited" 擋掉。</li>
     * <li>順手轉成 {@code yyyyMMdd} 整數 - 這正是 {@link #checkBirthDate} 比對的格式。</li>
     * </ul>
     * 兩種 schema 算出來的東西一樣：零日期 {@code + 0} 是 {@code 0}，而遷移後的
     * {@code NULL + 0} 是 {@code NULL}，{@code getInt} 讀 {@code NULL} 也是 {@code 0}。
     * 所以第二件事才是它非留不可的理由 - I3 遷移完成後也不必動這一行。
     */
    public final byte getLoginState() { // TODO hide?
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT loggedin, lastlogin, `birthday` + 0 AS `bday` FROM accounts WHERE id = ?")) {
            ps.setInt(1, getAccID());
            byte state;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new DatabaseException("Everything sucks");
                }
                birthday = rs.getInt("bday");
                state = rs.getByte("loggedin");

                if (state == MapleClient.LOGIN_SERVER_TRANSITION || state == MapleClient.CHANGE_CHANNEL) {
                    if (rs.getTimestamp("lastlogin").getTime() + 20000 < System.currentTimeMillis()) { // connecting to chanserver timeout
                        state = MapleClient.LOGIN_NOTLOGGEDIN;
                        updateLoginState(state, getSessionIPAddress());
                    }
                }
            }
            if (state == MapleClient.LOGIN_LOGGEDIN) {
                loggedIn = true;
            } else {
                loggedIn = false;
            }
            return state;
        } catch (SQLException e) {
            loggedIn = false;
            throw new DatabaseException("error getting login state", e);
        }
    }

    public final boolean checkBirthDate(final int date) {
        return birthday == date;
    }

    public final void removalTask() {
        try {
            player.cancelAllBuffs_();
            player.cancelAllDebuffs();
            if (player.getMarriageId() > 0) {
                final MapleQuestStatus stat1 = player.getQuestNAdd(MapleQuest.getInstance(160001));
                final MapleQuestStatus stat2 = player.getQuestNAdd(MapleQuest.getInstance(160002));
                if (stat1.getCustomData() != null && (stat1.getCustomData().equals("2_") || stat1.getCustomData().equals("2"))) {
                    //dc in process of marriage
                    if (stat2.getCustomData() != null) {
                        stat2.setCustomData("0");
                    }
                    stat1.setCustomData("3");
                }
            }
            player.changeRemoval(true);
            if (player.getEventInstance() != null) {
                player.getEventInstance().playerDisconnected(player, player.getId());
            }
            if (player.getMap() != null) {
                switch (player.getMapId()) {
                    case 541010100: //latanica
                    case 541020800: //scar/targa
                    case 551030200: //krexel
                    case 220080001: //pap
                        player.getMap().addDisconnected(player.getId());
                        break;
                }
                player.getMap().removePlayer(player);
            }

            final IMaplePlayerShop shop = player.getPlayerShop();
            if (shop != null) {
                shop.removeVisitor(player);
                if (shop.isOwner(player)) {
                    if (shop.getShopType() == 1 && shop.isAvailable()) {
                        shop.setOpen(true);
                    } else {
                        shop.closeShop(true, true);
                    }
                }
            }
            player.setMessenger(null);
        } catch (final Throwable e) {
            FileoutputUtil.outputFileError(FileoutputUtil.Acc_Stuck, e);
        }
    }

    public final void disconnect(final boolean RemoveInChannelServer, final boolean fromCS) {
        disconnect(RemoveInChannelServer, fromCS, false);
    }

    public final void disconnect(final boolean RemoveInChannelServer, final boolean fromCS, final boolean shutdown) {
        if (player != null && isLoggedIn()) {
            MapleMap map = player.getMap();
            final MapleParty party = player.getParty();
            final boolean clone = player.isClone();
            final String namez = player.getName();
            final boolean hidden = player.isHidden();
            final int gmLevel = player.getGMLevel();
            final int idz = player.getId(), messengerid = player.getMessenger() == null ? 0 : player.getMessenger().getId(), gid = player.getGuildId(), fid = player.getFamilyId();
            final BuddyList bl = player.getBuddylist();
            final MaplePartyCharacter chrp = new MaplePartyCharacter(player);
            final MapleMessengerCharacter chrm = new MapleMessengerCharacter(player);
            final MapleGuildCharacter chrg = player.getMGC();
            final MapleFamilyCharacter chrf = player.getMFC();
            final IMaplePlayerShop ips = player.getPlayerShop();
            
            if (player.getTrade() != null) {
                MapleTrade.cancelTrade(player.getTrade(), this);
            }
            if(ips != null ){
                if (!ips.isAvailable() || (ips.isOwner(player) && ips.getShopType() != 1) || (ips.isOwner(player) && ips.getShopType() == 1 && !ips.isOpen())) {
                    ips.closeShop(true, true);
                }
            }

            removalTask();
            player.saveToDB(true, fromCS);
            if (shutdown) {
                player = null;
                receiving = false;
                return;
            }

            if (!fromCS) {
                final ChannelServer ch = ChannelServer.getInstance(map == null ? channel : map.getChannel());

                try {
                    if (ch == null || clone || ch.isShutdown()) {
                        player = null;
                        return;//no idea
                    }
                    if (messengerid > 0) {
                        Messenger.leaveMessenger(messengerid, chrm);
                    }
                    if (party != null) {
                        chrp.setOnline(false);
                        Party.updateParty(party.getId(), PartyOperation.LOG_ONOFF, chrp);
                        if (map != null && party.getLeader().getId() == idz) {
                            MaplePartyCharacter lchr = null;
                            for (MaplePartyCharacter pchr : party.getMembers()) {
                                if (pchr != null && map.getCharacterById(pchr.getId()) != null && (lchr == null || lchr.getLevel() < pchr.getLevel())) {
                                    lchr = pchr;
                                }
                            }
                            if (lchr != null) {
                                Party.updateParty(party.getId(), PartyOperation.CHANGE_LEADER_DC, lchr);
                            }
                        }
                    }
                    if (bl != null) {
                        if (!serverTransition && isLoggedIn()) {
                            Buddy.loggedOff(namez, idz, channel, bl.getBuddyIds(), gmLevel, hidden);
                        } else { // Change channel
                            Buddy.loggedOn(namez, idz, channel, bl.getBuddyIds(), gmLevel, hidden);
                        }
                    }
                    if (gid > 0) {
                        Guild.setGuildMemberOnline(chrg, false, -1);
                    }
                    if (fid > 0) {
                        Family.setFamilyMemberOnline(chrf, false, -1);
                    }
                } catch (final Exception e) {
                    // One record, not two: the trace and the context line were
                    // always the same event, so the message keeps e.toString()
                    // (as before) and the Throwable carries the trace.
                    // getLogMessage is null-safe all the way down, so it stays
                    // non-throwing on this half-disconnected player.
                    LOG.log(System.Logger.Level.ERROR, getLogMessage(this, "ERROR") + e, e);
                    FileoutputUtil.outputFileError(FileoutputUtil.Acc_Stuck, e);
                } finally {
                    if (RemoveInChannelServer && ch != null) {
                        ch.removePlayer(idz, namez);
                    }
                    player = null;
                }
            } else {
                final int ch = Find.findChannel(idz);
                if (ch > 0) {
                    disconnect(RemoveInChannelServer, false);//u lie
                    return;
                }
                try {
                    if (party != null) {
                        chrp.setOnline(false);
                        Party.updateParty(party.getId(), PartyOperation.LOG_ONOFF, chrp);
                    }
                    if (!serverTransition && isLoggedIn()) {
                        Buddy.loggedOff(namez, idz, channel, bl.getBuddyIds(), gmLevel, hidden);
                    } else { // Change channel
                        Buddy.loggedOn(namez, idz, channel, bl.getBuddyIds(), gmLevel, hidden);
                    }
                    if (gid > 0) {
                        Guild.setGuildMemberOnline(chrg, false, -1);
                    }
                    if (player != null) {
                        player.setMessenger(null);
                    }
                } catch (final Exception e) {
                    // Same merge as the channel-server branch above.
                    LOG.log(System.Logger.Level.ERROR, getLogMessage(this, "ERROR") + e, e);
                    FileoutputUtil.outputFileError(FileoutputUtil.Acc_Stuck, e);
                } finally {
                    if (RemoveInChannelServer && ch > 0) {
                        CashShopServer.getPlayerStorage().deregisterPlayer(idz, namez);
                    }
                    player = null;
                }
            }
        }
        if (!serverTransition && isLoggedIn()) {
            updateLoginState(MapleClient.LOGIN_NOTLOGGEDIN, getSessionIPAddress());
        }
    }

    public final String getSessionIPAddress() {
        return session.getRemoteAddress().toString().split(":")[0];
    }

    public final boolean CheckIPAddress() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT SessionIP FROM accounts WHERE id = ?")) {
            ps.setInt(1, this.accId);

            boolean canlogin = false;

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    final String sessionIP = rs.getString("SessionIP");

                    if (sessionIP != null) { // Probably a login proced skipper?
                        canlogin = getSessionIPAddress().equals(sessionIP.split(":")[0]);
                    }
                }
            }

            return canlogin;
        } catch (final SQLException e) {
            // ERROR rather than the INFO the stream would suggest: this is a
            // caught SQLException that abandons the check and falls through to
            // "allowed" below, not progress an operator reads. Kept exactly as
            // terse as it was - it never reported the exception at all, so `e`
            // stays unused rather than changing what goes on the console.
            LOG.log(System.Logger.Level.ERROR, "Failed in checking IP address for client.");
        }
        return true;
    }

    public final void DebugMessage(final StringBuilder sb) {
        sb.append(getSession().getRemoteAddress());
        sb.append("Connected: ");
        sb.append(getSession().isConnected());
        sb.append(" Closing: ");
        sb.append(getSession().isClosing());
        sb.append(" ClientKeySet: ");
        sb.append(getSession().getAttribute(MapleClient.CLIENT_KEY) != null);
        sb.append(" loggedin: ");
        sb.append(isLoggedIn());
        sb.append(" has char: ");
        sb.append(getPlayer() != null);
    }

    public final int getChannel() {
        return channel;
    }

    public final ChannelServer getChannelServer() {
        return ChannelServer.getInstance(channel);
    }

    public final int deleteCharacter(final int cid) {
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT guildid, guildrank, familyid, name FROM characters WHERE id = ? AND accountid = ?")) {
                ps.setInt(1, cid);
                ps.setInt(2, accId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return 1;
                    }
                    if (rs.getInt("guildid") > 0) { // is in a guild when deleted
                        if (rs.getInt("guildrank") == 1) { //cant delete when leader
                            return 1;
                        }
                        Guild.deleteGuildCharacter(rs.getInt("guildid"), cid);
                    }
                    if (rs.getInt("familyid") > 0) {
                        Family.getFamily(rs.getInt("familyid")).leaveFamily(cid);
                    }
                }
            }

            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM characters WHERE id = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM monsterbook WHERE charid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM hiredmerch WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM mts_cart WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM mts_items WHERE characterid = ?", cid);
            //MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM cheatlog WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM mountdata WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM inventoryitems WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM famelog WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM famelog WHERE characterid_to = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM dueypackages WHERE RecieverId = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM wishlist WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM buddies WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM buddies WHERE buddyid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM keymap WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM savedlocations WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM skills WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM mountdata WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM skillmacros WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM trocklocations WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM queststatus WHERE characterid = ?", cid);
            MapleCharacter.deleteWhereCharacterId(con, "DELETE FROM inventoryslot WHERE characterid = ?", cid);
            return 0;
        } catch (Exception e) {
            FileoutputUtil.outputFileError(FileoutputUtil.PacketEx_Log, e);
            // ERROR: the delete is abandoned part-way and 1 (failure) goes back
            // to the client, so the character's rows are left half-removed.
            LOG.log(System.Logger.Level.ERROR, getLogMessage(this, "刪除角色失敗，cid " + cid), e);
        }
        return 1;
    }

    public final byte getGender() {
        return gender;
    }

    public final void setGender(final byte gender) {
        this.gender = gender;
    }

    public final String getSecondPassword() {
        return secondPassword;
    }

    public final void setSecondPassword(final String secondPassword) {
        this.secondPassword = secondPassword;
    }

    public final String getAccountName() {
        return accountName;
    }

    public final void setAccountName(final String accountName) {
        this.accountName = accountName;
    }

    public final void setChannel(final int channel) {
        this.channel = channel;
    }

    public final int getWorld() {
        return world;
    }

    public final void setWorld(final int world) {
        this.world = world;
    }

    public final int getLatency() {
        return (int) (lastPong - lastPing);
    }

    public final long getLastPong() {
        return lastPong;
    }

    public final long getLastPing() {
        return lastPing;
    }

    public final void pongReceived() {
        lastPong = System.currentTimeMillis();
    }

    public final void sendPing() {
        lastPing = System.currentTimeMillis();
        session.write(LoginPacket.getPing());

        PingTimer.getInstance().schedule(() -> {
            try {
                if (getLatency() < 0) {
                    if (getSession().isConnected()) {
                        getSession().close();
                    }
                }
            } catch (final NullPointerException e) {
                // client already gone
            }
        }, 15000); // note: idletime gets added to this too
    }

    public static final String getLogMessage(final MapleClient cfor, final String message) {
        return getLogMessage(cfor, message, new Object[0]);
    }

    public static final String getLogMessage(final MapleCharacter cfor, final String message) {
        return getLogMessage(cfor == null ? null : cfor.getClient(), message);
    }

    public static final String getLogMessage(final MapleCharacter cfor, final String message, final Object... parms) {
        return getLogMessage(cfor == null ? null : cfor.getClient(), message, parms);
    }

    public static final String getLogMessage(final MapleClient cfor, final String message, final Object... parms) {
        final StringBuilder builder = new StringBuilder();
        if (cfor != null) {
            if (cfor.getPlayer() != null) {
                builder.append("<");
                builder.append(MapleCharacterUtil.makeMapleReadable(cfor.getPlayer().getName()));
                builder.append(" (cid: ");
                builder.append(cfor.getPlayer().getId());
                builder.append(")> ");
            }
            if (cfor.getAccountName() != null) {
                builder.append("(Account: ");
                builder.append(cfor.getAccountName());
                builder.append(") ");
            }
        }
        builder.append(message);
        int start;
        for (final Object parm : parms) {
            start = builder.indexOf("{}");
            builder.replace(start, start + 2, parm.toString());
        }
        return builder.toString();
    }

    public static final int findAccIdForCharacterName(final String charName) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT accountid FROM characters WHERE name = ?")) {
            ps.setString(1, charName);

            int ret = -1;
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ret = rs.getInt("accountid");
                }
            }

            return ret;
        } catch (final SQLException e) {
            // ERROR: abandoned, and -1 comes back as "no such character". Kept
            // as terse as it was - it never reported the exception at all.
            LOG.log(System.Logger.Level.ERROR, "findAccIdForCharacterName SQL error");
        }
        return -1;
    }

    public final Set<String> getMacs() {
        return Collections.unmodifiableSet(macs);
    }

    public final boolean isGm() {
        return gm;
    }

    public final void setScriptScope(final String name, final ScriptScope e) {
        engines.put(name, e);
    }

    public final ScriptScope getScriptScope(final String name) {
        return engines.get(name);
    }

    public final void removeScriptScope(final String name) {
        engines.remove(name);
    }

    public final ScheduledFuture<?> getIdleTask() {
        return idleTask;
    }

    public final void setIdleTask(final ScheduledFuture<?> idleTask) {
        this.idleTask = idleTask;
    }

    protected static final class CharNameAndId {

        public final String name;
        public final int id;

        public CharNameAndId(final String name, final int id) {
            super();
            this.name = name;
            this.id = id;
        }
    }

    public int getCharacterSlots() {
        if (isGm()) {
            return 15;
        }
        if (charslots != DEFAULT_CHARSLOT) {
            return charslots; //save a sql
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM character_slots WHERE accid = ? AND worldid = ?")) {
            ps.setInt(1, accId);
            ps.setInt(2, world);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    charslots = rs.getInt("charslots");
                } else {
                    try (PreparedStatement psu = con.prepareStatement("INSERT INTO character_slots (accid, worldid, charslots) VALUES (?, ?, ?)")) {
                        psu.setInt(1, accId);
                        psu.setInt(2, world);
                        psu.setInt(3, charslots);
                        psu.executeUpdate();
                    }
                }
            }
        } catch (SQLException sqlE) {
            // ERROR: abandoned, so the default slot count is used and, on the
            // INSERT branch, no character_slots row exists for this account.
            LOG.log(System.Logger.Level.ERROR, "讀取角色欄位數失敗，帳號 " + accId, sqlE);
        }

        return charslots;
    }

    public boolean gainCharacterSlot() {
        if (getCharacterSlots() >= 15) {
            return false;
        }
        charslots++;

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE character_slots SET charslots = ? WHERE worldid = ? AND accid = ?")) {
            ps.setInt(1, charslots);
            ps.setInt(2, world);
            ps.setInt(3, accId);
            ps.executeUpdate();
        } catch (SQLException sqlE) {
            // ERROR: charslots was already incremented in memory but the row was
            // not written, so the extra slot is gone at the next login.
            LOG.log(System.Logger.Level.ERROR, "增加角色欄位失敗，帳號 " + accId, sqlE);
            return false;
        }
        return true;
    }

    public static final byte unbanIPMacs(String charname) {
        try (Connection con = DatabaseConnection.getConnection()) {
            final int accid;
            try (PreparedStatement ps = con.prepareStatement("SELECT accountid from characters where name = ?")) {
                ps.setString(1, charname);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return -1;
                    }
                    accid = rs.getInt(1);
                }
            }

            final String sessionIP;
            final String macs;
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM accounts WHERE id = ?")) {
                ps.setInt(1, accid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return -1;
                    }
                    sessionIP = rs.getString("sessionIP");
                    macs = rs.getString("macs");
                }
            }
            byte ret = 0;
            if (sessionIP != null) {
                try (PreparedStatement psa = con.prepareStatement("DELETE FROM ipbans WHERE ip = ?")) {
                    psa.setString(1, sessionIP);
                    psa.execute();
                }
                ret++;
            }
            if (macs != null) {
                String[] macz = macs.split(", ");
                for (String mac : macz) {
                    if (!mac.equals("")) {
                        try (PreparedStatement psa = con.prepareStatement("DELETE FROM macbans WHERE mac = ?")) {
                            psa.setString(1, mac);
                            psa.execute();
                        }
                    }
                }
                ret++;
            }
            return ret;
        } catch (SQLException e) {
            // ERROR: abandoned part-way, so the IP or MAC bans may be only
            // half-removed. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error while unbanning" + e);
            return -2;
        }
    }

    public static final byte unHellban(String charname) {
        try (Connection con = DatabaseConnection.getConnection()) {
            final int accid;
            try (PreparedStatement ps = con.prepareStatement("SELECT accountid from characters where name = ?")) {
                ps.setString(1, charname);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return -1;
                    }
                    accid = rs.getInt(1);
                }
            }

            final String sessionIP;
            final String email;
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM accounts WHERE id = ?")) {
                ps.setInt(1, accid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return -1;
                    }
                    sessionIP = rs.getString("sessionIP");
                    email = rs.getString("email");
                }
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET banned = 0, banreason = '' WHERE email = ?" + (sessionIP == null ? "" : " OR sessionIP = ?"))) {
                ps.setString(1, email);
                if (sessionIP != null) {
                    ps.setString(2, sessionIP);
                }
                ps.execute();
            }
            return 0;
        } catch (SQLException e) {
            // ERROR: the hellban was not lifted. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error while unbanning" + e);
            return -2;
        }
    }

    public boolean isMonitored() {
        return monitored;
    }

    public void setMonitored(boolean m) {
        this.monitored = m;
    }

    public boolean isReceiving() {
        return receiving;
    }

    public void setReceiving(boolean m) {
        this.receiving = m;
    }
}
