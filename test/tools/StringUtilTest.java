package tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The padding helpers, which are the single place in the tree most likely to be
 * broken by a platform default charset.
 *
 * <p>{@code getLeftPaddedStr} and {@code getRightPaddedStr} decide how many pad
 * characters to add by measuring the input in <b>BIG5 bytes</b>, because the
 * result goes on the wire to a client that lays it out in half-width cells: a
 * Chinese character occupies two. Until {@code tools.Encodings} was introduced
 * these measured with {@code String.getBytes()}, i.e. the platform default -
 * MS950 on a zh-TW box, which happens to agree with BIG5 on CJK byte counts.
 * JEP 400 made that default UTF-8 in JDK 18, where the same characters are
 * three bytes, and every padded column on the wire would have silently shifted.
 *
 * <p>These tests therefore assert byte widths, not character counts.
 */
class StringUtilTest {

    @Test
    @DisplayName("getlength measures BIG5 bytes, not characters")
    void getlengthMeasuresBig5Bytes() {
        assertEquals(0, StringUtil.getlength(""));
        assertEquals(3, StringUtil.getlength("abc"));
        assertEquals(2, StringUtil.getlength("哈"));
        assertEquals(4, StringUtil.getlength("哈哈"));
        assertEquals(6, StringUtil.getlength("楓之谷"));
        // 台 and 服 are two bytes each; the space and "v113" are one byte each.
        assertEquals(9, StringUtil.getlength("台服 v113"));
    }

    @Test
    @DisplayName("a CJK character must not be measured as three UTF-8 bytes")
    void getlengthIsNotUtf8() {
        // The regression this guards against: if getlength ever falls back to the
        // platform default under JEP 400, this is 9 rather than 6 and every padded
        // column on the wire moves.
        assertEquals(9, "楓之谷".getBytes(StandardCharsets.UTF_8).length);
        assertEquals(6, StringUtil.getlength("楓之谷"));
    }

    @Test
    @DisplayName("getLeftPaddedStr pads ASCII to the requested byte width")
    void leftPadAscii() {
        assertEquals("000042", StringUtil.getLeftPaddedStr("42", '0', 6));
        assertEquals("abc", StringUtil.getLeftPaddedStr("abc", '0', 3));
    }

    @Test
    @DisplayName("getRightPaddedStr pads ASCII to the requested byte width")
    void rightPadAscii() {
        assertEquals("42....", StringUtil.getRightPaddedStr("42", '.', 6));
        assertEquals("abc", StringUtil.getRightPaddedStr("abc", '.', 3));
    }

    @Test
    @DisplayName("a Chinese character counts as two columns, so 哈哈 gets two pad chars, not four")
    void cjkCountsAsTwoColumns() {
        final String left = StringUtil.getLeftPaddedStr("哈哈", '.', 6);
        assertEquals("..哈哈", left);
        assertEquals(4, left.length(), "two pad chars plus two Chinese characters");
        assertEquals(6, StringUtil.getlength(left), "the result is exactly six BIG5 bytes wide");

        final String right = StringUtil.getRightPaddedStr("哈哈", '.', 6);
        assertEquals("哈哈..", right);
        assertEquals(6, StringUtil.getlength(right));
    }

    @Test
    @DisplayName("mixed ASCII and Chinese is measured by total BIG5 width")
    void mixedWidth() {
        // "v1哈" is 1 + 1 + 2 = 4 BIG5 bytes, so padding to 7 adds three.
        assertEquals(4, StringUtil.getlength("v1哈"));
        assertEquals("   v1哈", StringUtil.getLeftPaddedStr("v1哈", ' ', 7));
        assertEquals(7, StringUtil.getlength(StringUtil.getLeftPaddedStr("v1哈", ' ', 7)));
    }

    @Test
    @DisplayName("an input already wider than the target is returned untouched, never truncated")
    void tooWideIsLeftAlone() {
        assertEquals("abcdef", StringUtil.getLeftPaddedStr("abcdef", '.', 3));
        assertEquals("abcdef", StringUtil.getRightPaddedStr("abcdef", '.', 3));
        assertEquals("哈哈哈", StringUtil.getRightPaddedStr("哈哈哈", '.', 3));
    }

    @Test
    @DisplayName("padding an empty string fills the whole width")
    void emptyInput() {
        assertEquals("....", StringUtil.getLeftPaddedStr("", '.', 4));
        assertEquals("....", StringUtil.getRightPaddedStr("", '.', 4));
    }

    @Test
    @DisplayName("joinStringFrom joins the tail of an array")
    void joinStringFrom() {
        final String[] words = {"!command", "player", "10", "20"};
        assertEquals("player 10 20", StringUtil.joinStringFrom(words, 1));
        assertEquals("player-10-20", StringUtil.joinStringFrom(words, 1, "-"));
        assertEquals("20", StringUtil.joinStringFrom(words, 3));
        assertEquals("", StringUtil.joinStringFrom(words, 4));
    }

    @Test
    @DisplayName("makeEnumHumanReadable title-cases words but leaves abbreviations alone")
    void makeEnumHumanReadable() {
        assertEquals("Maple Story IS", StringUtil.makeEnumHumanReadable("MAPLE_STORY_IS"));
        assertEquals("Hello", StringUtil.makeEnumHumanReadable("HELLO"));
    }

    @Test
    @DisplayName("countCharacters counts occurrences")
    void countCharacters() {
        assertEquals(0, StringUtil.countCharacters("abc", 'z'));
        assertEquals(3, StringUtil.countCharacters("a.b.c.", '.'));
        assertEquals(0, StringUtil.countCharacters("", '.'));
    }

    @Test
    @DisplayName("getDaysAmount is whole days between two millisecond stamps")
    void getDaysAmount() {
        final long day = 86_400_000L;
        assertEquals(0, StringUtil.getDaysAmount(0, day - 1));
        assertEquals(1, StringUtil.getDaysAmount(0, day));
        assertEquals(7, StringUtil.getDaysAmount(0, 7 * day + 1234));
    }

    @Test
    @DisplayName("getReadableMillis spells out an interval")
    void getReadableMillis() {
        assertEquals("None.", StringUtil.getReadableMillis(0, 0));
        assertEquals("1 second.", StringUtil.getReadableMillis(0, 1000));
        assertEquals("5 seconds.", StringUtil.getReadableMillis(0, 5000));
        assertEquals("1 minute and 30 seconds.", StringUtil.getReadableMillis(0, 90_000));
        assertTrue(StringUtil.getReadableMillis(0, 2 * 86_400_000L).startsWith("2 days"));
    }
}
