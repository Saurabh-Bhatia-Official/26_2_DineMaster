package com.dinemaster.kitchen.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "kot_items")
public class KotItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kot_id", nullable = false)
    private Kot kot;
    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;
    @Column(name = "product_id", nullable = false)
    private Long productId;
    @Column(name = "item_name_snapshot", nullable = false, length = 150)
    private String itemName;
    @Column(nullable = false)
    private Integer quantity;
    @Column(name = "instructions_snapshot", columnDefinition = "TEXT")
    private String instructions;
    public Long getId() { return id; }
    public Kot getKot() { return kot; }
    public void setKot(Kot kot) { this.kot = kot; }
    public Long getOrderItemId() { return orderItemId; }
    public void setOrderItemId(Long orderItemId) { this.orderItemId = orderItemId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
}
