package jmri.time.implementation;

import java.util.ArrayList;
import java.util.List;

import jmri.InstanceManager;
import jmri.JmriException;
import jmri.Sensor;
import jmri.Timebase;
import jmri.TimebaseBehaviorTestBase;
import jmri.TimebaseRateException;
import jmri.jmrix.internal.InternalSystemConnectionMemo;
import jmri.time.TimeProviderManager;
import jmri.util.JUnitAppender;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the shared Timebase behavior tests against DefaultTimebase, plus a few of its own. */
public class DefaultTimebaseTest extends TimebaseBehaviorTestBase {

    @Override
    protected Timebase createTimebase(InternalSystemConnectionMemo memo) {
        return new DefaultTimebase(memo);
    }

    // the computer clock can't change its rate or stop
    private void useComputerClock() {
        InstanceManager.getDefault(TimeProviderManager.class).getMainTimeProviderHandler().setUsePrimaryTimeProvider(false);
    }

    @Test
    public void testComputerClockRateChangeThrows() {
        useComputerClock();
        assertThrows(TimebaseRateException.class, () -> clock.userSetRate(2.0));
    }

    @Test
    public void testComputerClockSetRunWarnsAndSkips() {
        useComputerClock();
        clock.setRun(false);
        JUnitAppender.assertWarnMessageStartingWith("IUSystemClock can't start or stop now");
        assertTrue(clock.getRun(), "still running");
    }

    @Test
    public void testComputerClockSetTimeSkipsHardware() {
        useComputerClock();
        syncWithHardware();
        clock.setTime(at(6, 0));
        JUnitAppender.assertWarnMessageStartingWith("IUSystemClock can't set its time now");
        assertTrue(hardware.calls.isEmpty(), "hardware not sent a time the clock didn't take: " + hardware.calls);
    }

    @Test
    public void testComputerClockSensorGoesBackToRunning() throws JmriException {
        useComputerClock();
        clockRunningSensor().setKnownState(Sensor.INACTIVE);
        JUnitAppender.assertWarnMessageStartingWith("IUSystemClock can't start or stop now");
        assertEquals(Sensor.ACTIVE, clockRunningSensor().getKnownState(), "sensor matches the running clock");
    }

    @Test
    public void testComputerClockRunWhileRunningIsQuiet() {
        useComputerClock();
        clock.setRun(true); // tearDown fails on any unexpected warning
        assertTrue(clock.getRun());
    }

    @Test
    public void testDisposeRemovesSensorListener() {
        Sensor sensor = clockRunningSensor();
        int before = sensor.getNumPropertyChangeListeners();
        clock.dispose();
        assertEquals(before - 1, sensor.getNumPropertyChangeListeners(), "sensor listener removed");
        clock = null; // already disposed
    }

    @Test
    public void testSameStartTimeFiresNoConfigEvent() {
        clock.setStartSetTime(true, at(6, 0));
        List<String> events = new ArrayList<>();
        clock.addPropertyChangeListener("config", e -> events.add(e.getPropertyName()));
        clock.setStartSetTime(true, at(6, 0));
        assertTrue(events.isEmpty(), "no config event for the same values");
    }

}
