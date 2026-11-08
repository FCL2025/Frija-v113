package server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of {@link LiveSettings} that do not need a running server: how
 * each key is classified, what a value must look like, and how a re-read
 * {@code Settings.ini} is compared with what is running. Applying is not
 * reachable from here - it touches channels, the pool and ServerProperties,
 * whose static initialiser calls {@code System.exit}.
 */
class LiveSettingsTest {

    @Test
    @DisplayName("tms.Url is the only key the code reads that still needs a restart")
    void onlyUrlNeedsRestart() {
        assertEquals(LiveSettings.Mode.RESTART, LiveSettings.mode("tms.Url"));
        for (final String key : List.of("tms.User", "tms.Pass", "tms.Count", "tms.Exp", "tms.ServerName",
                "tms.Events", "tms.WFlags", "tms.Admin", "tms.IP", "tms.Debug", "tms.HandlerThreads",
                "tms.TimerThreads", "tms.DbPoolSize", "tms.DbPoolTimeout", "tms.PanelPort", "tms.PanelThreads",
                "tms.PanelToken", "tms.AutoSaveMinutes", "tms.Port1", "tms.Port12")) {
            assertEquals(LiveSettings.Mode.LIVE, LiveSettings.mode(key), key);
        }
    }

    @Test
    @DisplayName("keys nothing reads are 'none', not 'restart'; unknown keys are 'restart' and unknown")
    void noneAndUnknown() {
        for (final String key : List.of("tms.MaxCharacters", "tms.Port", "tms.LPort", "tms.Packet")) {
            assertEquals(LiveSettings.Mode.NONE, LiveSettings.mode(key), key);
        }
        assertFalse(LiveSettings.isKnown("tms.SomethingElse"));
        assertEquals(LiveSettings.Mode.RESTART, LiveSettings.mode("tms.SomethingElse"));
        assertTrue(LiveSettings.isKnown("tms.Port7"));
    }

    @Test
    @DisplayName("validation mirrors how the running server parses each key")
    void validation() {
        assertDoesNotThrow(() -> LiveSettings.validate("tms.Exp", "5"));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.validate("tms.Exp", "0"));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.validate("tms.Exp", "abc"));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.validate("tms.Exp", ""));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.validate("tms.Count", "0"));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.validate("tms.Count", ""));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.validate("tms.Flag", "4"));
        // Optional keys: blank means "the default".
        assertDoesNotThrow(() -> LiveSettings.validate("tms.DbPoolSize", ""));
        assertDoesNotThrow(() -> LiveSettings.validate("tms.Port3", ""));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.validate("tms.Port3", "70000"));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.validate("tms.Debug", "yes"));
        assertDoesNotThrow(() -> LiveSettings.validate("tms.Debug", "TRUE"));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.validate("tms.PanelToken", "a b"));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.validate("tms.ServerName", "a\nb"));
    }

    @Test
    @DisplayName("changedKeys compares trimmed values and treats a missing side as blank")
    void changedKeys() {
        final Map<String, String> file = new HashMap<>();
        file.put("tms.Exp", "2");
        file.put("tms.Meso", "1");
        file.put("tms.ServerName", "新名字");
        file.put("﻿#Database", "Setting"); // the BOM-glued comment Properties.load produces
        final Map<String, String> running = new HashMap<>();
        running.put("tms.Exp", "1");
        running.put("tms.Meso", "1   "); // trailing blanks Properties.load keeps
        running.put("tms.ServerName", "舊名字");
        running.put("tms.DbPoolSize", "128"); // deleted from the file
        running.put("net.sf.odinms.channel.net.port2", "8587"); // a database row, not a setting

        final List<String> changed = LiveSettings.changedKeys(file, running::get, running.keySet());
        assertEquals(List.of("tms.DbPoolSize", "tms.Exp", "tms.ServerName"), changed);
    }

    @Test
    @DisplayName("changedKeys orders credentials first, channel ports before events, tms.Count last")
    void applyOrder() {
        final Map<String, String> file = Map.of(
                "tms.Count", "4", "tms.Events", "Boats", "tms.Port4", "9000", "tms.Pass", "x", "tms.Exp", "3");
        final List<String> changed = LiveSettings.changedKeys(file, k -> null, List.of());
        assertEquals(List.of("tms.Pass", "tms.Exp", "tms.Port4", "tms.Events", "tms.Count"), changed);
    }

    @Test
    @DisplayName("secrets are never echoed in an outcome summary")
    void secretsNotEchoed() {
        final String text = LiveSettings.describe(List.of(
                new LiveSettings.Outcome("tms.Pass", null, true, LiveSettings.Mode.LIVE, "ok"),
                new LiveSettings.Outcome("tms.Exp", "2", true, LiveSettings.Mode.LIVE, null)));
        assertTrue(text.contains("tms.Pass → 已套用"));
        assertTrue(text.contains("tms.Exp = 2 → 已套用"));
        assertTrue(LiveSettings.isSecret("tms.Pass"));
        assertTrue(LiveSettings.isSecret("tms.PanelToken"));
    }
}
