package com.fleet.vts.iettfeed.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Configuration for the İETT live feed and the downstream ingestion target. */
@ConfigurationProperties(prefix = "vts.iett")
public class IettProperties {

    /** Whether the scheduled poll runs at all. */
    private boolean enabled = true;

    /** İETT SOAP service base URL (host + FiloDurum path). */
    private String baseUrl = "https://api.ibb.gov.tr/iett/FiloDurum";

    /** SOAP endpoint path relative to {@link #baseUrl}. */
    private String soapPath = "/SeferGerceklesme.asmx";

    /** Route codes (HatKodu) to poll each tick; the API has no "all routes" call. */
    private List<String> routes = List.of("15B", "500T", "34", "146", "28");

    /** Cap on distinct buses tracked; must not exceed the seeded IMEI count (100). */
    private int maxVehicles = 100;

    /** Base URL of the ingestion service that accepts telemetry batches. */
    private String ingestionBaseUrl = "http://localhost:8081";

    private Duration iettConnectTimeout = Duration.ofSeconds(5);
    private Duration iettReadTimeout = Duration.ofSeconds(25);
    private Duration ingestionConnectTimeout = Duration.ofSeconds(3);
    private Duration ingestionReadTimeout = Duration.ofSeconds(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSoapPath() {
        return soapPath;
    }

    public void setSoapPath(String soapPath) {
        this.soapPath = soapPath;
    }

    public List<String> getRoutes() {
        return routes;
    }

    public void setRoutes(List<String> routes) {
        this.routes = routes;
    }

    public int getMaxVehicles() {
        return maxVehicles;
    }

    public void setMaxVehicles(int maxVehicles) {
        this.maxVehicles = maxVehicles;
    }

    public String getIngestionBaseUrl() {
        return ingestionBaseUrl;
    }

    public void setIngestionBaseUrl(String ingestionBaseUrl) {
        this.ingestionBaseUrl = ingestionBaseUrl;
    }

    public Duration getIettConnectTimeout() {
        return iettConnectTimeout;
    }

    public void setIettConnectTimeout(Duration iettConnectTimeout) {
        this.iettConnectTimeout = iettConnectTimeout;
    }

    public Duration getIettReadTimeout() {
        return iettReadTimeout;
    }

    public void setIettReadTimeout(Duration iettReadTimeout) {
        this.iettReadTimeout = iettReadTimeout;
    }

    public Duration getIngestionConnectTimeout() {
        return ingestionConnectTimeout;
    }

    public void setIngestionConnectTimeout(Duration ingestionConnectTimeout) {
        this.ingestionConnectTimeout = ingestionConnectTimeout;
    }

    public Duration getIngestionReadTimeout() {
        return ingestionReadTimeout;
    }

    public void setIngestionReadTimeout(Duration ingestionReadTimeout) {
        this.ingestionReadTimeout = ingestionReadTimeout;
    }
}
