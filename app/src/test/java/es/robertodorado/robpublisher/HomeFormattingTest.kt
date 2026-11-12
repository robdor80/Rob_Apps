package es.robertodorado.robpublisher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFormattingTest {
    @Test fun releaseTagKeepsVersionAndCodeWithoutGitDecoration() {
        assertEquals("0.2.1 (3)", displayRelease("v0.2.1-3"))
        assertEquals("1.0.2 (3)", displayRelease("v1.0.2-3"))
        assertEquals("release-candidate", displayRelease("release-candidate"))
    }
    @Test fun publishedDateUsesSpanishOrder() {
        assertEquals("23/11/2026", displayDate("2026-11-23"))
        assertEquals("No disponible", displayDate(""))
    }
}
