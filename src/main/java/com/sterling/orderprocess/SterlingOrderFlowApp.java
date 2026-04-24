package com.sterling.orderprocess;

import com.sterling.orderprocess.model.ItemDetail;
import com.sterling.orderprocess.model.OrderContext;
import com.sterling.orderprocess.model.OrderEnterpriseDetail;
import com.sterling.orderprocess.model.SterlingSession;
import com.sterling.orderprocess.util.SterlingUtils;

import java.util.List;

public class SterlingOrderFlowApp {
    private final SterlingSession driver;
    private final OrderEnterpriseDetail orderEnterpriseDetail;
    private OrderContext ctx;
    private int count = 1;

    public SterlingOrderFlowApp(SterlingSession driver, OrderEnterpriseDetail orderEnterpriseDetail) {
        this.driver = driver;
        this.orderEnterpriseDetail = orderEnterpriseDetail;
    }

    public void runFullFlow() {
        String currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());
        System.out.println("Current Status - " + currentStatus);

        if (!"Order Delivered".equals(currentStatus)) {
            if ("Created".equals(currentStatus)) {
                printStep("Release Holds");
                SterlingUtils.apiReleaseHolds(orderEnterpriseDetail, driver);

                printStep("Process Order Payments");
                SterlingUtils.apiProcessOrderPayments(orderEnterpriseDetail, driver);

                printStep("Schedule Order");
                SterlingUtils.apiScheduleOrder(orderEnterpriseDetail, driver);
                currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());
            }

            if ("Awaiting DS PO Creation".equals(currentStatus)) {
                printStep("Create Order Invoice For BDRDS");
                SterlingUtils.serviceCreateOrderInvoiceForBdrds(orderEnterpriseDetail, driver);

                printStep("Process Order Payments");
                SterlingUtils.apiProcessOrderPayments(orderEnterpriseDetail, driver);

                printStep("Release Holds");
                SterlingUtils.apiReleaseHolds(orderEnterpriseDetail, driver);

                printStep("Create Chained Order");
                SterlingUtils.apiCreateChainedOrder(orderEnterpriseDetail, driver);
                currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());
            }

            if ("DS PO Created".equals(currentStatus)) {
                ctx = SterlingUtils.buildOrderContext(orderEnterpriseDetail.orderNo(), orderEnterpriseDetail.enterpriseCode());
                List<ItemDetail> itemDetails = SterlingUtils.splitItemDetails(ctx.items());

                printStep("Receive ASN on DS PO");
                SterlingUtils.serviceMkpdsReceiveAsnSync(ctx, itemDetails, driver);
            }

            if ("DTCScheduled".equals(currentStatus) || "Dropped For Fulfillment".equals(currentStatus)) {
                printStep("Release Order");
                SterlingUtils.apiReleaseOrder(orderEnterpriseDetail, driver);

                ctx = SterlingUtils.buildOrderContextWithShipAdviceNo(orderEnterpriseDetail.orderNo(), orderEnterpriseDetail.enterpriseCode());

                printStep("Process Order Drop Ack");
                SterlingUtils.serviceProcessOrderDropAck(ctx, driver);

                printStep("Trigger Agent");
                SterlingUtils.apiTriggerInvAgentReleaseAckOrderInvoice(driver);
                currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());
            }

            ctx = SterlingUtils.buildOrderContextWithShipAdviceNo(orderEnterpriseDetail.orderNo(), orderEnterpriseDetail.enterpriseCode());
            if ("Invoiced PreShip".equals(currentStatus) || "Acknowledged".equals(currentStatus)) {
                printStep("Process Order Payments");
                SterlingUtils.apiProcessOrderPayments(ctx, driver);

                printStep("Confirm Shipment");
                SterlingUtils.apiConfirmShipment(ctx, driver);
                currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());
            }

            List<ItemDetail> cmoItems = SterlingUtils.splitCmoItems(ctx.items());
            List<ItemDetail> nonCmoItems = SterlingUtils.splitNonCmoItems(ctx.items());

            System.out.println("CMO items status - " + cmoItems);
            System.out.println("Non CMO items status - " + nonCmoItems);

            if ("Shipped".equals(currentStatus)) {
                int maxAttempts = 20;

                if (!cmoItems.isEmpty()) {
                    printStep("Deliver Shipment CMO");
                    SterlingUtils.apiDeliverShipmentCmo(ctx, driver);
                    currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());

                    if ("Shipped".equals(currentStatus)) {
                        printStep("Trigger Agent - Create Shipment Invoice");
                        SterlingUtils.apiTriggerInvAgentCreateShipmentInvoice(driver);
                        currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());

                        int attempt = 1;
                        while (!"Invoiced".equals(currentStatus) && attempt <= maxAttempts) {
                            sleepSeconds(15);
                            attempt++;
                            currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());
                        }

                        printStep("Deliver Shipment CMO");
                        SterlingUtils.apiDeliverShipmentCmo(ctx, driver);
                    }
                }

                if (!nonCmoItems.isEmpty()) {
                    printStep("Multi API Non CMO");
                    SterlingUtils.apiMultiApiNonCmo(ctx, nonCmoItems, driver);
                    currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());

                    if ("Shipped".equals(currentStatus)) {
                        printStep("Trigger Agent - Create Shipment Invoice");
                        SterlingUtils.apiTriggerInvAgentCreateShipmentInvoice(driver);
                        currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());

                        int attempt = 1;
                        while (!"Invoiced".equals(currentStatus) && attempt <= maxAttempts) {
                            sleepSeconds(15);
                            attempt++;
                            currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());
                        }

                        printStep("Multi API Non CMO");
                        SterlingUtils.apiMultiApiNonCmo(ctx, nonCmoItems, driver);
                    }
                }

                currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());
            }

            if ("Invoiced".equals(currentStatus)) {
                if (!cmoItems.isEmpty()) {
                    printStep("Deliver Shipment CMO");
                    SterlingUtils.apiDeliverShipmentCmo(ctx, driver);
                }
                if (!nonCmoItems.isEmpty()) {
                    printStep("Multi API Non CMO");
                    SterlingUtils.apiMultiApiNonCmo(ctx, nonCmoItems, driver);
                }
                currentStatus = SterlingUtils.getOrderStatus(orderEnterpriseDetail.orderNo());
            }
        }

        System.out.println("Final " + orderEnterpriseDetail.orderNo() + " Status is - " + currentStatus);
    }

    private void printStep(String label) {
        System.out.println("####### " + count + ". " + label + " ##############################################");
        count++;
    }

    private void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sleep interrupted", e);
        }
    }

    public static void main(String[] args) {
        SterlingUtils.chooseEnvironment();
        OrderEnterpriseDetail orderEnterpriseDetail = SterlingUtils.getOrderInputs();

        try (SterlingSession driver = SterlingUtils.createDriver()) {
            SterlingOrderFlowApp flow = new SterlingOrderFlowApp(driver, orderEnterpriseDetail);
            flow.runFullFlow();
        }
    }
}
