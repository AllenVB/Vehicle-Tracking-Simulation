package com.fleet.vts.iettfeed.iett;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One vehicle entry from İETT {@code GetHatOtoKonum_json}. Every field arrives as
 * a string. Note the live API returns NO speed or plate field (contrary to some
 * docs) — only position, identity and route. Speed/heading are derived later.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IettVehicle(
        String kapino,
        String enlem,
        String boylam,
        String hatkodu,
        String guzergahkodu,
        String hatad,
        String yon,
        @JsonProperty("son_konum_zamani") String sonKonumZamani,
        String yakinDurakKodu) {
}
