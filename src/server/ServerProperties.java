package server;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import database.DatabaseConnection;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 *
 * @author Emilyx3
 */
public class ServerProperties {

    // Declared before the static block below, which uses it: static fields and
    // static initialisers run in the order they are written.
    private static final System.Logger LOG = System.getLogger(ServerProperties.class.getName());

    private static final Properties props = new Properties();

    private static final String[] toLoad = {
        "Settings.ini"
    };

    private ServerProperties() {
    }

    static {
        for (String s : toLoad) {
            InputStreamReader fr;
            try {
                fr = new InputStreamReader(new FileInputStream(s), "UTF-8");
                props.load(fr);
                fr.close();
            } catch (IOException ex) {
                // ERROR: this file is not read, so every tms.* key in it is
                // missing and the server runs on defaults it never announced.
                LOG.log(System.Logger.Level.ERROR, "Error reading " + s, ex);
            }
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM auth_server_channel_ip");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                props.put(rs.getString("name") + rs.getInt("channelid"), rs.getString("value"));
            }
        } catch (SQLException ex) {
            // ERROR: the line before System.exit(0), and for most startup
            // failures the only thing printed at all.
            LOG.log(System.Logger.Level.ERROR, "Error reading auth_server_channel_ip", ex);
            System.exit(0); //Big ass error.
        }

    }

    public static String getProperty(String s) {
        return props.getProperty(s);
    }

    public static void setProperty(String prop, String newInf) {
        props.setProperty(prop, newInf);
    }

    public static String getProperty(String s, String def) {
        return props.getProperty(s, def);
    }
}
