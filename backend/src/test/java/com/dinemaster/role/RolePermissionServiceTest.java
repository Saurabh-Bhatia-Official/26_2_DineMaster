package com.dinemaster.role;

import com.dinemaster.user.entity.User;
import com.dinemaster.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RolePermissionServiceTest {

    @Mock
    private RoleRepository roles;

    @Mock
    private UserRepository users;

    @InjectMocks
    private RolePermissionService service;

    @Test
    void prefersTenantRoleAndFallsBackToGlobalPermissions() {
        Role tenantRole = role("CASHIER", 7L, "order:view");
        Role globalRole = role("CASHIER", null, "menu:view");
        when(roles.findByCodeAndTenantIdAndActiveTrue("CASHIER", 7L)).thenReturn(Optional.of(tenantRole));
        when(roles.findByCodeAndTenantIdAndActiveTrue("CASHIER", 8L)).thenReturn(Optional.empty());
        when(roles.findByCodeAndTenantIdIsNullAndActiveTrue("CASHIER")).thenReturn(Optional.of(globalRole));

        assertThat(service.permissionsFor("cashier", 7L)).containsExactly("order:view");
        assertThat(service.permissionsFor("CASHIER", 8L)).containsExactly("menu:view");
    }

    @Test
    void replacingTenantRolePermissionsInvalidatesOnlyMatchingUsers() {
        Role role = new Role("CASHIER", "Cashier", 7L);
        User matchingUser = user("CASHIER", 7L, 4);
        User otherTenant = user("CASHIER", 8L, 5);
        User otherRole = user("MANAGER", 7L, 6);
        when(users.findAll()).thenReturn(List.of(matchingUser, otherTenant, otherRole));
        Set<Permission> replacement = Set.of(new Permission("menu:view"));

        service.replacePermissions(role, replacement);

        assertThat(role.getPermissions()).isEqualTo(replacement);
        assertThat(matchingUser.getPermissionVersion()).isEqualTo(5);
        assertThat(otherTenant.getPermissionVersion()).isEqualTo(5);
        assertThat(otherRole.getPermissionVersion()).isEqualTo(6);
        verify(roles).save(role);
        verify(users).save(matchingUser);
    }

    private static Role role(String code, Long tenantId, String permissionCode) {
        Role role = new Role(code, code, tenantId);
        role.setPermissions(Set.of(new Permission(permissionCode)));
        return role;
    }

    private static User user(String role, Long tenantId, int permissionVersion) {
        User user = new User();
        user.setRole(role);
        user.setTenantId(tenantId);
        user.setPermissionVersion(permissionVersion);
        return user;
    }
}
