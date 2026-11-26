package jmri.jmrix.loconet;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.TimeZone;

import jmri.InstanceManager;
import jmri.Timebase;
import jmri.util.JUnitUtil;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for LnClockControlTest class.
 *
 * @author Bob Jacobsen (c) 2025
 **/
public class LnClockControl2Test {

    private LnClockControl ldt;
    private Date time;

    private LocoNetInterfaceScaffold lnis;
    private LocoNetSystemConnectionMemo memo;
    private SlotManager slotmanager;
    private TimeZone originalTimeZone;

    @Test
    public void testCheckStartValues(){
        assertEquals(1.0, ldt.getRate(), "initial rate");
        // midnight to the second, as the original toString() check was, on the fast clock's date
        ZonedDateTime start = time.toInstant().atZone(ZoneId.systemDefault());
        ZonedDateTime fastClock = InstanceManager.getDefault(Timebase.class).getTime().toInstant().atZone(ZoneId.systemDefault());
        assertEquals(LocalTime.MIDNIGHT, start.toLocalTime().truncatedTo(ChronoUnit.SECONDS), "starts at 00:00:00");
        assertEquals(fastClock.toLocalDate(), start.toLocalDate(), "on the fast clock's date");
    }

    @BeforeEach
    public void setUp() {
        // LnClockControl works in whole hours from UTC, so pin a zone where midnight is exact
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        JUnitUtil.setUp();
        JUnitUtil.resetProfileManager();
        JUnitUtil.initConfigureManager();
        JUnitUtil.initMemoryManager();

        // prepare an interface
        lnis = new LocoNetInterfaceScaffold();
        slotmanager = new SlotManager(lnis);
        memo = new LocoNetSystemConnectionMemo(lnis, slotmanager);
        lnis.setSystemConnectionMemo(memo);
        slotmanager.setCommandStationType(LnCommandStationType.COMMAND_STATION_DCS050);

        ldt = new LnClockControl(memo);
        time = ldt.getTime();
    }

    @AfterEach
    public void tearDown(){
        memo.dispose();
        lnis = null;

        JUnitUtil.tearDown();
        TimeZone.setDefault(originalTimeZone);
        originalTimeZone = null;
    }

}
