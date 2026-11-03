package ir.delneveshte.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppUpdaterTest {
    @Test public void comparesDottedReleaseVersionsNumerically(){
        assertTrue(AppUpdater.compare("0.24.0","0.23.0")>0);
        assertTrue(AppUpdater.compare("0.10.0","0.9.9")>0);
        assertEquals(0,AppUpdater.compare("v0.24.0","0.24.0"));
        assertTrue(AppUpdater.compare("1.0","0.99.99")>0);
    }
}
