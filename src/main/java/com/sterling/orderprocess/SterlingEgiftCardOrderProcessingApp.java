package com.sterling.orderprocess;

import com.sterling.orderprocess.model.OrderEnterpriseDetail;
import com.sterling.orderprocess.model.SterlingSession;
import com.sterling.orderprocess.util.SterlingUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SterlingEgiftCardOrderProcessingApp {
    public static void main(String[] args) {
        String envChoice = SterlingUtils.prompt("Enter environment (dev, qa, prod) [default=dev]: ");
        String env = SterlingUtils.loadEnv(envChoice.isEmpty() ? "dev" : envChoice);
        System.out.println("Running in environment: " + env);

        OrderEnterpriseDetail orderInput = SterlingUtils.getOrderInputs();

        try (SterlingSession driver = SterlingUtils.createDriver()) {
            String processOrderPaymentsTemplate = "<Order DocumentType=\"0001\" EnterpriseCode=\"%s\" OrderNo=\"%s\"/> "
                    .formatted(orderInput.enterpriseCode(), orderInput.orderNo());
            System.out.println("processOrderPayments --> " + processOrderPaymentsTemplate);
            System.out.println();
            SterlingUtils.runSterling(driver, "api", "processOrderPayments", processOrderPaymentsTemplate);
            SterlingUtils.waitAndReturn(driver, 10);

            String scheduleOrderTemplate = """
                    <ScheduleOrder DocumentType="0001" EnterpriseCode="%s" OrderHeaderKey="" OrderNo="%s" />
                    """.formatted(orderInput.enterpriseCode(), orderInput.orderNo());
            System.out.println("scheduleOrder --> " + scheduleOrderTemplate);
            System.out.println();
            SterlingUtils.runSterling(driver, "api", "scheduleOrder", scheduleOrderTemplate);
            SterlingUtils.waitAndReturn(driver, 10);

            String releaseOrderTemplate = "<ReleaseOrder DocumentType=\"0001\" EnterpriseCode=\"%s\" OrderNo=\"%s\"/>"
                    .formatted(orderInput.enterpriseCode(), orderInput.orderNo());
            System.out.println("releaseOrder --> " + releaseOrderTemplate);
            System.out.println();
            SterlingUtils.runSterling(driver, "api", "releaseOrder", releaseOrderTemplate);
            SterlingUtils.waitAndReturn(driver, 10);

            String timestamp = SterlingUtils.getTimestamp();
            var orderContext = SterlingUtils.buildOrderContextWithShipAdviceNo(orderInput.orderNo(), orderInput.enterpriseCode());
            String enterpriseCode = orderContext.enterpriseCode();
            String shipNode = orderContext.shipNode();
            String itemDetailsInput = buildItemDetailsInput(orderContext.items());

            System.out.println("Item_details_input - " + itemDetailsInput);

            List<String[]> itemDetails = new ArrayList<>();
            for (String pair : itemDetailsInput.split(",")) {
                String[] parts = pair.trim().split(":");
                if (parts.length >= 3) {
                    itemDetails.add(new String[]{parts[0].trim(), parts[1].trim(), parts[2].trim()});
                }
            }

            Set<String> seenShipAdvice = new HashSet<>();
            List<String> processOrderDropAckLines = new ArrayList<>();
            for (String[] itemDetail : itemDetails) {
                String itemShipAdviceNo = itemDetail[2];
                if (seenShipAdvice.add(itemShipAdviceNo)) {
                    processOrderDropAckLines.add("""
                            <OrderRelease ConsolidatorAddressCode=" " DocumentType="0001" EnterpriseCode="%s"
                                          PKMSReceiptTimeStamp="%s" OrderNo="%s" ShipAdviceNo="%s"
                                          ShipNode="%s" TransactionType="ACKNOWLEDGEMENT" />
                            """.formatted(enterpriseCode, timestamp, orderInput.orderNo(), itemShipAdviceNo, shipNode));
                }
            }

            String processOrderDropAckPayload = String.join(System.lineSeparator(), processOrderDropAckLines);
            System.out.println("ProcessOrderDropAck");
            System.out.println();
            System.out.println(processOrderDropAckPayload);
            System.out.println();
            SterlingUtils.runSterling(driver, "service", "ProcessOrderDropAck", processOrderDropAckPayload);
            SterlingUtils.waitAndReturn(driver, 10);

            System.out.println("triggerAgent --> <TriggerAgent CriteriaId='RELEASE_ACK_ORDER_INVOICE'/>");
            System.out.println();
            SterlingUtils.runSterling(driver, "api", "triggerAgent", "<TriggerAgent CriteriaId='RELEASE_ACK_ORDER_INVOICE'/>");
            SterlingUtils.waitAndReturn(driver, 10);

            System.out.println("processOrderPayments --> " + processOrderPaymentsTemplate);
            System.out.println();
            SterlingUtils.runSterling(driver, "api", "processOrderPayments", processOrderPaymentsTemplate);
            SterlingUtils.waitAndReturn(driver, 15);
        }
    }

    private static String buildItemDetailsInput(List<com.sterling.orderprocess.model.ItemDetail> items) {
        List<String> result = new ArrayList<>();
        for (com.sterling.orderprocess.model.ItemDetail item : items) {
            result.add("%s:%s:%s".formatted(item.itemId(), item.qty(), item.shipAdviceNo()));
        }
        return String.join(",", result);
    }
}
