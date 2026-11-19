package com.dinemaster.auth.service;

import com.dinemaster.role.Roles;
import com.dinemaster.user.entity.User;
import com.dinemaster.user.repository.UserBranchRepository;
import com.dinemaster.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;

@Service
public class ManagerPinService {
    private static final short MAX_FAILURES = 5;
    private final UserRepository users;
    private final UserBranchRepository branches;
    private final PasswordEncoder encoder;

    public ManagerPinService(UserRepository users, UserBranchRepository branches, PasswordEncoder encoder) {
        this.users = users; this.branches = branches; this.encoder = encoder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = InvalidManagerPinException.class)
    public User verify(String username, String pin, Long tenantId, Long branchId) {
        User user = users.findByUsername(username == null ? "" : username.trim()).orElse(null);
        if (user == null || !user.isActive() || !java.util.Objects.equals(user.getTenantId(), tenantId)
                || !(Roles.matches(user.getRole(), "MANAGER") || Roles.matches(user.getRole(), "OWNER"))
                || !(Roles.matches(user.getRole(), "OWNER") || branches.isAssigned(user.getId(), branchId)))
            throw new InvalidManagerPinException();
        OffsetDateTime now = OffsetDateTime.now();
        if (user.getPinLockedUntil() != null && user.getPinLockedUntil().isAfter(now))
            throw new InvalidManagerPinException();
        if (user.getPinHash() == null || pin == null || !pin.matches("[0-9]{6}") || !encoder.matches(pin, user.getPinHash())) {
            short attempts = (short) (user.getPinFailedAttempts() + 1);
            if (attempts >= MAX_FAILURES) {
                user.setPinFailedAttempts((short) 0);
                user.setPinLockedUntil(now.plusMinutes(15));
            } else user.setPinFailedAttempts(attempts);
            users.save(user);
            throw new InvalidManagerPinException();
        }
        user.setPinFailedAttempts((short) 0); user.setPinLockedUntil(null);
        users.save(user);
        return user;
    }

    public static final class InvalidManagerPinException extends RuntimeException { }
}
