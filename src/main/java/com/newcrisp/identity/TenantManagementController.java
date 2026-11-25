package com.newcrisp.identity;

import static com.newcrisp.identity.TenantManagementStore.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff")
class TenantManagementController {
    private static final String VERSION = "0|[1-9][0-9]{0,18}";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final TenantManagementService management;
    private final WebTokens tokens;
    private final ChatIdentity identity;

    TenantManagementController(TenantManagementService management, WebTokens tokens, ChatIdentity identity) {
        this.management = management;
        this.tokens = tokens;
        this.identity = identity;
    }

    @GetMapping("/departments")
    ResponseEntity<List<Department>> departments(HttpServletRequest request) {
        return ok(management.departments(sid(request, false)));
    }

    @PostMapping("/departments")
    ResponseEntity<Department> create(@Valid @RequestBody DepartmentInput input, HttpServletRequest request) {
        var result = management.createDepartment(sid(request, true), input.code(), input.name());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(result);
    }

    @PutMapping("/departments/{id}")
    ResponseEntity<Department> rename(@PathVariable String id, @Valid @RequestBody Rename input, HttpServletRequest request) {
        return ok(management.rename(sid(request, true), id, input.name(), input.version()));
    }

    @GetMapping("/members")
    ResponseEntity<List<Member>> members(HttpServletRequest request) {
        return ok(management.members(sid(request, false)));
    }

    @PutMapping("/members/{id}/roles")
    ResponseEntity<Void> roles(@PathVariable String id, @Valid @RequestBody Roles input, HttpServletRequest request) {
        management.roles(sid(request, true), id, input.roles().stream().map(role -> new RoleGrant(role.departmentId(), role.role())).toList(),
                input.version());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/members/{id}/disable")
    ResponseEntity<Void> disable(@PathVariable String id, @Valid @RequestBody Disable input, HttpServletRequest request) {
        management.disable(sid(request, true), id, input.version());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private String sid(HttpServletRequest request, boolean write) {
        if (write) identity.requireBrowserOrigin(request);
        return tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), ChatIdentity.Side.STAFF).subject().sid();
    }

    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    record DepartmentInput(@NotBlank @Pattern(regexp = StaffController.TENANT_CODE) String code,
                           @NotBlank @Size(max = 120) String name) {}
    record Rename(@NotBlank @Size(max = 120) String name, @NotNull @Pattern(regexp = VERSION) String version) {}
    record Roles(@NotNull @Size(max = 200) List<@NotNull @Valid GrantInput> roles,
                 @NotNull @Pattern(regexp = VERSION) String version) {}
    record GrantInput(@NotNull @Pattern(regexp = UUID) String departmentId, @NotNull Role role) {}
    record Disable(@NotNull @Pattern(regexp = VERSION) String version) {}
}
