package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.domain.Vehicle;
import com.fleet.vts.gateway.repository.VehicleRepository;
import com.fleet.vts.gateway.security.CurrentUser;
import com.fleet.vts.gateway.web.dto.VehicleDto;
import com.fleet.vts.gateway.web.dto.VehicleRequest;
import com.fleet.vts.gateway.web.mapper.VehicleMapper;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Vehicle CRUD, tenant-scoped from the JWT. Representative of the device/driver/
 * geofence/rule CRUD endpoints, which follow the same repository + MapStruct
 * pattern. Writes require ADMIN or FLEET_MANAGER.
 */
@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    private final VehicleRepository repository;
    private final VehicleMapper mapper;
    private final JdbcTemplate jdbc;

    public VehicleController(VehicleRepository repository, VehicleMapper mapper, JdbcTemplate jdbc) {
        this.repository = repository;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<VehicleDto> list(@AuthenticationPrincipal Jwt jwt) {
        return repository.findByTenantId(CurrentUser.tenantId(jwt)).stream().map(mapper::toDto).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<VehicleDto> get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return repository.findByIdAndTenantId(id, CurrentUser.tenantId(jwt))
                .map(mapper::toDto).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','FLEET_MANAGER')")
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
                                    @Valid @RequestBody VehicleRequest request) {
        String badType = invalidType(request.type());
        if (badType != null) {
            return typeError(badType);
        }
        Vehicle vehicle = mapper.toEntity(request);
        vehicle.setTenantId(CurrentUser.tenantId(jwt));
        // Defaults for optional fields (NOT NULL columns).
        if (vehicle.getType() == null) {
            vehicle.setType("CAR");
        }
        if (vehicle.getFuelType() == null) {
            vehicle.setFuelType("DIESEL");
        }
        if (vehicle.getStatus() == null) {
            vehicle.setStatus("ACTIVE");
        }
        if (vehicle.getOdometerKm() == null) {
            vehicle.setOdometerKm(0L);
        }
        return ResponseEntity.ok(mapper.toDto(repository.save(vehicle)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','FLEET_MANAGER')")
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                    @Valid @RequestBody VehicleRequest request) {
        String badType = invalidType(request.type());
        if (badType != null) {
            return typeError(badType);
        }
        return repository.findByIdAndTenantId(id, CurrentUser.tenantId(jwt))
                .map(vehicle -> {
                    mapper.update(vehicle, request);
                    return ResponseEntity.ok().<Object>body(mapper.toDto(repository.save(vehicle)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The vehicle type is a free string on the request but a foreign key in the schema, so an
     * unknown value used to reach the database and come back as a 500 — a server error for what
     * is really a bad request. Checked here against the taxonomy so it fails as a 400 with the
     * allowed values, before any write.
     *
     * @return the offending type, or {@code null} when it is absent (defaulted later) or valid
     */
    private String invalidType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_type WHERE code = ?", Integer.class, type);
        return (n != null && n > 0) ? null : type;
    }

    private ResponseEntity<Object> typeError(String badType) {
        Set<String> allowed = Set.copyOf(jdbc.queryForList("SELECT code FROM vehicle_type", String.class));
        return ResponseEntity.badRequest().body(java.util.Map.of(
                "error", "UNKNOWN_VEHICLE_TYPE", "type", badType, "allowed", allowed));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return repository.findByIdAndTenantId(id, CurrentUser.tenantId(jwt))
                .map(vehicle -> {
                    repository.delete(vehicle);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
