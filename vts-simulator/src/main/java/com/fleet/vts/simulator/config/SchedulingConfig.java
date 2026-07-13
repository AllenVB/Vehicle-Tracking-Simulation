package com.fleet.vts.simulator.config;

import com.fleet.vts.simulator.sim.FleetSimulator;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Registers the simulation tick at a fixed rate taken from configuration
 * ({@code vts.simulator.tick}), so the cadence is profile-driven rather than
 * a compile-time constant on {@code @Scheduled}.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    private final FleetSimulator simulator;
    private final SimulatorProperties properties;

    public SchedulingConfig(FleetSimulator simulator, SimulatorProperties properties) {
        this.simulator = simulator;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedRateTask(simulator::tick, properties.getTick());
    }
}
