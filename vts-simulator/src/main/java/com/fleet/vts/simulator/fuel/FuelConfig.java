package com.fleet.vts.simulator.fuel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the fuel-data seam.
 *
 * <p>The default {@link SimulatedFuelLevelSource} backs off as soon as any other
 * {@link FuelLevelSource} bean exists, so a real integration is added by registering one
 * bean and deleting nothing. The condition sits on a {@code @Bean} method rather than on
 * the class itself because {@code @ConditionalOnMissingBean} is only well defined here —
 * during component scanning the evaluation order is undefined, and the default could win
 * or lose at random.
 */
@Configuration
public class FuelConfig {

    @Bean
    @ConditionalOnMissingBean(FuelLevelSource.class)
    public FuelLevelSource simulatedFuelLevelSource() {
        return new SimulatedFuelLevelSource();
    }
}
