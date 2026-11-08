package database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The URL used for the one connection that tries new credentials before the
 * pool switches to them. Only the pure helper is reachable here: the pool
 * itself reads {@code ServerProperties}, whose static initialiser calls
 * {@code System.exit}.
 */
class DatabaseConnectionTest {

    private static final String SHIPPED = "jdbc:mysql://localhost:3306/twmsv113?autoReconnect=true&maxReconnects=999"
            + "&characterEncoding=BIG5&rewriteBatchedStatements=true";

    @Test
    @DisplayName("probeUrl drops the retry options that turn a wrong password into a 30-minute hang")
    void dropsRetryOptions() {
        final String probe = DatabaseConnection.probeUrl(SHIPPED);
        assertFalse(probe.contains("autoReconnect"));
        assertFalse(probe.contains("maxReconnects"));
        assertTrue(probe.contains("connectTimeout=5000"));
        assertTrue(probe.contains("socketTimeout=10000"));
    }

    @Test
    @DisplayName("probeUrl keeps everything else, in order, and the database it points at")
    void keepsTheRest() {
        assertEquals("jdbc:mysql://localhost:3306/twmsv113?characterEncoding=BIG5&rewriteBatchedStatements=true"
                + "&connectTimeout=5000&socketTimeout=10000", DatabaseConnection.probeUrl(SHIPPED));
    }

    @Test
    @DisplayName("probeUrl matches option names case-insensitively, as Connector/J does")
    void caseInsensitive() {
        assertEquals("jdbc:mysql://h/db?x=1&connectTimeout=5000&socketTimeout=10000",
                DatabaseConnection.probeUrl("jdbc:mysql://h/db?AUTORECONNECT=true&x=1&ConnectTimeout=999999"));
    }

    @Test
    @DisplayName("probeUrl copes with no query string and with stray ampersands")
    void edgeShapes() {
        assertEquals("jdbc:mysql://h/db?connectTimeout=5000&socketTimeout=10000",
                DatabaseConnection.probeUrl("jdbc:mysql://h/db"));
        assertEquals("jdbc:mysql://h/db?a=1&connectTimeout=5000&socketTimeout=10000",
                DatabaseConnection.probeUrl("jdbc:mysql://h/db?&a=1&&"));
    }
}
