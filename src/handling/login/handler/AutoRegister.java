package handling.login.handler;

import client.LoginCrypto;
import database.DatabaseConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class AutoRegister {

    private static final int ACCOUNTS_PER_IP = 1;

    public static boolean getAccountExists(String login) {
        boolean accountExists = false;
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT name FROM accounts WHERE name = ?", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
            ps.setString(1, login);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.first()) {
                    accountExists = true;
                }
            }
        } catch (SQLException ex) {
            System.out.println(ex);
        }
        return accountExists;
    }

    /**
     * Registers {@code login}, unless the requesting address already holds
     * {@link #ACCOUNTS_PER_IP} accounts.
     *
     * The answer is returned rather than parked in a public static field, which
     * is how this used to report itself. Two clients auto-registering at the same
     * moment run on two Maple-Handler threads, so each could read the other's
     * result - and a caller that saw a stale true would then treat an account
     * that was never created as if it had been.
     *
     * @return true only when a row was actually inserted.
     */
    public static boolean createAccount(String login, String pwd, String eip) {
        String sockAddr = eip;

        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ipc = con.prepareStatement("SELECT SessionIP FROM accounts WHERE SessionIP = ?", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
            ipc.setString(1, sockAddr.substring(1, sockAddr.lastIndexOf(':')));
            try (ResultSet rs = ipc.executeQuery()) {
                if (!(rs.first() == false || rs.last() == true && rs.getRow() < ACCOUNTS_PER_IP)) {
                    return false;
                }
            }
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO accounts (name, password, salt, email, birthday, macs, SessionIP) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                // A new account is created with the salted SHA-512 straight away.
                // This used to store an unsalted SHA-1, so every auto-registered
                // account sat in the weakest of the three schemes until its owner
                // next logged in.
                final String salt = LoginCrypto.makeSalt();
                ps.setString(1, login);
                ps.setString(2, LoginCrypto.makeSaltedSha512Hash(pwd, salt));
                ps.setString(3, salt);
                ps.setString(4, "autoregister@mail.com");
                ps.setString(5, "2008-04-07");
                ps.setString(6, "00-00-00-00-00-00");
                ps.setString(7, sockAddr.substring(1, sockAddr.lastIndexOf(':')));
                ps.executeUpdate();
            }
            return true;
        } catch (SQLException ex) {
            System.out.println(ex);
            return false;
        }
    }
}
