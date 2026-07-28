package com.fleet.vts.iettfeed.iett;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.iettfeed.source.LiveReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** SOAP response extraction/parse, reading conversion and timestamp handling. */
class IettClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String OK_RESPONSE = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <GetHatOtoKonum_jsonResponse xmlns="http://tempuri.org/">
                  <GetHatOtoKonum_jsonResult>[\
            {"kapino":"C-290","boylam":"29.1014","enlem":"41.0469","hatkodu":"15B",\
            "guzergahkodu":"15B_G_D0","hatad":"TOPAGACI - USKUDAR","yon":"USKUDAR",\
            "son_konum_zamani":"2026-07-28 16:02:49","yakinDurakKodu":"223752"},\
            {"kapino":"C-228","boylam":"29.03936","enlem":"41.03969","hatkodu":"15B",\
            "guzergahkodu":"15B_D_D0","hatad":"TOPAGACI - USKUDAR","yon":"KURAN",\
            "son_konum_zamani":"2026-07-28 16:02:54","yakinDurakKodu":"219291"}\
            ]</GetHatOtoKonum_jsonResult>
                </GetHatOtoKonum_jsonResponse>
              </soap:Body>
            </soap:Envelope>""";

    private static final String FAULT_RESPONSE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
              <soapenv:Body><soapenv:Fault><faultstring>Policy Falsified</faultstring></soapenv:Fault></soapenv:Body>
            </soapenv:Envelope>""";

    @Test
    void parsesVehiclesFromSoapResult() {
        List<IettVehicle> vehicles = IettClient.parseVehicles(OK_RESPONSE, mapper);

        assertThat(vehicles).hasSize(2);
        assertThat(vehicles.get(0).kapino()).isEqualTo("C-290");
        assertThat(vehicles.get(0).enlem()).isEqualTo("41.0469");
        assertThat(vehicles.get(0).sonKonumZamani()).isEqualTo("2026-07-28 16:02:49");
        assertThat(vehicles.get(1).kapino()).isEqualTo("C-228");
    }

    @Test
    void faultResponseYieldsEmptyList() {
        assertThat(IettClient.parseVehicles(FAULT_RESPONSE, mapper)).isEmpty();
    }

    @Test
    void emptyResultElementYieldsEmptyList() {
        String xml = "<a><GetHatOtoKonum_jsonResult></GetHatOtoKonum_jsonResult></a>";
        assertThat(IettClient.parseVehicles(xml, mapper)).isEmpty();
        assertThat(IettClient.parseVehicles(null, mapper)).isEmpty();
    }

    @Test
    void toReadingParsesCoordinatesAndTimestamp() {
        IettVehicle v = new IettVehicle("C-290", "41.0469", "29.1014", "15B",
                "15B_G_D0", "x", "USKUDAR", "2026-07-28 16:02:49", "223752");

        Optional<LiveReading> reading = IettClient.toReading(v);

        assertThat(reading).isPresent();
        assertThat(reading.get().vehicleId()).isEqualTo("C-290");
        assertThat(reading.get().lat()).isEqualTo(41.0469);
        assertThat(reading.get().lon()).isEqualTo(29.1014);
        // İstanbul is UTC+3 → 16:02:49 local == 13:02:49Z
        assertThat(reading.get().ts()).isEqualTo(Instant.parse("2026-07-28T13:02:49Z"));
    }

    @Test
    void toReadingDropsInvalidOrZeroPosition() {
        assertThat(IettClient.toReading(new IettVehicle("C-1", "abc", "29.0", "15B",
                null, null, null, null, null))).isEmpty();
        assertThat(IettClient.toReading(new IettVehicle("C-2", "0", "0", "15B",
                null, null, null, "2026-07-28 16:02:49", null))).isEmpty();
        assertThat(IettClient.toReading(new IettVehicle("", "41.0", "29.0", "15B",
                null, null, null, null, null))).isEmpty();
    }

    @Test
    void unparseableTimestampBecomesNull() {
        assertThat(IettClient.parseTs("not-a-date")).isNull();
        assertThat(IettClient.parseTs(null)).isNull();
    }

    @Test
    void soapEnvelopeCarriesRouteCode() {
        assertThat(IettClient.soapEnvelope("15B"))
                .contains("<HatKodu>15B</HatKodu>")
                .contains("GetHatOtoKonum_json");
    }
}
