package jmri.time.implementation;

import java.time.*;

import jmri.util.JUnitUtil;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for InternalDateTime class.
 *
 * @author Bob Jacobsen (c) 2025
 **/
public class InternalDateTimeTest {

    InternalDateTime idt;
    ZonedDateTime time;

    @Test
    public void testCheckStartValues(){
        assertEquals(1.0, idt.getRate().getRate(), "initial rate");
        assertFalse(idt.isRunning(), "initial running");
    }

    @Test
    public void testSetTimeOfDayKeepsZone() {
        ZoneId denver = ZoneId.of("America/Denver");
        idt.setDateTime(ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, denver));
        idt.setTime(LocalTime.of(6, 0));
        assertEquals(ZonedDateTime.of(2026, 1, 15, 6, 0, 0, 0, denver), idt.getTime());
    }

    @Test
    public void testFirstStartKeepsTimeSetBefore() {
        InternalDateTime clock = new InternalDateTime("IU2").init();
        ZonedDateTime sixAm = ZonedDateTime.of(1957, 6, 14, 6, 0, 0, 0, ZoneId.systemDefault());
        clock.setDateTime(sixAm);
        clock.start();
        assertEquals(sixAm.toLocalDate(), clock.getTime().toLocalDate(), "same day");
        assertEquals(6, clock.getTime().getHour(), "still around 6:00");
        clock.dispose();
    }

    @BeforeEach
    public void setUp() {
        JUnitUtil.setUp();
        JUnitUtil.initDefaultUserMessagePreferences();
        JUnitUtil.initTimeProviderManager();

        idt = new InternalDateTime("IU1");
        idt.init();
        idt.lockFromRunning();
        time = idt.getTime();
    }

    @AfterEach
    public void tearDown(){
        time = null;
        JUnitUtil.tearDown();
    }

}
