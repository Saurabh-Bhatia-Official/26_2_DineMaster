package com.dinemaster.catalog.service;

import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.cache.CacheInvalidationService;
import com.dinemaster.orders.repository.OrderItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository products;

    @Mock
    private OrderItemRepository orderItems;

    @Mock
    private CacheInvalidationService cacheInvalidation;

    @InjectMocks
    private ProductService service;

    @Test
    void checksTrimmedSkuAndExcludesCurrentProductWhenUpdating() {
        when(products.existsByTenantIdAndSkuIgnoreCaseAndIdNotAndDeletedAtIsNull(4L, "ABC-1", 8L))
                .thenReturn(true);

        assertThat(service.skuTaken(4L, " ABC-1 ", 8L)).isTrue();
        assertThat(service.skuTaken(4L, "  ", 8L)).isFalse();
        verify(products).existsByTenantIdAndSkuIgnoreCaseAndIdNotAndDeletedAtIsNull(4L, "ABC-1", 8L);
        verify(products, never()).existsByTenantIdAndSkuIgnoreCaseAndIdNotAndDeletedAtIsNull(4L, "  ", 8L);
    }

    @Test
    void deletesUnusedProductAndArchivesProductReferencedByAnOrder() {
        Product unused = product(1L);
        Product referenced = product(2L);
        when(orderItems.existsByProduct_Id(1L)).thenReturn(false);
        when(orderItems.existsByProduct_Id(2L)).thenReturn(true);
        when(products.save(referenced)).thenReturn(referenced);

        assertThat(service.deleteOrArchive(unused)).isFalse();
        assertThat(service.deleteOrArchive(referenced)).isTrue();

        verify(products).delete(unused);
        verify(products).save(referenced);
        assertThat(referenced.isAvailable()).isFalse();
        assertThat(referenced.getDeletedAt()).isNotNull();
    }

    private static Product product(Long id) {
        Product product = new Product();
        product.setId(id);
        return product;
    }
}
