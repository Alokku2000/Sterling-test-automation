package com.sterling.orderprocess.model;

import java.util.List;

public record OrderContext(
        String orderNo,
        String enterpriseCode,
        String shipNode,
        String sellerOrgCode,
        String trackingNo,
        String containerNo,
        String timestamp,
        List<ItemDetail> items
) {
}
