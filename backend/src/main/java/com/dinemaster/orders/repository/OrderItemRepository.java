package com.dinemaster.orders.repository;





import com.dinemaster.kitchen.entity.Kot;
import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrder_Id(Long orderId);

    List<OrderItem> findByKot_Id(Long kotId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrderItem i where i.id = :itemId and i.kot.id = :kotId")
    Optional<OrderItem> findForUpdateByIdAndKotId(@Param("itemId") Long itemId, @Param("kotId") Long kotId);
    boolean existsByProduct_Id(Long productId);

    @Modifying
    @Query(value = "UPDATE order_items SET kot_id = :kotId WHERE id = :itemId AND order_id = :orderId",
            nativeQuery = true)
    int assignToKot(@Param("itemId") Long itemId, @Param("orderId") Long orderId,
            @Param("kotId") Long kotId);
}
