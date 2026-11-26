package com.dinemaster.catalog.entity;
import java.io.Serializable;
import java.util.Objects;
public class BranchProductId implements Serializable {
    private Long branchId;
    private Long productId;
    public BranchProductId() { }
    public BranchProductId(Long branchId, Long productId) { this.branchId = branchId; this.productId = productId; }
    @Override public boolean equals(Object o) { return o instanceof BranchProductId that && Objects.equals(branchId, that.branchId) && Objects.equals(productId, that.productId); }
    @Override public int hashCode() { return Objects.hash(branchId, productId); }
}
