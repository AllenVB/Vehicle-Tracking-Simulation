package com.fleet.vts.analytics.rules;

import com.fleet.vts.analytics.geofence.GeofenceRegistry;
import com.fleet.vts.analytics.state.GeofenceState;
import com.fleet.vts.common.enums.GeofenceEventType;
import com.fleet.vts.common.event.GeofenceEvent;
import com.fleet.vts.common.event.TelemetryEvent;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GEOFENCE_ENTER / GEOFENCE_EXIT via PostGIS-style point-in-polygon (JTS). The
 * per-vehicle set of geofences currently inside is kept in a state store; a
 * change of membership emits the corresponding enter/exit event.
 */
public class GeofenceRule implements ProcessorSupplier<String, TelemetryEvent, String, GeofenceEvent> {

    public static final String STORE = "geofence-inside-set";

    private final GeofenceRegistry registry;
    private final Serde<GeofenceState> stateSerde;

    public GeofenceRule(GeofenceRegistry registry, Serde<GeofenceState> stateSerde) {
        this.registry = registry;
        this.stateSerde = stateSerde;
    }

    @Override
    public Processor<String, TelemetryEvent, String, GeofenceEvent> get() {
        return new Processor<>() {
            private KeyValueStore<String, GeofenceState> store;
            private ProcessorContext<String, GeofenceEvent> context;

            @Override
            public void init(ProcessorContext<String, GeofenceEvent> context) {
                this.context = context;
                this.store = context.getStateStore(STORE);
            }

            @Override
            public void process(Record<String, TelemetryEvent> record) {
                TelemetryEvent e = record.value();
                if (e == null || e.lat() == null || e.lon() == null) {
                    return;
                }
                List<GeofenceRegistry.Area> inside = registry.areasContaining(e.lat(), e.lon());
                Set<Long> current = new HashSet<>();
                GeofenceState prev = store.get(record.key());
                Set<Long> prevIds = prev == null ? Set.of() : prev.insideIds();

                for (GeofenceRegistry.Area area : inside) {
                    current.add(area.id());
                    if (!prevIds.contains(area.id())) {
                        emit(e, area, GeofenceEventType.ENTER, record.timestamp());
                    }
                }
                for (Long id : prevIds) {
                    if (!current.contains(id)) {
                        registry.find(id).ifPresent(area ->
                                emit(e, area, GeofenceEventType.EXIT, record.timestamp()));
                    }
                }
                store.put(record.key(), new GeofenceState(current));
            }

            private void emit(TelemetryEvent e, GeofenceRegistry.Area area,
                              GeofenceEventType type, long ts) {
                GeofenceEvent event = GeofenceEvent.builder()
                        .tenantId(area.tenantId())
                        .vehicleId(e.vehicleId())
                        .geofenceId(area.id())
                        .geofenceName(area.name())
                        .eventType(type)
                        .occurredAt(e.ts())
                        .lat(e.lat())
                        .lon(e.lon())
                        .correlationId(e.correlationId())
                        .build();
                context.forward(new Record<>(e.vehicleId().toString(), event, ts));
            }
        };
    }

    @Override
    public Set<StoreBuilder<?>> stores() {
        return Set.of(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STORE), Serdes.String(), stateSerde));
    }
}
