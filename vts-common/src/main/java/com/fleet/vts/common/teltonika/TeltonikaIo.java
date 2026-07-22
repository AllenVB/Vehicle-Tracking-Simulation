package com.fleet.vts.common.teltonika;

/**
 * The AVL IO ids this platform reads, from Teltonika's FMB property list.
 *
 * <p>Only a handful of the several hundred defined ids matter here. Which ids a device
 * actually sends is a matter of its configuration profile, so every read must tolerate the
 * id being absent — that is a normal record, not a broken one.
 */
public final class TeltonikaIo {

    private TeltonikaIo() {
    }

    /** Ignition: 0 off, 1 on. */
    public static final int IGNITION = 239;

    /** Movement: 0 stopped, 1 moving. */
    public static final int MOVEMENT = 240;

    /** Internal battery level, percent. */
    public static final int BATTERY_LEVEL_PCT = 113;

    /** External (vehicle) power, millivolts. */
    public static final int EXTERNAL_VOLTAGE_MV = 66;

    /** Total odometer, metres. */
    public static final int TOTAL_ODOMETER_M = 16;

    /** Fuel level from the CAN adapter, percent. */
    public static final int FUEL_LEVEL_PCT = 89;

    /** GSM signal strength, 0-5. */
    public static final int GSM_SIGNAL = 21;
}
