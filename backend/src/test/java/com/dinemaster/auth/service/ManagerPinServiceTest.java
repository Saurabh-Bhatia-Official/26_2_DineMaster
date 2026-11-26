package com.dinemaster.auth.service;

import com.dinemaster.user.entity.User;
import com.dinemaster.user.repository.UserBranchRepository;
import com.dinemaster.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerPinServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private UserBranchRepository branches;

    @Mock
    private PasswordEncoder encoder;

    @InjectMocks
    private ManagerPinService service;

    @Test
    void ownerCanVerifyPinAndFailureStateIsCleared() {
        User owner = user("OWNER");
        owner.setPinHash("encoded-pin");
        owner.setPinFailedAttempts((short) 2);
        owner.setPinLockedUntil(OffsetDateTime.now().minusMinutes(1));
        when(users.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(encoder.matches("123456", "encoded-pin")).thenReturn(true);

        assertThat(service.verify(" owner ", "123456", 7L, 3L)).isSameAs(owner);

        assertThat(owner.getPinFailedAttempts()).isZero();
        assertThat(owner.getPinLockedUntil()).isNull();
        verify(users).save(owner);
        verify(branches, never()).isAssigned(owner.getId(), 3L);
    }

    @Test
    void repeatedInvalidPinsLockTheAccount() {
        User manager = user("MANAGER");
        manager.setPinHash("encoded-pin");
        when(users.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(branches.isAssigned(10L, 3L)).thenReturn(true);
        when(encoder.matches("123456", "encoded-pin")).thenReturn(false);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> service.verify("manager", "123456", 7L, 3L))
                    .isInstanceOf(ManagerPinService.InvalidManagerPinException.class);
        }

        assertThat(manager.getPinFailedAttempts()).isZero();
        assertThat(manager.getPinLockedUntil()).isAfter(OffsetDateTime.now());
        verify(users, org.mockito.Mockito.times(5)).save(manager);
    }

    @Test
    void rejectsWrongTenantAndMalformedPinWithoutCheckingPassword() {
        User manager = user("MANAGER");
        manager.setPinHash("encoded-pin");
        when(users.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(branches.isAssigned(10L, 3L)).thenReturn(true);

        assertThatThrownBy(() -> service.verify("manager", "123", 8L, 3L))
                .isInstanceOf(ManagerPinService.InvalidManagerPinException.class);
        assertThatThrownBy(() -> service.verify("manager", "12x456", 7L, 3L))
                .isInstanceOf(ManagerPinService.InvalidManagerPinException.class);

        verify(encoder, never()).matches(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    private static User user(String role) {
        User user = new User();
        user.setId(10L);
        user.setUsername(role.toLowerCase());
        user.setRole(role);
        user.setTenantId(7L);
        user.setActive(true);
        return user;
    }
}
