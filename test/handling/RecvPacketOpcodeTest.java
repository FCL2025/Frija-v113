package handling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The inbound opcode table, which is data rather than code: the numbers come
 * from {@code Libs/opcode/recv.ini} at class-init time via
 * {@link ExternalCodeTableGetter}, and {@code !reloadops} rebuilds them.
 *
 * <p>This is the one test class here that touches the filesystem. The path is
 * relative, so the working directory has to be the repo root - which it is under
 * {@code Launcher.bat test}, and is the only way the server itself ever runs. If
 * it is not, every test below is skipped rather than failed: a wrong working
 * directory is a harness problem, not a regression.
 */
class RecvPacketOpcodeTest {

    private static final Path RECV_INI = Path.of("Libs", "opcode", "recv.ini");

    @BeforeAll
    static void requireTheOpcodeTable() {
        assumeTrue(Files.isReadable(RECV_INI),
                () -> "needs " + RECV_INI.toAbsolutePath() + " - run the tests from the repo root");
    }

    @Test
    @DisplayName("the ini is loaded at class-init, so the codes are not left at the -2 default")
    void theTableIsPopulated() {
        int assigned = 0;
        for (final RecvPacketOpcode op : RecvPacketOpcode.values()) {
            if (op.getValue() != -2) {
                assigned++;
            }
        }
        assertTrue(assigned > 100, "only " + assigned + " of " + RecvPacketOpcode.values().length + " opcodes got a value");
    }

    @Test
    @DisplayName("the values match what Properties reads out of recv.ini")
    void valuesMatchTheIni() throws IOException {
        final Properties props = RecvPacketOpcode.getDefaultProperties();
        assertFalse(props.isEmpty());

        int checked = 0;
        for (final RecvPacketOpcode op : RecvPacketOpcode.values()) {
            final String raw = props.getProperty(op.name());
            if (raw == null || !raw.trim().matches("0x[0-9A-Fa-f]{1,4}|\\d{1,5}")) {
                // Skip the "BASE +offset" form; getValue() resolves those itself and
                // re-implementing that here would only test the test.
                continue;
            }
            final String trimmed = raw.trim();
            final short expected = trimmed.startsWith("0x")
                    ? (short) Integer.parseInt(trimmed.substring(2), 16)
                    : (short) Integer.parseInt(trimmed);
            assertEquals(expected, op.getValue(), op.name() + " disagrees with recv.ini");
            checked++;
        }
        assertTrue(checked > 100, "only " + checked + " opcodes were checked against the ini");
    }

    @Test
    @DisplayName("forValue resolves the opcodes the login and channel handlers dispatch on")
    void forValueResolvesKnownOpcodes() {
        for (final RecvPacketOpcode op : new RecvPacketOpcode[]{
            RecvPacketOpcode.PONG,
            RecvPacketOpcode.LOGIN_PASSWORD,
            RecvPacketOpcode.CHARLIST_REQUEST,
            RecvPacketOpcode.CHAR_SELECT,
            RecvPacketOpcode.PLAYER_LOGGEDIN,
            RecvPacketOpcode.CHANGE_MAP,
            RecvPacketOpcode.GENERAL_CHAT,
            RecvPacketOpcode.NPC_TALK}) {
            assertTrue(op.getValue() != -2, op + " has no value in recv.ini");
            assertSame(op, RecvPacketOpcode.forValue(op.getValue()),
                    op + " (0x" + Integer.toHexString(op.getValue() & 0xFFFF) + ") does not resolve back to itself");
        }
    }

    @Test
    @DisplayName("forValue answers null for a header no opcode claims")
    void forValueIsNullForAnUnknownHeader() {
        final Map<Short, RecvPacketOpcode> claimed = new HashMap<>();
        for (final RecvPacketOpcode op : RecvPacketOpcode.values()) {
            claimed.putIfAbsent(op.getValue(), op);
        }
        short unclaimed = 0;
        while (claimed.containsKey(unclaimed)) {
            unclaimed++;
        }
        assertNull(RecvPacketOpcode.forValue(unclaimed));
    }

    @Test
    @DisplayName("forValue agrees with the linear scan it replaced: first declaration wins")
    void forValueMatchesAScanOfValues() {
        // The dispatcher used to scan values() on every packet and take the first
        // match; the lookup map must answer identically, duplicates included.
        for (final RecvPacketOpcode op : RecvPacketOpcode.values()) {
            RecvPacketOpcode firstWithThisValue = null;
            for (final RecvPacketOpcode candidate : RecvPacketOpcode.values()) {
                if (candidate.getValue() == op.getValue()) {
                    firstWithThisValue = candidate;
                    break;
                }
            }
            assertSame(firstWithThisValue, RecvPacketOpcode.forValue(op.getValue()));
        }
    }

    @Test
    @DisplayName("reloadValues() is idempotent - !reloadops must not shuffle the table")
    void reloadIsIdempotent() {
        final Map<RecvPacketOpcode, Short> before = new EnumMap<>(RecvPacketOpcode.class);
        for (final RecvPacketOpcode op : RecvPacketOpcode.values()) {
            before.put(op, op.getValue());
        }

        RecvPacketOpcode.reloadValues();

        for (final RecvPacketOpcode op : RecvPacketOpcode.values()) {
            assertEquals(before.get(op).shortValue(), op.getValue(), op + " changed across a reload");
        }
        assertSame(RecvPacketOpcode.LOGIN_PASSWORD,
                RecvPacketOpcode.forValue(RecvPacketOpcode.LOGIN_PASSWORD.getValue()),
                "the lookup map must be rebuilt, not just the values");
    }

    @Test
    @DisplayName("NeedsChecking marks the opcodes that may arrive without a logged-in player")
    void needsCheckingFlags() {
        // The false ones are the packets that legitimately arrive before there is a
        // character on the session; MapleServerHandler uses this to decide whether
        // to reject a packet outright.
        assertFalse(RecvPacketOpcode.PONG.needsChecking());
        assertFalse(RecvPacketOpcode.LOGIN_PASSWORD.needsChecking());
        assertFalse(RecvPacketOpcode.PLAYER_LOGGEDIN.needsChecking());
        assertTrue(RecvPacketOpcode.CHANGE_MAP.needsChecking());
        assertTrue(RecvPacketOpcode.GENERAL_CHAT.needsChecking());
    }

    @Test
    @DisplayName("getOpcodeTable renders every constant, sorted by value")
    void opcodeTableRenders() {
        final String table = ExternalCodeTableGetter.getOpcodeTable(RecvPacketOpcode.values());
        assertNotNull(table);
        assertEquals(RecvPacketOpcode.values().length, table.lines().count());
        assertTrue(table.contains("LOGIN_PASSWORD = 0x"), table.lines().findFirst().orElse(""));
    }
}
