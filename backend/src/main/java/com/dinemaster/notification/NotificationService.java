package com.dinemaster.notification;

import com.dinemaster.role.Roles;
import com.dinemaster.security.BranchScopeService;
import com.dinemaster.user.entity.User;
import com.dinemaster.user.repository.UserBranchRepository;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    private final NotificationRepository notifications;
    private final BranchScopeService branchScope;
    private final UserBranchRepository userBranches;

    public NotificationService(NotificationRepository notifications, BranchScopeService branchScope,
            UserBranchRepository userBranches) {
        this.notifications = notifications;
        this.branchScope = branchScope;
        this.userBranches = userBranches;
    }

    public List<NotificationResponse> inbox(Authentication authentication, boolean unreadOnly, int limit) {
        User user = requireTenantUser(authentication);
        return notifications.findInbox(user.getTenantId(), user.getId(), Roles.canonical(user.getRole()),
                Roles.matches(user.getRole(), "OWNER"), userBranches.findBranchIds(user.getId()), unreadOnly, limit);
    }

    public boolean markRead(Authentication authentication, long id) {
        User user = requireTenantUser(authentication);
        return notifications.markRead(id, user.getTenantId(), user.getId(), Roles.canonical(user.getRole()),
                Roles.matches(user.getRole(), "OWNER"), userBranches.findBranchIds(user.getId())) > 0;
    }

    public int markAllRead(Authentication authentication) {
        User user = requireTenantUser(authentication);
        return notifications.markAllRead(user.getTenantId(), user.getId(), Roles.canonical(user.getRole()),
                Roles.matches(user.getRole(), "OWNER"), userBranches.findBranchIds(user.getId()));
    }

    private User requireTenantUser(Authentication authentication) {
        User user = branchScope.requireUser(authentication);
        if (user.getTenantId() == null) {
            throw new AccessDeniedException("Tenant staff account is required");
        }
        return user;
    }
}
