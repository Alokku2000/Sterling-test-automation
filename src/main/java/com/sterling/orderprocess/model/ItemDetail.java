package com.sterling.orderprocess.model;

public record ItemDetail(
        String itemId,
        String qty,
        String shipAdviceNo,
        String itemType,
        String uom,
        String scac,
        String shipNode,
        String primeLineNo
) {
}
