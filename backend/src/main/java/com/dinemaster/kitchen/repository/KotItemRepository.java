package com.dinemaster.kitchen.repository;
import com.dinemaster.kitchen.entity.KotItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface KotItemRepository extends JpaRepository<KotItem, Long> {
    List<KotItem> findByKot_IdOrderByIdAsc(Long kotId);
}
