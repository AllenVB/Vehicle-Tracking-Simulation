package com.fleet.vts.simulator.control;

import com.fleet.vts.simulator.ingestion.TeltonikaTelemetryTransport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Device-channel controls, used to produce the conditions real hardware produces.
 *
 * <p>Silencing a device is the interesting one: it is the only way to make the platform
 * receive readings that are hours old, in bulk, out of order — the case the HTTP simulator
 * structurally could not generate, and therefore the case nothing downstream had ever met.
 *
 * <p>Stays on the simulator's internal port, like the rest of the control API.
 */
@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private final TeltonikaTelemetryTransport devices;

    public DeviceController(TeltonikaTelemetryTransport devices) {
        this.devices = devices;
    }

    /** Buffer depth and silence state across the emulated fleet. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return devices.status();
    }

    /**
     * Cut a device's link for {@code seconds}. It keeps recording; everything buffered goes
     * out in one burst when the window closes.
     *
     * @param imei device IMEI, e.g. {@code 000000000000007}
     */
    @PostMapping("/{imei}/silence")
    public Map<String, Object> silence(@PathVariable String imei,
                                       @RequestParam(defaultValue = "600") long seconds) {
        return devices.silence(imei, seconds);
    }
}
