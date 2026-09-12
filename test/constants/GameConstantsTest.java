package constants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import client.inventory.MapleInventoryType;
import client.inventory.MapleWeaponType;

/**
 * The pure id-range predicates out of the 2000-line GameConstants.
 *
 * <p>Only the functions that are arithmetic on an id are covered. Everything in
 * that class which consults {@code Randomizer}, a WZ-backed provider or the
 * calendar is deliberately left alone - a test that has to be told what random
 * number to expect is testing the test.
 */
class GameConstantsTest {

    @Test
    @DisplayName("getInventoryType maps the leading digit of an item id")
    void inventoryType() {
        assertEquals(MapleInventoryType.EQUIP, GameConstants.getInventoryType(1302000));
        assertEquals(MapleInventoryType.USE, GameConstants.getInventoryType(2000000));
        assertEquals(MapleInventoryType.SETUP, GameConstants.getInventoryType(3010000));
        assertEquals(MapleInventoryType.ETC, GameConstants.getInventoryType(4000000));
        assertEquals(MapleInventoryType.CASH, GameConstants.getInventoryType(5000000));
        assertEquals(MapleInventoryType.UNDEFINED, GameConstants.getInventoryType(9000000));
        assertEquals(MapleInventoryType.UNDEFINED, GameConstants.getInventoryType(0));
    }

    @Test
    @DisplayName("isEquip / isWeapon / isShield")
    void equipmentRanges() {
        assertTrue(GameConstants.isEquip(1302000));
        assertFalse(GameConstants.isEquip(2000000));

        assertTrue(GameConstants.isWeapon(1302000));
        assertTrue(GameConstants.isWeapon(1300000));
        assertFalse(GameConstants.isWeapon(1299999));
        assertFalse(GameConstants.isWeapon(1500000), "1500000 and up are pets/mounts, not weapons");

        assertTrue(GameConstants.isShield(1092000));
        assertFalse(GameConstants.isShield(1302000));
    }

    @Test
    @DisplayName("getWeaponType reads the weapon category out of the id")
    void weaponType() {
        assertEquals(MapleWeaponType.SWORD1H, GameConstants.getWeaponType(1302000));
        assertEquals(MapleWeaponType.NOT_A_WEAPON, GameConstants.getWeaponType(2000000));
        assertTrue(GameConstants.isTwoHanded(1402000), "1402xxx is a two-handed sword");
        assertFalse(GameConstants.isTwoHanded(1302000));
    }

    @Test
    @DisplayName("the throwing star / bullet / rechargable family")
    void rechargables() {
        assertTrue(GameConstants.isThrowingStar(2070000));
        assertFalse(GameConstants.isThrowingStar(2060000));
        assertTrue(GameConstants.isBullet(2330000));
        assertFalse(GameConstants.isBullet(2340000));

        assertTrue(GameConstants.isRechargable(2070000));
        assertTrue(GameConstants.isRechargable(2330000));
        assertFalse(GameConstants.isRechargable(2000000));
    }

    @Test
    @DisplayName("bow and crossbow arrows sit in adjacent 1000-wide bands")
    void arrows() {
        assertTrue(GameConstants.isArrowForBow(2060000));
        assertTrue(GameConstants.isArrowForBow(2060999));
        assertFalse(GameConstants.isArrowForBow(2061000));

        assertTrue(GameConstants.isArrowForCrossBow(2061000));
        assertTrue(GameConstants.isArrowForCrossBow(2061999));
        assertFalse(GameConstants.isArrowForCrossBow(2062000));
    }

    @Test
    @DisplayName("scroll families are distinguished by the id / 100 prefix")
    void scrollFamilies() {
        assertTrue(GameConstants.isCleanSlate(2049000));
        assertTrue(GameConstants.isAccessoryScroll(2049200));
        assertTrue(GameConstants.isEquipScroll(2049300));
        assertTrue(GameConstants.isPotentialScroll(2049400));

        assertTrue(GameConstants.isUpgradeScroll(2040000));
        assertTrue(GameConstants.isUpgradeScroll(2049999));
        assertFalse(GameConstants.isUpgradeScroll(2050000));

        assertTrue(GameConstants.isTownScroll(2030000));
        assertFalse(GameConstants.isTownScroll(2040000));
    }

    @Test
    @DisplayName("isChaosScroll carves 2049105..2049110 out of the 20491xx band")
    void chaosScrollHasAHole() {
        assertTrue(GameConstants.isChaosScroll(2049100));
        assertFalse(GameConstants.isChaosScroll(2049105));
        assertFalse(GameConstants.isChaosScroll(2049110));
        assertTrue(GameConstants.isChaosScroll(2049111));
        assertFalse(GameConstants.isChaosScroll(2049200));

        assertEquals(10, GameConstants.getChaosNumber(2049116));
        assertEquals(5, GameConstants.getChaosNumber(2049100));
    }

    @Test
    @DisplayName("monster cards")
    void monsterCards() {
        assertTrue(GameConstants.isMonsterCard(2380000));
        assertFalse(GameConstants.isMonsterCard(2370000));
        assertTrue(GameConstants.isSpecialCard(2388000));
        assertFalse(GameConstants.isSpecialCard(2387999));
        assertEquals(8123, GameConstants.getCardShortId(2388123));
    }

    @Test
    @DisplayName("job family predicates")
    void jobs() {
        assertTrue(GameConstants.isAdventurer(0));
        assertTrue(GameConstants.isAdventurer(510));
        assertFalse(GameConstants.isAdventurer(1000));

        assertTrue(GameConstants.isKOC(1000));
        assertTrue(GameConstants.isKOC(1512));
        assertFalse(GameConstants.isKOC(2000));

        assertTrue(GameConstants.isAran(2000));
        assertTrue(GameConstants.isAran(2112));
        assertFalse(GameConstants.isAran(2001), "2001 is the Evan beginner, not an Aran");

        assertTrue(GameConstants.isEvan(2001));
        assertTrue(GameConstants.isEvan(2218));
        assertFalse(GameConstants.isEvan(2219));

        assertTrue(GameConstants.isResist(3000));
        assertTrue(GameConstants.isResist(3512));
        assertFalse(GameConstants.isResist(2999));
    }

    @Test
    @DisplayName("isJobFamily requires the same hundred and a forwards step")
    void jobFamily() {
        assertTrue(GameConstants.isJobFamily(100, 110));
        assertTrue(GameConstants.isJobFamily(100, 100));
        assertFalse(GameConstants.isJobFamily(110, 100), "an advancement never goes backwards");
        assertFalse(GameConstants.isJobFamily(100, 200));
    }

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
        assertEquals(0, GameConstants.EntrustedStoreTax(99_999));
        assertEquals(400, GameConstants.EntrustedStoreTax(100_000));
        assertEquals(3_000_000, GameConstants.EntrustedStoreTax(100_000_000));
        for (final int meso : new int[]{100_000, 1_000_000, 5_000_000, 10_000_000, 25_000_000, 100_000_000}) {
            assertTrue(GameConstants.EntrustedStoreTax(meso) < GameConstants.getTaxAmount(meso),
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
        assertEquals(80, GameConstants.getTimelessRequiredEXP(1));
        assertEquals(65, GameConstants.getReverseRequiredEXP(1));
    }

    @Test
    @DisplayName("maxViewRangeSq is 800000, which is not what its comment says")
    void maxViewRangeSq() {
        // The source says `return 800000; // 800 * 800`, but 800 * 800 is 640000.
        // Every caller compares it against Point.distanceSq, so the view radius the
        // server actually uses is sqrt(800000) ~ 894 units, not 800. Pinned at the
        // value the code has rather than the one the comment claims: correcting it
        // would shrink what every player on every map can see, which is a gameplay
        // change and not a test's decision.
        assertEquals(800_000, GameConstants.maxViewRangeSq());
        assertEquals(640_000, 800 * 800);
    }

    @Test
    @DisplayName("isCustomQuest splits the WZ quest ids from the server's own")
    void customQuests() {
        assertFalse(GameConstants.isCustomQuest(99_999));
        assertTrue(GameConstants.isCustomQuest(100_000));
    }

    @Test
    @DisplayName("rings and pets")
    void ringsAndPets() {
        assertTrue(GameConstants.isRing(1112000));
        assertTrue(GameConstants.isRing(1112999));
        assertFalse(GameConstants.isRing(1113000), "113xxxx is belts");

        assertTrue(GameConstants.isPet(5000000));
        assertFalse(GameConstants.isPet(5010000));
    }
}
