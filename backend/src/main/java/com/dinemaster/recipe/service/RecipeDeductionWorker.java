package com.dinemaster.recipe.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RecipeDeductionWorker {
    private final JdbcTemplate jdbc;
    private final RecipeDeductionService deduction;

    public RecipeDeductionWorker(JdbcTemplate jdbc, RecipeDeductionService deduction) {
        this.jdbc = jdbc;
        this.deduction = deduction;
    }

    @Scheduled(fixedDelayString = "${dinemaster.recipe-deduction.poll-ms:5000}")
    public void processQueued() {
        jdbc.update("UPDATE recipe_deduction_jobs SET status='QUEUED', updated_at=CURRENT_TIMESTAMP WHERE status='PROCESSING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'");
        List<Long> ids = jdbc.query("SELECT id FROM recipe_deduction_jobs WHERE status='QUEUED' ORDER BY created_at LIMIT 20", (rs, n) -> rs.getLong(1));
        for (Long id : ids) {
            int claimed = jdbc.update("UPDATE recipe_deduction_jobs SET status='PROCESSING', attempts=attempts+1, updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'", id);
            if (claimed == 0) continue;
            Long orderId = jdbc.queryForObject("SELECT order_id FROM recipe_deduction_jobs WHERE id=?", Long.class, id);
            try {
                deduction.process(orderId);
                jdbc.update("UPDATE recipe_deduction_jobs SET status='COMPLETED', error=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (message.length() > 4000) message = message.substring(0, 4000);
                jdbc.update("UPDATE recipe_deduction_jobs SET status='FAILED', error=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", message, id);
            }
        }
    }
}
