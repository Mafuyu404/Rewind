package cc.sighs.rewind.common.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link SavedDataRollback} 里那几处纯逻辑：判据、扁平布局的 id 映射、名单匹配规则。
 *
 * <p>这几处以前在四个 target 里各有一份（其中三份逐字节相同），搬进 common 之后由这里兜住。
 */
class SavedDataRollbackTest {
    @Test
    void recognizesSavedDataFilesInBothLayouts() {
        assertTrue(SavedDataRollback.isSavedDataFile("data/scoreboard.dat"));
        assertTrue(SavedDataRollback.isSavedDataFile("DIM-1/data/raids.dat"));
        assertTrue(SavedDataRollback.isSavedDataFile("dimensions/minecraft/the_end/data/minecraft/raids.dat"));

        assertFalse(SavedDataRollback.isSavedDataFile("region/r.0.0.mca"));
        assertFalse(SavedDataRollback.isSavedDataFile("playerdata/380df991-f603-344c-a090-369bad2a924a.dat"));
        assertFalse(SavedDataRollback.isSavedDataFile("data/level.dat.bak"));
    }

    @Test
    void mapsFlatLayoutFileNamesToIds() {
        assertEquals("scoreboard", SavedDataRollback.flatIdOf("data/scoreboard.dat"));
        assertEquals("raids", SavedDataRollback.flatIdOf("DIM-1/data/raids.dat"));
        assertEquals("random_sequences", SavedDataRollback.flatIdOf("DIM1/data/random_sequences.dat"));
    }

    @Test
    void matchesExactAndPrefixOrSuffixEntries() {
        Set<String> exact = Set.of("scoreboard");

        assertTrue(SavedDataRollback.matchesPrefixes("scoreboard", exact, Set.of("map_")));
        assertTrue(SavedDataRollback.matchesPrefixes("map_12", exact, Set.of("map_")));
        assertFalse(SavedDataRollback.matchesPrefixes("mapping", exact, Set.of("map_")));

        assertTrue(SavedDataRollback.matchesSuffixes("Monument_index", exact, Set.of("_index")));
        assertTrue(SavedDataRollback.matchesSuffixes("scoreboard", exact, Set.of("_index")));
        assertFalse(SavedDataRollback.matchesSuffixes("index", exact, Set.of("_index")));
    }
}
