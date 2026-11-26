package com.dinemaster.diningtable.service;

import com.dinemaster.diningtable.entity.DiningTable;
import com.dinemaster.diningtable.repository.DiningTableRepository;
import com.dinemaster.user.entity.User;
import com.dinemaster.user.repository.UserBranchRepository;
import com.dinemaster.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WaiterAssignmentService {
    private final DiningTableRepository tables;
    private final UserRepository users;
    private final UserBranchRepository userBranches;
    private final JdbcTemplate jdbc;
    public WaiterAssignmentService(DiningTableRepository tables, UserRepository users,
            UserBranchRepository userBranches, JdbcTemplate jdbc) {
        this.tables = tables; this.users = users; this.userBranches = userBranches; this.jdbc = jdbc;
    }

    @Transactional
    public DiningTable assign(Long tableId, Long requestedWaiterId, User actor) {
        DiningTable table = tables.findForUpdateById(tableId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found"));
        Long branchId = table.getRestaurant() == null ? null : table.getRestaurant().getId();
        if (branchId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found");
        boolean manager = com.dinemaster.role.Roles.matches(actor.getRole(), "MANAGER")
                || com.dinemaster.role.Roles.matches(actor.getRole(), "OWNER");
        Long waiterId = requestedWaiterId;
        if (!manager) {
            if (!com.dinemaster.role.Roles.matches(actor.getRole(), "WAITER"))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Waiter assignment is restricted");
            if (waiterId != null && !waiterId.equals(actor.getId()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Waiters may assign only themselves");
            waiterId = actor.getId();
            if (table.getWaiter() != null && !table.getWaiter().getId().equals(actor.getId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Table is assigned to another waiter");
        }
        User next = null;
        if (waiterId != null) {
            Long targetId = waiterId;
            next = users.findById(targetId).filter(User::isActive)
                    .filter(user -> java.util.Objects.equals(user.getTenantId(), actor.getTenantId()))
                    .filter(user -> com.dinemaster.role.Roles.matches(user.getRole(), "WAITER"))
                    .filter(user -> userBranches.isAssigned(user.getId(), branchId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Waiter must be active and assigned to this branch"));
        }
        Long oldId = table.getWaiter() == null ? null : table.getWaiter().getId();
        Long newId = next == null ? null : next.getId();
        if (java.util.Objects.equals(oldId, newId)) return table;
        jdbc.update("UPDATE waiter_assignments SET released_at = CURRENT_TIMESTAMP WHERE table_id = ? AND released_at IS NULL", tableId);
        if (next != null) jdbc.update("INSERT INTO waiter_assignments(branch_id, table_id, waiter_id) VALUES (?, ?, ?)", branchId, tableId, next.getId());
        table.setWaiter(next);
        return tables.save(table);
    }
}
