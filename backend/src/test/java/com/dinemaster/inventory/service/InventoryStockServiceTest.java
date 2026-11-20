package com.dinemaster.inventory.service;

import com.dinemaster.inventory.entity.InventoryItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryStockServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @InjectMocks
    private InventoryStockService service;

    @Test
    void consumesOldestBatchesFirstAndUpdatesItemStock() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 1L, "quantity_on_hand", new BigDecimal("2.000")),
                Map.of("id", 2L, "quantity_on_hand", new BigDecimal("3.000"))));
        InventoryItem item = item("5.000");

        service.consume(item, new BigDecimal("3.500"), "RECIPE", "ORDER", 9L);

        assertThat(item.getCurrentStock()).isEqualByComparingTo("1.500");
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("UPDATE stock_batches"),
                eq(new BigDecimal("2.000")), eq(1L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("UPDATE stock_batches"),
                eq(new BigDecimal("1.500")), eq(2L));
        verify(jdbc, org.mockito.Mockito.times(2)).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO stock_movements"),
                any(Object[].class));
    }

    @Test
    void receivingNonPositiveQuantityDoesNotCreateStockRecords() {
        service.receive(item("0.000"), BigDecimal.ZERO, null, BigDecimal.ZERO, "PURCHASE", "INITIAL", 1L);

        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    private static InventoryItem item(String currentStock) {
        InventoryItem item = new InventoryItem();
        item.setId(12L);
        item.setItemName("Tomatoes");
        item.setCurrentStock(new BigDecimal(currentStock));
        return item;
    }
}
