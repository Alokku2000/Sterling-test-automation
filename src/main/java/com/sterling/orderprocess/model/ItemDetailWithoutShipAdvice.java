package com.sterling.orderprocess.model;

public record ItemDetailWithoutShipAdvice(
        String itemId,
        String qty,
        String itemType,
        String uom,
        String scac,
        String shipNode,
        String primeLineNo
) {
}
