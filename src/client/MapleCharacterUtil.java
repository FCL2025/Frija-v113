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

import constants.GameConstants;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Connection;
import tools.Pair;
import java.util.regex.Pattern;

import database.DatabaseConnection;

public class MapleCharacterUtil {

    private static final System.Logger LOG = System.getLogger(MapleCharacterUtil.class.getName());

    private static final Pattern namePattern = Pattern.compile("[a-zA-Z0-9_-]{3,12}");
    private static final Pattern petPattern = Pattern.compile("[a-zA-Z0-9_-]{4,12}");

    public static final boolean canCreateChar(final String name) {
        if (getIdByName(name) != -1 || !isEligibleCharName(name)) {
            return false;
        }
        return true;
    }

    public static final boolean isEligibleCharName(final String name) {
        if (name.length() > 15) {
            return false;
        }
        if (name.length() < 2) {
            return false;
        }
        for (String z : GameConstants.RESERVED) {
            if (name.indexOf(z) != -1) {
                return false;
            }
        }
        return true;
    }

    public static final boolean canChangePetName(final String name) {
        if (petPattern.matcher(name).matches()) {
            for (String z : GameConstants.RESERVED) {
                if (name.indexOf(z) != -1) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public static final String makeMapleReadable(final String in) {
        String wui = in.replace('I', 'i');
        wui = wui.replace('l', 'L');
        wui = wui.replace("rn", "Rn");
        wui = wui.replace("vv", "Vv");
        wui = wui.replace("VV", "Vv");
        return wui;
    }

    public static final int getIdByName(final String name) {
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT id FROM characters WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                return rs.getInt("id");
            }
        } catch (SQLException e) {
            // ERROR: the lookup is abandoned and -1 comes back, which the
            // callers read as "no such character". Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "error 'getIdByName' " + e);
        }
        return -1;
    }

    public static final boolean promptPoll(final int accountid) {
        boolean prompt = false;
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT * from game_poll_reply where AccountId = ?")) {
            ps.setInt(1, accountid);

            try (ResultSet rs = ps.executeQuery()) {
                prompt = rs.next() ? false : true;
            }
        } catch (SQLException e) {
            // ERROR: abandoned, and the caller is told not to prompt. Terse.
            LOG.log(System.Logger.Level.ERROR, "error 'PromptPoll' " + e);
        }
        return prompt;
    }

    public static final boolean setPoll(final int accountid, final int selection) {
        if (!promptPoll(accountid)) { // Hacking OR spamming the db.
            return false;
        }

        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("INSERT INTO game_poll_reply (AccountId, SelectAns) VALUES (?, ?)")) {
            ps.setInt(1, accountid);
            ps.setInt(2, selection);

            ps.execute();
        } catch (SQLException e) {
            // ERROR: the reply row was never inserted, yet true is returned
            // below - this line is the only trace of the lost answer. Terse.
            LOG.log(System.Logger.Level.ERROR, "error 'SetPoll' " + e);
        }
        return true;
    }

    // -2 = An unknown error occured
    // -1 = Account not found on database
    // 0 = You do not have a second password set currently.
    // 1 = The password you have input is wrong
    // 2 = Password Changed successfully
    public static final int change_SecondPassword(final int accid, final String password, final String newpassword) {
        try (Connection con = DatabaseConnection.getConnection()) {
            String secondPassword;
            final String salt2;
            try (PreparedStatement ps = con.prepareStatement("SELECT * from accounts where id = ?")) {
                ps.setInt(1, accid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return -1;
                    }
                    secondPassword = rs.getString("2ndpassword");
                    salt2 = rs.getString("salt2");
                }
            }
            if (secondPassword != null && salt2 != null) {
                secondPassword = LoginCrypto.rand_r(secondPassword);
            } else if (secondPassword == null && salt2 == null) {
                return 0;
            }
            if (!check_ifPasswordEquals(secondPassword, password, salt2)) {
                return 1;
            }

            // Store the new second password the same way MapleClient does:
            // a salted SHA-512, wrapped by rand_s so rand_r can peel it back off
            // on read. This used to write a bare legacy SHA-1 with a null salt2,
            // which silently downgraded the account every time the player changed
            // their second password.
            final String newSalt = LoginCrypto.makeSalt();
            final String hashedsecond = LoginCrypto.rand_s(LoginCrypto.makeSaltedSha512Hash(newpassword, newSalt));
            try (PreparedStatement ps = con.prepareStatement("UPDATE accounts set 2ndpassword = ?, salt2 = ? where id = ?")) {
                ps.setString(1, hashedsecond);
                ps.setString(2, newSalt);
                ps.setInt(3, accid);

                if (!ps.execute()) {
                    return 2;
                }
                return -2;
            }
        } catch (SQLException e) {
            // ERROR: the second password was not changed (-2 = unknown error).
            // The "getIdByName" in the text is a copy-paste that predates this
            // method; kept verbatim so the line stays greppable. Terse.
            LOG.log(System.Logger.Level.ERROR, "error 'getIdByName' " + e);
            return -2;
        }
    }

    private static final boolean check_ifPasswordEquals(final String passhash, final String pwd, final String salt) {
        // Check if the passwords are correct here. :B
        if (passhash == null || pwd == null) {
            return false;
        }
        if (passhash.length() >= 3 && LoginCryptoLegacy.isLegacyPassword(passhash) && LoginCryptoLegacy.checkPassword(pwd, passhash)) {
            return true;
        }
        if (salt == null) {
            return LoginCrypto.checkSha1Hash(passhash, pwd);
        }
        // Guarded on salt != null: the salted check used to run with a null salt
        // too, which hashed the literal string "null" onto the end of the password.
        return LoginCrypto.checkSaltedSha512Hash(passhash, pwd, salt);
    }

    //id accountid gender
    public static Pair<Integer, Pair<Integer, Integer>> getInfoByName(String name, int world) {
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT * FROM characters WHERE name = ? AND world = ?")) {
            ps.setString(1, name);
            ps.setInt(2, world);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Pair<>(rs.getInt("id"), new Pair<>(rs.getInt("accountid"), rs.getInt("gender")));
            }
        } catch (Exception e) {
            // ERROR: abandoned, and null comes back as "no such character".
            LOG.log(System.Logger.Level.ERROR, "error 'getInfoByName' " + name, e);
        }
        return null;
    }

    public static void setNXCodeUsed(String name, String code) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("UPDATE nxcode SET `user` = ?, `valid` = 0 WHERE code = ?")) {
            ps.setString(1, name);
            ps.setString(2, code);
            ps.execute();
        }
    }

    public static void sendNote(String to, String name, String msg, int fame) {
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("INSERT INTO notes (`to`, `from`, `message`, `timestamp`, `gift`) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, to);
            ps.setString(2, name);
            ps.setString(3, msg);
            ps.setLong(4, System.currentTimeMillis());
            ps.setInt(5, fame);
            ps.executeUpdate();
        } catch (SQLException e) {
            // ERROR: the note row was not written, so the note is simply lost
            // - the sender is told nothing. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Unable to send note" + e);
        }
    }

    public static boolean getNXCodeValid(String code, boolean validcode) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT `valid` FROM nxcode WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    validcode = rs.getInt("valid") > 0;
                }
            }
        }
        return validcode;
    }

    public static int getNXCodeType(String code) throws SQLException {
        int type = -1;
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT `type` FROM nxcode WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    type = rs.getInt("type");
                }
            }
        }
        return type;
    }

    public static int getNXCodeItem(String code) throws SQLException {
        int item = -1;
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT `item` FROM nxcode WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    item = rs.getInt("item");
                }
            }
        }
        return item;
    }
}
