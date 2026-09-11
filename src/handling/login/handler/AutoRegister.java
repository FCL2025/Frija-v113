package handling.login.handler;

import client.LoginCrypto;
import database.DatabaseConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class AutoRegister {

    private static final int ACCOUNTS_PER_IP = 1;
    public static boolean autoRegister = true;
    public static boolean success = false;

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

    public static void createAccount(String login, String pwd, String eip) {
        String sockAddr = eip;
        final Connection con;

        try {
            con = DatabaseConnection.getConnection();
        } catch (Exception ex) {
            System.out.println(ex);
            return;
        }

        try (con) {
            ResultSet rs;
            PreparedStatement ipc = con.prepareStatement("SELECT SessionIP FROM accounts WHERE SessionIP = ?", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);

            ipc.setString(1, sockAddr.substring(1, sockAddr.lastIndexOf(':')));
            rs = ipc.executeQuery();
            if (rs.first() == false || rs.last() == true && rs.getRow() < ACCOUNTS_PER_IP) {

                PreparedStatement ps = con.prepareStatement("INSERT INTO accounts (name, password, email, birthday, macs, SessionIP) VALUES (?, ?, ?, ?, ?, ?)");/*) {*/

                ps.setString(1, login);
                ps.setString(2, LoginCrypto.hexSha1(pwd));
                ps.setString(3, "autoregister@mail.com");
                ps.setString(4, "2008-04-07");
                ps.setString(5, "00-00-00-00-00-00");
                ps.setString(6, sockAddr.substring(1, sockAddr.lastIndexOf(':')));
                ps.executeUpdate();
            }

            success = true;
        } catch (SQLException ex) {
            System.out.println(ex);
            return;
        }
        /*                }
         }
         rs.close();
         } catch (SQLException ex) {
         System.out.println(ex);
         }*/
    }
}
