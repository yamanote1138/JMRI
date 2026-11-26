package jmri.time.implementation;

import java.time.*;

import java.util.Date;

import jmri.*;
import jmri.implementation.AbstractNamedBean;
import jmri.time.*;

/**
 * Abstract implementation of Timebase.
 *
 * This class contains all the code that relates to TimeProvider.
 *
 * @author Bob Jacobsen Copyright (C) 2004, 2007
 * @author Dave Duchamp Copyright (C) 2007. additions/revisions for handling one hardware clock
 * @author Daniel Bergqvist Copyright (C) 2025
 */
public abstract class AbstractTimebase extends AbstractNamedBean implements Timebase {

    // the same limits as SimpleTimebase
    private static final double MINIMUM_RATE = 0.1;
    private static final double MAXIMUM_RATE = 100;

    public AbstractTimebase(String sysName) {
        super(sysName);
    }

    private ZonedDateTime convertToZonedDateTime(Date dateToConvert) {
        return Instant.ofEpochMilli(dateToConvert.getTime())
                .atZone(ZoneId.systemDefault());
    }

    private Date convertToDate(ZonedDateTime dateToConvert) {
        return java.util.Date.from(dateToConvert.toInstant());
    }

    protected void setTimeIfPossible(Date d) {
        TimeProvider tp = InstanceManager.getDefault(TimeProviderManager.class)
                .getCurrentTimeProvider();
        if (tp instanceof TimeSetter) {
            setTime(d);
        }
    }

    @Override
    public void setTime(Date d) {
        setTimeOnClock(d);
    }

    // sets the current clock's time; returns false if it can't set its time now
    protected boolean setTimeOnClock(Date d) {
        TimeProvider tp = InstanceManager.getDefault(TimeProviderManager.class)
                .getCurrentTimeProvider();
        // setTime declares no exception and SimpleTimebase never threw here, so warn and skip
        if (!(tp instanceof TimeSetter) || !((TimeSetter)tp).canSetDateTime()) {
            log.warn("{} can't set its time now, ignoring", tp != null ? tp.getDisplayName() : null);
            return false;
        }
        ((TimeSetter)tp).setDateTime(convertToZonedDateTime(d));
        return true;
    }

    @Override
    public void setTime(Instant i) {
        setTime(Date.from(i));
    }

    @Override
    public void userSetTime(Date d) {
        setTime(d);
    }

    @Override
    public Date getTime() {
        TimeProvider tp = InstanceManager.getDefault(TimeProviderManager.class)
                .getCurrentTimeProvider();
        return convertToDate(tp.getTime());
    }

    @Override
    public void setRun(boolean y) {
        TimeProvider tp = InstanceManager.getDefault(TimeProviderManager.class)
                .getCurrentTimeProvider();
        // setRun declares no exception and SimpleTimebase never threw here, so warn and skip
        if (!(tp instanceof StartStopTimeProvider) || !((StartStopTimeProvider)tp).canStartAndStop()) {
            // only warn about a real change, not e.g. a panel file asking a running computer clock to run
            if (tp == null || tp.isRunning() != y) {
                log.warn("{} can't start or stop now, ignoring", tp != null ? tp.getDisplayName() : null);
            }
            return;
        }
        if (y) {
            ((StartStopTimeProvider)tp).start();
        } else {
            ((StartStopTimeProvider)tp).stop();
        }
    }

    @Override
    public boolean getRun() {
        return InstanceManager.getDefault(TimeProviderManager.class)
                .getCurrentTimeProvider().isRunning();
    }

    @Override
    public void setRate(double factor) throws TimebaseRateException {
        setRateOnClock(factor);
    }

    @Override
    public void userSetRate(double factor) throws TimebaseRateException {
        setRateOnClock(factor);
    }

    // check, then set: say no with the TimebaseRateException callers already handle
    private void setRateOnClock(double factor) throws TimebaseRateException {
        if (factor < MINIMUM_RATE || factor > MAXIMUM_RATE) {
            log.error("rate of {} is out of reasonable range {} - {}", factor, MINIMUM_RATE, MAXIMUM_RATE);
            throw new TimebaseRateException(Bundle.getMessage("IncorrectRate", factor, MINIMUM_RATE, MAXIMUM_RATE));
        }
        TimeProvider tp = InstanceManager.getDefault(TimeProviderManager.class)
                .getCurrentTimeProvider();
        if (!(tp instanceof CanSetRate) || !((CanSetRate)tp).canSetRate()) {
            throw new TimebaseRateException(Bundle.getMessage("ClockCantSetRate", tp != null ? tp.getDisplayName() : null));
        }
        if (tp instanceof RateSetter) {
            // the clock's own setRate fires the rate event; setting its Rate object directly doesn't
            ((RateSetter)tp).setRate(factor);
        } else if (tp.getRate() instanceof RateSetter) {
            ((RateSetter)tp.getRate()).setRate(factor);
        } else {
            throw new TimebaseRateException(Bundle.getMessage("ClockCantSetRate", tp.getDisplayName()));
        }
    }

    @Override
    public double getRate() {
        return InstanceManager.getDefault(TimeProviderManager.class)
                .getCurrentTimeProvider().getRate().getRate();
    }

    @Override
    public double userGetRate() {
        return InstanceManager.getDefault(TimeProviderManager.class)
                .getCurrentTimeProvider().getRate().getRate();
    }


    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AbstractTimebase.class);

}
