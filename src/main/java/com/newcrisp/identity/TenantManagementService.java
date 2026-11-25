package com.newcrisp.identity;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class TenantManagementService {
    private final StaffService staff;
    private final TenantManagementStore store;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TenantManagementService(StaffService staff, TenantManagementStore store, ApplicationEventPublisher events, Clock clock) {
        this.staff = staff;
        this.store = store;
        this.events = events;
        this.clock = clock;
    }

    List<TenantManagementStore.Department> departments(String sid) { return store.departments(staff.admin(sid).tenantId()); }
    List<TenantManagementStore.Member> members(String sid) { return store.members(staff.admin(sid).tenantId()); }

    @Transactional
    TenantManagementStore.Department createDepartment(String sid, String code, String name) {
        long tenant = staff.admin(sid).tenantId();
        try {
            return store.createDepartment(tenant, code, name.strip());
        } catch (DuplicateKeyException duplicate) {
            throw new IdentityError(HttpStatus.CONFLICT, "DEPARTMENT_ALREADY_EXISTS");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    TenantManagementStore.Department rename(String sid, String id, String name, String expected) {
        long tenant = staff.admin(sid).tenantId();
        var department = store.lockDepartment(tenant, id).orElseThrow(IdentityError::notFound);
        requireVersion(expected, Long.parseLong(department.version()));
        String normalized = name.strip();
        if (department.name().equals(normalized)) return department;
        store.rename(tenant, id, normalized);
        return new TenantManagementStore.Department(id, department.code(), normalized,
                Long.toString(Long.parseLong(department.version()) + 1));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void roles(String sid, String id, List<TenantManagementStore.RoleGrant> roles, String expected) {
        long tenant = staff.admin(sid).tenantId();
        var current = store.lockMember(tenant, id).orElseThrow(IdentityError::notFound);
        requireVersion(expected, current.version());
        if (!current.enabled()) throw new IdentityError(HttpStatus.CONFLICT, "STAFF_DISABLED");
        var grants = Set.copyOf(roles);
        if (!store.containsDepartments(tenant, grants)) throw IdentityError.notFound();
        if (store.roles(tenant, id).equals(grants)) return;
        store.replaceRoles(tenant, id, grants);
        events.publishEvent(new ChatIdentity.StaffAccessChanged(tenant, id, current.version() + 1, false));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void disable(String sid, String id, String expected) {
        long tenant = staff.admin(sid).tenantId();
        var current = store.lockMember(tenant, id).orElseThrow(IdentityError::notFound);
        requireVersion(expected, current.version());
        if (!current.enabled()) return;
        store.disable(tenant, id, LocalDateTime.now(clock));
        events.publishEvent(new ChatIdentity.StaffAccessChanged(tenant, id, current.version() + 1, true));
    }

    private static void requireVersion(String expected, long actual) {
        if (!Long.toString(actual).equals(expected)) throw new IdentityError(HttpStatus.CONFLICT, "MANAGEMENT_VERSION_CHANGED");
    }
}
