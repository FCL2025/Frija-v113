package handling.login.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The address handling behind "a client on this machine may auto-register
 * any number of accounts". Getting {@code isLoopback} wrong one way lets the
 * whole internet past the one-account-per-IP limit; the other way puts the
 * limit back on the operator.
 */
class AutoRegisterTest {

    @Test
    @DisplayName("hostOf strips MINA's leading slash and the port, IPv4 and IPv6 alike")
    void hostOf() {
        assertEquals("127.0.0.1", AutoRegister.hostOf("/127.0.0.1:51234"));
        assertEquals("203.0.113.7", AutoRegister.hostOf("/203.0.113.7:8484"));
        assertEquals("0:0:0:0:0:0:0:1", AutoRegister.hostOf("/0:0:0:0:0:0:0:1:51234"));
        assertEquals("127.0.0.1", AutoRegister.hostOf("127.0.0.1:1"));
    }

    @Test
    @DisplayName("loopback: the whole of 127.0.0.0/8 and ::1")
    void loopback() {
        assertTrue(AutoRegister.isLoopback("127.0.0.1"));
        assertTrue(AutoRegister.isLoopback("127.1.2.3"));
        assertTrue(AutoRegister.isLoopback("0:0:0:0:0:0:0:1"));
        assertTrue(AutoRegister.isLoopback("::1"));
    }

    @Test
    @DisplayName("not loopback: LAN, public, and anything that is not an IP literal")
    void notLoopback() {
        assertFalse(AutoRegister.isLoopback("192.168.1.10"));
        assertFalse(AutoRegister.isLoopback("10.0.0.1"));
        assertFalse(AutoRegister.isLoopback("203.0.113.7"));
        // A host name is never resolved - ofLiteral refuses it - so it can
        // neither block a handler thread on DNS nor talk its way past the limit.
        assertFalse(AutoRegister.isLoopback("localhost"));
        assertFalse(AutoRegister.isLoopback(""));
        assertFalse(AutoRegister.isLoopback("anonymous"));
    }
}
