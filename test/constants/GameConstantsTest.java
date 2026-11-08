package constants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure functions left in GameConstants after the item / job / map split.
 *
 * <p>Only the functions that are arithmetic on a number are covered: the two
 * tax schedules, the level exp table, the monster-book level formula and the
 * custom-quest split. {@link #derivedLevelFormulas()} also pins the two
 * equipment-exp formulas that moved to {@code ItemConstants}, because the three
 * of them are one family of one-line formulas and splitting the assertions
 * apart would have changed them.
 */
class GameConstantsTest {

    @Test
    @DisplayName("getTaxAmount steps through six brackets")
    void taxBrackets() {
        assertEquals(0, GameConstants.getTaxAmount(99_999));
        assertEquals(800, GameConstants.getTaxAmount(100_000));
        assertEquals(18_000, GameConstants.getTaxAmount(1_000_000));
        assertEquals(150_000, GameConstants.getTaxAmount(5_000_000));
        assertEquals(400_000, GameConstants.getTaxAmount(10_000_000));
        assertEquals(1_250_000, GameConstants.getTaxAmount(25_000_000));
        assertEquals(6_000_000, GameConstants.getTaxAmount(100_000_000));
    }

    @Test
    @DisplayName("EntrustedStoreTax is the cheaper schedule")
    void entrustedStoreTax() {
        assertEquals(0, GameConstants.entrustedStoreTax(99_999));
        assertEquals(400, GameConstants.entrustedStoreTax(100_000));
        assertEquals(3_000_000, GameConstants.entrustedStoreTax(100_000_000));
        for (final int meso : new int[]{100_000, 1_000_000, 5_000_000, 10_000_000, 25_000_000, 100_000_000}) {
            assertTrue(GameConstants.entrustedStoreTax(meso) < GameConstants.getTaxAmount(meso),
                    "the entrusted-store rate should undercut the trade rate at " + meso);
        }
    }

    @Test
    @DisplayName("getExpNeededForLevel is bounded at both ends")
    void expTable() {
        assertEquals(0, GameConstants.getExpNeededForLevel(0));
        assertEquals(15, GameConstants.getExpNeededForLevel(1));
        assertEquals(Integer.MAX_VALUE, GameConstants.getExpNeededForLevel(-1));
        assertEquals(Integer.MAX_VALUE, GameConstants.getExpNeededForLevel(10_000));

        int previous = -1;
        for (int level = 0; level <= 200; level++) {
            final int exp = GameConstants.getExpNeededForLevel(level);
            if (exp == Integer.MAX_VALUE) {
                break;
            }
            assertTrue(exp > previous, "the exp table must increase, but level " + level + " did not");
            previous = exp;
        }
    }

    @Test
    @DisplayName("the derived level formulas")
    void derivedLevelFormulas() {
        assertEquals(0, GameConstants.getBookLevel(0));
        assertEquals(10, GameConstants.getBookLevel(1));
        assertEquals(30, GameConstants.getBookLevel(2));
        assertEquals(80, ItemConstants.getTimelessRequiredEXP(1));
        assertEquals(65, ItemConstants.getReverseRequiredEXP(1));
    }

    @Test
    @DisplayName("isCustomQuest splits the WZ quest ids from the server's own")
    void customQuests() {
        assertFalse(GameConstants.isCustomQuest(99_999));
        assertTrue(GameConstants.isCustomQuest(100_000));
    }
}
