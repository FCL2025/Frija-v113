package constants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure functions out of MapConstants.
 *
 * <p>Only the view-range constant is arithmetic; the map-id predicates are
 * switch tables over ids this fork's WZ data may or may not carry, so they are
 * left to the running server.
 */
class MapConstantsTest {

    @Test
    @DisplayName("maxViewRangeSq is 800000, which is not what its comment says")
    void maxViewRangeSq() {
        // The source says `return 800000; // 800 * 800`, but 800 * 800 is 640000.
        // Every caller compares it against Point.distanceSq, so the view radius the
        // server actually uses is sqrt(800000) ~ 894 units, not 800. Pinned at the
        // value the code has rather than the one the comment claims: correcting it
        // would shrink what every player on every map can see, which is a gameplay
        // change and not a test's decision.
        assertEquals(800_000, MapConstants.maxViewRangeSq());
        assertEquals(640_000, 800 * 800);
    }
}
