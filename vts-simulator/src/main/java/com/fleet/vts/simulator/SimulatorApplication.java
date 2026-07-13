package com.fleet.vts.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Vehicle fleet simulator. Each vehicle advances along a polyline route by
 * interpolation and is ticked in parallel using Java 21 virtual threads.
 */
@SpringBootApplication
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
