package constants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import client.inventory.MapleInventoryType;
import client.inventory.MapleWeaponType;

/**
 * The pure id-range predicates out of ItemConstants.
 *
 * <p>Only the functions that are arithmetic on an id are covered. Everything in
 * that class which consults {@code Randomizer}, a WZ-backed provider or the
 * calendar is deliberately left alone - a test that has to be told what random
 * number to expect is testing the test.
 */
class ItemConstantsTest {

    @Test
    @DisplayName("getInventoryType maps the leading digit of an item id")
    void inventoryType() {
        assertEquals(MapleInventoryType.EQUIP, ItemConstants.getInventoryType(1302000));
        assertEquals(MapleInventoryType.USE, ItemConstants.getInventoryType(2000000));
        assertEquals(MapleInventoryType.SETUP, ItemConstants.getInventoryType(3010000));
        assertEquals(MapleInventoryType.ETC, ItemConstants.getInventoryType(4000000));
        assertEquals(MapleInventoryType.CASH, ItemConstants.getInventoryType(5000000));
        assertEquals(MapleInventoryType.UNDEFINED, ItemConstants.getInventoryType(9000000));
        assertEquals(MapleInventoryType.UNDEFINED, ItemConstants.getInventoryType(0));
    }

    @Test
    @DisplayName("isEquip / isWeapon / isShield")
    void equipmentRanges() {
        assertTrue(ItemConstants.isEquip(1302000));
        assertFalse(ItemConstants.isEquip(2000000));

        assertTrue(ItemConstants.isWeapon(1302000));
        assertTrue(ItemConstants.isWeapon(1300000));
        assertFalse(ItemConstants.isWeapon(1299999));
        assertFalse(ItemConstants.isWeapon(1500000), "1500000 and up are pets/mounts, not weapons");

        assertTrue(ItemConstants.isShield(1092000));
        assertFalse(ItemConstants.isShield(1302000));
    }

    @Test
    @DisplayName("getWeaponType reads the weapon category out of the id")
    void weaponType() {
        assertEquals(MapleWeaponType.SWORD1H, ItemConstants.getWeaponType(1302000));
        assertEquals(MapleWeaponType.NOT_A_WEAPON, ItemConstants.getWeaponType(2000000));
        assertTrue(ItemConstants.isTwoHanded(1402000), "1402xxx is a two-handed sword");
        assertFalse(ItemConstants.isTwoHanded(1302000));
    }

    @Test
    @DisplayName("the throwing star / bullet / rechargable family")
    void rechargables() {
        assertTrue(ItemConstants.isThrowingStar(2070000));
        assertFalse(ItemConstants.isThrowingStar(2060000));
        assertTrue(ItemConstants.isBullet(2330000));
        assertFalse(ItemConstants.isBullet(2340000));

        assertTrue(ItemConstants.isRechargable(2070000));
        assertTrue(ItemConstants.isRechargable(2330000));
        assertFalse(ItemConstants.isRechargable(2000000));
    }

    @Test
    @DisplayName("bow and crossbow arrows sit in adjacent 1000-wide bands")
    void arrows() {
        assertTrue(ItemConstants.isArrowForBow(2060000));
        assertTrue(ItemConstants.isArrowForBow(2060999));
        assertFalse(ItemConstants.isArrowForBow(2061000));

        assertTrue(ItemConstants.isArrowForCrossBow(2061000));
        assertTrue(ItemConstants.isArrowForCrossBow(2061999));
        assertFalse(ItemConstants.isArrowForCrossBow(2062000));
    }

    @Test
    @DisplayName("scroll families are distinguished by the id / 100 prefix")
    void scrollFamilies() {
        assertTrue(ItemConstants.isCleanSlate(2049000));
        assertTrue(ItemConstants.isAccessoryScroll(2049200));
        assertTrue(ItemConstants.isEquipScroll(2049300));
        assertTrue(ItemConstants.isPotentialScroll(2049400));

        assertTrue(ItemConstants.isUpgradeScroll(2040000));
        assertTrue(ItemConstants.isUpgradeScroll(2049999));
        assertFalse(ItemConstants.isUpgradeScroll(2050000));

        assertTrue(ItemConstants.isTownScroll(2030000));
        assertFalse(ItemConstants.isTownScroll(2040000));
    }

    @Test
    @DisplayName("isChaosScroll carves 2049105..2049110 out of the 20491xx band")
    void chaosScrollHasAHole() {
        assertTrue(ItemConstants.isChaosScroll(2049100));
        assertFalse(ItemConstants.isChaosScroll(2049105));
        assertFalse(ItemConstants.isChaosScroll(2049110));
        assertTrue(ItemConstants.isChaosScroll(2049111));
        assertFalse(ItemConstants.isChaosScroll(2049200));

        assertEquals(10, ItemConstants.getChaosNumber(2049116));
        assertEquals(5, ItemConstants.getChaosNumber(2049100));
    }

    @Test
    @DisplayName("monster cards")
    void monsterCards() {
        assertTrue(ItemConstants.isMonsterCard(2380000));
        assertFalse(ItemConstants.isMonsterCard(2370000));
        assertTrue(ItemConstants.isSpecialCard(2388000));
        assertFalse(ItemConstants.isSpecialCard(2387999));
        assertEquals(8123, ItemConstants.getCardShortId(2388123));
    }

    @Test
    @DisplayName("rings and pets")
    void ringsAndPets() {
        assertTrue(ItemConstants.isRing(1112000));
        assertTrue(ItemConstants.isRing(1112999));
        assertFalse(ItemConstants.isRing(1113000), "113xxxx is belts");

        assertTrue(ItemConstants.isPet(5000000));
        assertFalse(ItemConstants.isPet(5010000));
    }
}
