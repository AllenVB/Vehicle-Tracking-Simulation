package com.fleet.vts.simulator.sim;

import java.util.List;

/**
 * The 81 Turkish provinces with their approximate city-centre coordinates and a
 * population-weighted vehicle allocation that sums to exactly 100:
 * the three largest metros (İstanbul, Ankara, İzmir) get 3, the next 13 metros
 * get 2, and the remaining 65 provinces get 1. This is the default fleet layout
 * (dev profile = 100 vehicles); larger fleets round-robin over the same centres.
 */
public final class TurkeyProvinces {

    public record Province(String name, double lat, double lon, int vehicles) {
    }

    private TurkeyProvinces() {
    }

    /**
     * A destination for a vehicle standing at (lat, lon): one of the {@code candidates}
     * nearest provinces, picked at random. Near rather than anywhere, so journeys are
     * intercity but still finish in a demo-friendly time, and the fleet keeps its
     * population-weighted spread instead of all drifting to one corner.
     */
    public static Province nearbyDestination(double lat, double lon, int candidates,
                                             java.util.Random rnd) {
        List<Province> sorted = new java.util.ArrayList<>(ALL);
        sorted.sort(java.util.Comparator.comparingDouble(p -> sq(p.lat() - lat) + sq(p.lon() - lon)));
        // index 0 is (essentially) where we already are -> skip it
        int n = Math.min(candidates, sorted.size() - 1);
        return sorted.get(1 + rnd.nextInt(Math.max(1, n)));
    }

    private static double sq(double v) {
        return v * v;
    }

    public static final List<Province> ALL = List.of(
            new Province("Adana", 37.00, 35.32, 2),
            new Province("Adıyaman", 37.76, 38.28, 1),
            new Province("Afyonkarahisar", 38.76, 30.54, 1),
            new Province("Ağrı", 39.72, 43.05, 1),
            new Province("Amasya", 40.65, 35.83, 1),
            new Province("Ankara", 39.93, 32.86, 3),
            new Province("Antalya", 36.90, 30.70, 2),
            new Province("Artvin", 41.18, 41.82, 1),
            new Province("Aydın", 37.85, 27.84, 1),
            new Province("Balıkesir", 39.65, 27.88, 1),
            new Province("Bilecik", 40.15, 29.98, 1),
            new Province("Bingöl", 38.88, 40.50, 1),
            new Province("Bitlis", 38.40, 42.11, 1),
            new Province("Bolu", 40.74, 31.61, 1),
            new Province("Burdur", 37.72, 30.29, 1),
            new Province("Bursa", 40.19, 29.06, 2),
            new Province("Çanakkale", 40.15, 26.41, 1),
            new Province("Çankırı", 40.60, 33.62, 1),
            new Province("Çorum", 40.55, 34.95, 1),
            new Province("Denizli", 37.78, 29.09, 1),
            new Province("Diyarbakır", 37.91, 40.24, 2),
            new Province("Edirne", 41.68, 26.56, 1),
            new Province("Elazığ", 38.68, 39.22, 1),
            new Province("Erzincan", 39.75, 39.50, 1),
            new Province("Erzurum", 39.90, 41.27, 1),
            new Province("Eskişehir", 39.78, 30.52, 1),
            new Province("Gaziantep", 37.07, 37.38, 2),
            new Province("Giresun", 40.91, 38.39, 1),
            new Province("Gümüşhane", 40.46, 39.48, 1),
            new Province("Hakkari", 37.57, 43.74, 1),
            new Province("Hatay", 36.20, 36.16, 2),
            new Province("Isparta", 37.76, 30.55, 1),
            new Province("Mersin", 36.80, 34.63, 2),
            new Province("İstanbul", 41.01, 28.98, 3),
            new Province("İzmir", 38.42, 27.14, 3),
            new Province("Kars", 40.60, 43.10, 1),
            new Province("Kastamonu", 41.39, 33.78, 1),
            new Province("Kayseri", 38.73, 35.49, 2),
            new Province("Kırklareli", 41.74, 27.22, 1),
            new Province("Kırşehir", 39.15, 34.16, 1),
            new Province("Kocaeli", 40.77, 29.92, 2),
            new Province("Konya", 37.87, 32.48, 2),
            new Province("Kütahya", 39.42, 29.98, 1),
            new Province("Malatya", 38.35, 38.31, 1),
            new Province("Manisa", 38.61, 27.43, 2),
            new Province("Kahramanmaraş", 37.58, 36.93, 1),
            new Province("Mardin", 37.31, 40.74, 1),
            new Province("Muğla", 37.22, 28.36, 1),
            new Province("Muş", 38.73, 41.49, 1),
            new Province("Nevşehir", 38.62, 34.71, 1),
            new Province("Niğde", 37.97, 34.68, 1),
            new Province("Ordu", 40.98, 37.88, 1),
            new Province("Rize", 41.02, 40.52, 1),
            new Province("Sakarya", 40.77, 30.40, 1),
            new Province("Samsun", 41.29, 36.33, 2),
            new Province("Siirt", 37.93, 41.94, 1),
            new Province("Sinop", 42.03, 35.15, 1),
            new Province("Sivas", 39.75, 37.02, 1),
            new Province("Tekirdağ", 40.98, 27.51, 1),
            new Province("Tokat", 40.31, 36.55, 1),
            new Province("Trabzon", 41.00, 39.72, 1),
            new Province("Tunceli", 39.11, 39.55, 1),
            new Province("Şanlıurfa", 37.17, 38.79, 2),
            new Province("Uşak", 38.68, 29.41, 1),
            new Province("Van", 38.49, 43.38, 1),
            new Province("Yozgat", 39.82, 34.81, 1),
            new Province("Zonguldak", 41.45, 31.79, 1),
            new Province("Aksaray", 38.37, 34.03, 1),
            new Province("Bayburt", 40.26, 40.22, 1),
            new Province("Karaman", 37.18, 33.22, 1),
            new Province("Kırıkkale", 39.85, 33.52, 1),
            new Province("Batman", 37.88, 41.13, 1),
            new Province("Şırnak", 37.52, 42.46, 1),
            new Province("Bartın", 41.64, 32.34, 1),
            new Province("Ardahan", 41.11, 42.70, 1),
            new Province("Iğdır", 39.92, 44.04, 1),
            new Province("Yalova", 40.65, 29.28, 1),
            new Province("Karabük", 41.20, 32.63, 1),
            new Province("Kilis", 36.72, 37.12, 1),
            new Province("Osmaniye", 37.07, 36.25, 1),
            new Province("Düzce", 40.84, 31.16, 1)
    );
}
