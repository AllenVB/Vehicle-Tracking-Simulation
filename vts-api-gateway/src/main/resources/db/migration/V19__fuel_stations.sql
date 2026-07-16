-- Fuel stations near every province, so the map can show them and a selected vehicle
-- can report its distance to the nearest one. Two per province at small offsets, with
-- rotating real brands. Not GPS-perfect, but distributed along the intercity network.

CREATE TABLE fuel_station (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL REFERENCES tenant (id),
    name       VARCHAR(90) NOT NULL,
    brand      VARCHAR(40) NOT NULL,
    location   GEOGRAPHY(POINT, 4326) NOT NULL
);
CREATE INDEX idx_fuel_station_location ON fuel_station USING GIST (location);

INSERT INTO fuel_station (tenant_id, name, brand, location)
SELECT t.id,
       b.brand || ' ' || p.name || CASE WHEN s.k = 1 THEN '' ELSE ' 2' END,
       b.brand,
       ST_SetSRID(ST_MakePoint(
           p.lon + (s.k * 2 - 3) * 0.030 + ((p.n % 5) - 2) * 0.008,
           p.lat + (s.k * 2 - 3) * 0.020 + ((p.n % 3) - 1) * 0.008), 4326)::geography
FROM tenant t
CROSS JOIN (VALUES
    (1,'Adana',37.00,35.32),(2,'Adıyaman',37.76,38.28),(3,'Afyonkarahisar',38.76,30.54),
    (4,'Ağrı',39.72,43.05),(5,'Amasya',40.65,35.83),(6,'Ankara',39.93,32.86),
    (7,'Antalya',36.90,30.70),(8,'Artvin',41.18,41.82),(9,'Aydın',37.85,27.84),
    (10,'Balıkesir',39.65,27.88),(11,'Bilecik',40.15,29.98),(12,'Bingöl',38.88,40.50),
    (13,'Bitlis',38.40,42.11),(14,'Bolu',40.74,31.61),(15,'Burdur',37.72,30.29),
    (16,'Bursa',40.19,29.06),(17,'Çanakkale',40.15,26.41),(18,'Çankırı',40.60,33.62),
    (19,'Çorum',40.55,34.95),(20,'Denizli',37.78,29.09),(21,'Diyarbakır',37.91,40.24),
    (22,'Edirne',41.68,26.56),(23,'Elazığ',38.68,39.22),(24,'Erzincan',39.75,39.50),
    (25,'Erzurum',39.90,41.27),(26,'Eskişehir',39.78,30.52),(27,'Gaziantep',37.07,37.38),
    (28,'Giresun',40.91,38.39),(29,'Gümüşhane',40.46,39.48),(30,'Hakkari',37.57,43.74),
    (31,'Hatay',36.20,36.16),(32,'Isparta',37.76,30.55),(33,'Mersin',36.80,34.63),
    (34,'İstanbul',41.01,28.98),(35,'İzmir',38.42,27.14),(36,'Kars',40.60,43.10),
    (37,'Kastamonu',41.39,33.78),(38,'Kayseri',38.73,35.49),(39,'Kırklareli',41.74,27.22),
    (40,'Kırşehir',39.15,34.16),(41,'Kocaeli',40.77,29.92),(42,'Konya',37.87,32.48),
    (43,'Kütahya',39.42,29.98),(44,'Malatya',38.35,38.31),(45,'Manisa',38.61,27.43),
    (46,'Kahramanmaraş',37.58,36.93),(47,'Mardin',37.31,40.74),(48,'Muğla',37.22,28.36),
    (49,'Muş',38.73,41.49),(50,'Nevşehir',38.62,34.71),(51,'Niğde',37.97,34.68),
    (52,'Ordu',40.98,37.88),(53,'Rize',41.02,40.52),(54,'Sakarya',40.77,30.40),
    (55,'Samsun',41.29,36.33),(56,'Siirt',37.93,41.94),(57,'Sinop',42.03,35.15),
    (58,'Sivas',39.75,37.02),(59,'Tekirdağ',40.98,27.51),(60,'Tokat',40.31,36.55),
    (61,'Trabzon',41.00,39.72),(62,'Tunceli',39.11,39.55),(63,'Şanlıurfa',37.17,38.79),
    (64,'Uşak',38.68,29.41),(65,'Van',38.49,43.38),(66,'Yozgat',39.82,34.81),
    (67,'Zonguldak',41.45,31.79),(68,'Aksaray',38.37,34.03),(69,'Bayburt',40.26,40.22),
    (70,'Karaman',37.18,33.22),(71,'Kırıkkale',39.85,33.52),(72,'Batman',37.88,41.13),
    (73,'Şırnak',37.52,42.46),(74,'Bartın',41.64,32.34),(75,'Ardahan',41.11,42.70),
    (76,'Iğdır',39.92,44.04),(77,'Yalova',40.65,29.28),(78,'Karabük',41.20,32.63),
    (79,'Kilis',36.72,37.12),(80,'Osmaniye',37.07,36.25),(81,'Düzce',40.84,31.16)
) AS p(n, name, lat, lon)
CROSS JOIN generate_series(1, 2) AS s(k)
CROSS JOIN LATERAL (
    SELECT (ARRAY['Shell','BP','Opet','Petrol Ofisi','Total','Aytemiz'])[1 + ((p.n + s.k) % 6)] AS brand
) AS b
WHERE t.slug = 'demo';
