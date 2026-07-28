package com.fleet.vts.iettfeed.iett;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.iettfeed.config.IettProperties;
import com.fleet.vts.iettfeed.source.LiveReading;
import com.fleet.vts.iettfeed.source.LiveVehicleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * İETT live-position source. For each configured route it POSTs a SOAP request to
 * {@code GetHatOtoKonum_json} (the HTTP GET form is blocked by İBB's API gateway),
 * pulls the JSON array out of the SOAP {@code <...Result>} element and converts
 * each entry into a neutral {@link LiveReading}. Per-route failures are logged and
 * skipped so one bad route never sinks the whole tick.
 */
@Component
public class IettClient implements LiveVehicleSource {

    private static final Logger log = LoggerFactory.getLogger(IettClient.class);

    private static final String RESULT_OPEN = "<GetHatOtoKonum_jsonResult>";
    private static final String RESULT_CLOSE = "</GetHatOtoKonum_jsonResult>";
    private static final String SOAP_ACTION = "\"http://tempuri.org/GetHatOtoKonum_json\"";
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<List<IettVehicle>> LIST_TYPE = new TypeReference<>() {
    };

    private final RestClient iett;
    private final IettProperties props;
    private final ObjectMapper mapper;

    public IettClient(RestClient iettRestClient, IettProperties props, ObjectMapper mapper) {
        this.iett = iettRestClient;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public List<LiveReading> poll() {
        List<LiveReading> readings = new ArrayList<>();
        for (String route : props.getRoutes()) {
            try {
                String xml = call(route);
                for (IettVehicle v : parseVehicles(xml, mapper)) {
                    toReading(v).ifPresent(readings::add);
                }
            } catch (Exception e) {
                log.warn("İETT route {} çekilemedi: {}", route, e.getMessage());
            }
        }
        return readings;
    }

    private String call(String hatKodu) {
        return iett.post()
                .uri(props.getSoapPath())
                .contentType(MediaType.valueOf("text/xml;charset=UTF-8"))
                .header("SOAPAction", SOAP_ACTION)
                .body(soapEnvelope(hatKodu))
                .retrieve()
                .body(String.class);
    }

    static String soapEnvelope(String hatKodu) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body>"
                + "<GetHatOtoKonum_json xmlns=\"http://tempuri.org/\">"
                + "<HatKodu>" + xmlEscape(hatKodu) + "</HatKodu>"
                + "</GetHatOtoKonum_json>"
                + "</soap:Body></soap:Envelope>";
    }

    /** Extract the JSON array from the SOAP result element and parse it. Any
     *  missing element, empty body or malformed JSON yields an empty list. */
    static List<IettVehicle> parseVehicles(String soapXml, ObjectMapper mapper) {
        if (soapXml == null) {
            return List.of();
        }
        int start = soapXml.indexOf(RESULT_OPEN);
        int end = soapXml.indexOf(RESULT_CLOSE);
        if (start < 0 || end < 0 || end <= start) {
            return List.of();
        }
        String json = xmlUnescape(soapXml.substring(start + RESULT_OPEN.length(), end)).trim();
        if (json.isEmpty()) {
            return List.of();
        }
        try {
            List<IettVehicle> list = mapper.readValue(json, LIST_TYPE);
            return list != null ? list : List.of();
        } catch (Exception e) {
            log.warn("İETT yaniti parse edilemedi: {}", e.getMessage());
            return List.of();
        }
    }

    /** Convert a raw İETT entry into a neutral reading; drops entries without a
     *  parseable position. Timestamp is best-effort (null when unparseable). */
    static Optional<LiveReading> toReading(IettVehicle v) {
        Double lat = parseCoord(v.enlem());
        Double lon = parseCoord(v.boylam());
        if (lat == null || lon == null || v.kapino() == null || v.kapino().isBlank()) {
            return Optional.empty();
        }
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180 || (lat == 0.0 && lon == 0.0)) {
            return Optional.empty();
        }
        return Optional.of(new LiveReading(v.kapino().trim(), lat, lon, parseTs(v.sonKonumZamani())));
    }

    private static Double parseCoord(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Instant parseTs(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.trim(), TS_FORMAT).atZone(ISTANBUL).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String xmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
