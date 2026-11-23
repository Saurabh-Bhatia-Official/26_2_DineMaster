package com.dinemaster.orders.repository;





import com.dinemaster.orders.entity.Order;
import com.dinemaster.restaurant.entity.Restaurant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("select o.restaurant.id from Order o where o.id = :id")
    Optional<Long> findRestaurantIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForUpdateById(@Param("id") Long id);

    List<Order> findByRestaurant_IdAndStatusAndParkedTrueOrderByOrderTimeDesc(Long restaurantId, String status);
    Optional<Order> findByRestaurant_IdAndIdempotencyKey(Long restaurantId, String idempotencyKey);
    Optional<Order> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);

    List<Order> findByRestaurant_IdOrderByOrderTimeDesc(Long restaurantId);

    List<Order> findByStatusIgnoreCaseOrderByOrderTimeDesc(String status);

    Optional<Order> findByIdAndTableSession_Id(Long orderId, Long sessionId);

    @Query("select count(o) from Order o where o.diningTable.id = :tableId "
            + "and upper(o.status) not in ('PAID', 'COMPLETED', 'CANCELLED', 'CANCELED', 'VOIDED')")
    long countOpenOrdersForTable(@Param("tableId") Long tableId);
}
