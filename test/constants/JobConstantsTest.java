package constants;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure id-range predicates out of JobConstants.
 *
 * <p>Only the functions that are arithmetic on an id are covered - the job
 * family bands and the advancement check. The delay tables are data, not
 * arithmetic, and are left alone.
 */
class JobConstantsTest {

    @Test
    @DisplayName("job family predicates")
    void jobs() {
        assertTrue(JobConstants.isAdventurer(0));
        assertTrue(JobConstants.isAdventurer(510));
        assertFalse(JobConstants.isAdventurer(1000));

        assertTrue(JobConstants.isKOC(1000));
        assertTrue(JobConstants.isKOC(1512));
        assertFalse(JobConstants.isKOC(2000));

        assertTrue(JobConstants.isAran(2000));
        assertTrue(JobConstants.isAran(2112));
        assertFalse(JobConstants.isAran(2001), "2001 is the Evan beginner, not an Aran");

        assertTrue(JobConstants.isEvan(2001));
        assertTrue(JobConstants.isEvan(2218));
        assertFalse(JobConstants.isEvan(2219));

        assertTrue(JobConstants.isResist(3000));
        assertTrue(JobConstants.isResist(3512));
        assertFalse(JobConstants.isResist(2999));
    }

    @Test
    @DisplayName("isJobFamily requires the same hundred and a forwards step")
    void jobFamily() {
        assertTrue(JobConstants.isJobFamily(100, 110));
        assertTrue(JobConstants.isJobFamily(100, 100));
        assertFalse(JobConstants.isJobFamily(110, 100), "an advancement never goes backwards");
        assertFalse(JobConstants.isJobFamily(100, 200));
    }
}
