package com.graphhopper.routing.util;

/**
 * Retrieve default speed
 *
 * @author Andrzej Oles
 */
public abstract class AbstractAdjustedSpeedCalculator implements SpeedCalculator{
    protected final SpeedCalculator superSpeedCalculator;

    public AbstractAdjustedSpeedCalculator(SpeedCalculator superSpeedCalculator) {
        if (superSpeedCalculator == null)
            throw new IllegalArgumentException("No super calculator set");
        this.superSpeedCalculator = superSpeedCalculator;
    }

    @Override
    public boolean isTimeDependent() {
        return superSpeedCalculator.isTimeDependent();
    }
}