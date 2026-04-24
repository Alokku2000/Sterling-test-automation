package com.sterling.orderprocess.util;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.SelectOption;
import com.sterling.orderprocess.model.ItemDetail;
import com.sterling.orderprocess.model.ItemDetailWithoutShipAdvice;
import com.sterling.orderprocess.model.OrderContext;
import com.sterling.orderprocess.model.OrderEnterpriseDetail;
import com.sterling.orderprocess.model.SterlingSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public final class SterlingUtils {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Scanner SCANNER = new Scanner(System.in);

    private SterlingUtils() {
    }

    public static String chooseEnvironment() {
        String envChoice = prompt("Enter environment (eqa1, eqa2, eqa3) [default=eqa1]: ");
        String env = loadEnv(envChoice.isEmpty() ? "eqa1" : envChoice);
        System.out.println("Running in environment: " + env);
        return env;
    }

    public static OrderEnterpriseDetail getOrderInputs() {
        String orderNo = prompt("Enter OrderNo: ");
        String enterpriseCode = prompt("Enter EnterpriseCode: ");
        return new OrderEnterpriseDetail(orderNo, enterpriseCode);
    }

    public static String prompt(String label) {
        System.out.print(label);
        return SCANNER.nextLine().trim();
    }

    public static SterlingSession createDriver() {
        String startUrl = requireEnv("STERLING_URL");
        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
        Page page = browser.newPage();
        page.navigate(startUrl);
        sleepSeconds(20);
        return new SterlingSession(playwright, browser, page);
    }

    public static String loadEnv(String envOverride) {
        String env = (envOverride == null || envOverride.isBlank())
                ? System.getenv().getOrDefault("APP_ENV", "dev")
                : envOverride;

        Path envFile = Path.of(".env." + env);
        if (!Files.exists(envFile)) {
            throw new IllegalStateException("Environment file not found: " + envFile.toAbsolutePath());
        }

        try {
            for (String rawLine : Files.readAllLines(envFile)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int idx = line.indexOf('=');
                String key = line.substring(0, idx).trim();
                String value = stripQuotes(line.substring(idx + 1).trim());
                if (!key.isEmpty()) {
                    EnvOverrides.set(key, value);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read environment file: " + envFile.toAbsolutePath(), e);
        }
        return env;
    }

    public static String getOrderStatus(String orderNo) {
        String query = """
                select ys.DESCRIPTION
                  from yantra_owner.yfs_order_header yoh
                  inner join yantra_owner.yfs_order_release_status yors
                    on yoh.ORDER_HEADER_KEY = yors.ORDER_HEADER_KEY
                  inner join YANTRA_OWNER.yfs_status ys
                    on yors.status = ys.status
                 where yors.STATUS_QUANTITY > 0
                   and ys.PROCESS_TYPE_KEY = 'ORDER_FULFILLMENT'
                   and yoh.order_no = ?
                 order by yors.STATUS_DATE desc
                 fetch first 1 row only
                """;

        List<Map<String, Object>> rows = executeQuery(query, List.of(orderNo));
        if (rows.isEmpty()) {
            return null;
        }
        return safeTrim(rows.get(0).values().iterator().next());
    }

    public static int getShippedStatusCount(String orderNo) {
        String query = """
                select count(*)
                  from yantra_owner.yfs_task_q
                 where available_date < sysdate
                   and transaction_key = (
                        select ytq.TRANSACTION_KEY
                          from yantra_owner.YFS_SHIPMENT_LINE ysl
                          inner join yantra_owner.YFS_TASK_Q ytq
                            on ytq.DATA_KEY = ysl.SHIPMENT_KEY
                          inner join yantra_owner.YFS_TRANSACTION yt
                            on yt.TRANSACTION_KEY = ytq.TRANSACTION_KEY
                           and yt.TRANNAME = 'CreateShipmentInvoice'
                         where order_no = ?
                   )
                """;

        List<Map<String, Object>> rows = executeQuery(query, List.of(orderNo));
        if (rows.isEmpty()) {
            return 0;
        }
        Number count = (Number) rows.get(0).values().iterator().next();
        System.out.println(count.intValue());
        return count.intValue();
    }

    public static void runSterling(SterlingSession driver, String mode, String name, String xml) {
        System.out.println(mode + " --> " + name);
        System.out.println("XML message --> " + xml);
        System.out.println();

        Page page = driver.page();
        String serviceNameSelector = "xpath=//*[@id=\"frmInteropTest\"]/table[1]/tbody/tr[3]/td[2]/input";
        String userIdSelector = "xpath=//*[@id=\"frmInteropTest\"]/table[1]/tbody/tr[5]/td[2]/input";
        String messageSelector = "xpath=//*[@id=\"InteropApiData\"]";
        String submitSelector = "xpath=//*[@id=\"frmInteropTest\"]/table[1]/tbody/tr[1]/td/input";

        page.locator(serviceNameSelector).fill("");
        page.locator(userIdSelector).fill("");
        page.locator(messageSelector).fill("");

        if ("service".equalsIgnoreCase(mode)) {
            String isServiceSelector = "xpath=//*[@id=\"frmInteropTest\"]/table[1]/tbody/tr[2]/td[2]/input";
            if (!page.locator(isServiceSelector).isChecked()) {
                page.locator(isServiceSelector).check();
            }
            page.locator(serviceNameSelector).fill(name);
        } else if ("api".equalsIgnoreCase(mode)) {
            page.locator("#ApiName").selectOption(new SelectOption().setLabel(name));
        } else {
            throw new IllegalArgumentException("mode must be either 'service' or 'api'");
        }

        page.locator(userIdSelector).fill("admin");
        page.locator(messageSelector).fill(xml);
        page.locator(submitSelector).click();
    }

    public static void waitAndReturn(SterlingSession driver, int seconds) {
        sleepSeconds(seconds);
        driver.page().goBack();
    }

    public static void apiReleaseHolds(OrderEnterpriseDetail ctx, SterlingSession driver) {
        String query = """
                select yoht.HOLD_TYPE, yoht.ORDER_LINE_KEY
                  from YANTRA_OWNER.yfs_order_hold_type yoht
                  inner join yantra_owner.yfs_order_header yoh
                    on yoh.ORDER_HEADER_KEY = yoht.ORDER_HEADER_KEY
                 where yoht.STATUS <> 1300
                   and yoh.order_no = ?
                """;

        List<Map<String, Object>> rows = executeQuery(query, List.of(ctx.orderNo()));
        if (rows.isEmpty()) {
            System.out.println("No holds for Order - " + ctx.orderNo());
            return;
        }

        for (Map<String, Object> row : rows) {
            String holdType = safeTrim(row.get("HOLD_TYPE"));
            String orderLineKey = safeTrim(row.get("ORDER_LINE_KEY"));

            System.out.println("Hold Type - " + holdType);
            System.out.println("order_ln_key - " + orderLineKey);

            String xml;
            if (orderLineKey == null || "None".equals(orderLineKey)) {
                xml = """
                        <Order OrderNo="%s" EnterpriseCode="%s" DocumentType="0001">
                            <OrderHoldTypes>
                                <OrderHoldType Status="1300" HoldType="%s"/>
                            </OrderHoldTypes>
                        </Order>
                        """.formatted(ctx.orderNo(), ctx.enterpriseCode(), holdType);
            } else {
                xml = """
                        <Order OrderNo="%s" EnterpriseCode="%s" DocumentType="0001">
                            <OrderLines>
                                <OrderLine OrderLineKey="%s">
                                    <OrderHoldTypes>
                                        <OrderHoldType Status="1300" HoldType="%s"/>
                                    </OrderHoldTypes>
                                </OrderLine>
                            </OrderLines>
                        </Order>
                        """.formatted(ctx.orderNo(), ctx.enterpriseCode(), orderLineKey, holdType);
            }

            runSterling(driver, "api", "changeOrder", xml);
            waitAndReturn(driver, 10);
        }
    }

    public static void apiProcessOrderPayments(OrderEnterpriseDetail ctx, SterlingSession driver) {
        apiProcessOrderPayments(new OrderContext(
                ctx.orderNo(),
                ctx.enterpriseCode(),
                null,
                null,
                null,
                null,
                getTimestamp(),
                List.of()
        ), driver);
    }

    public static void apiProcessOrderPayments(OrderContext ctx, SterlingSession driver) {
        String xml = "<Order DocumentType=\"0001\" EnterpriseCode=\"%s\" OrderNo=\"%s\"/>"
                .formatted(ctx.enterpriseCode(), ctx.orderNo());
        runSterling(driver, "api", "processOrderPayments", xml);
        waitAndReturn(driver, 10);
    }

    public static void apiScheduleOrder(OrderEnterpriseDetail ctx, SterlingSession driver) {
        String xml = """
                <ScheduleOrder DocumentType="0001" EnterpriseCode="%s" OrderHeaderKey="" OrderNo="%s" />
                """.formatted(ctx.enterpriseCode(), ctx.orderNo());
        runSterling(driver, "api", "scheduleOrder", xml);
        waitAndReturn(driver, 10);
    }

    public static void apiReleaseOrder(OrderEnterpriseDetail ctx, SterlingSession driver) {
        String xml = "<ReleaseOrder DocumentType=\"0001\" EnterpriseCode=\"%s\" OrderNo=\"%s\"/>"
                .formatted(ctx.enterpriseCode(), ctx.orderNo());
        runSterling(driver, "api", "releaseOrder", xml);
        waitAndReturn(driver, 15);
    }

    public static void serviceProcessOrderDropAck(OrderContext ctx, SterlingSession driver) {
        Set<String> seenShipAdvice = new HashSet<>();
        for (ItemDetail item : ctx.items()) {
            if (item.shipAdviceNo() == null || seenShipAdvice.contains(item.shipAdviceNo())) {
                continue;
            }
            String line = """
                    <OrderRelease ConsolidatorAddressCode=" " DocumentType="0001"
                                  EnterpriseCode="%s" PKMSReceiptTimeStamp="%s"
                                  OrderNo="%s" ShipAdviceNo="%s"
                                  ShipNode="%s" TransactionType="ACKNOWLEDGEMENT" />
                    """.formatted(
                    ctx.enterpriseCode(),
                    ctx.timestamp(),
                    ctx.orderNo(),
                    item.shipAdviceNo(),
                    item.shipNode()
            );
            seenShipAdvice.add(item.shipAdviceNo());
            runSterling(driver, "service", "ProcessOrderDropAck", line);
            waitAndReturn(driver, 15);
        }
    }

    public static void apiTriggerInvAgentReleaseAckOrderInvoice(SterlingSession driver) {
        runSterling(driver, "api", "triggerAgent", "<TriggerAgent CriteriaId='RELEASE_ACK_ORDER_INVOICE'/>");
        waitAndReturn(driver, 10);
    }

    public static void apiTriggerInvAgentCreateShipmentInvoice(SterlingSession driver) {
        runSterling(driver, "api", "triggerAgent", "<TriggerAgent CriteriaId='CREATE_SHIPMENT_INVOICE'/>");
        waitAndReturn(driver, 10);
    }

    public static void apiConfirmShipment(OrderContext ctx, SterlingSession driver) {
        for (ItemDetail item : ctx.items()) {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Shipment Action="Create" DocumentType="0001" CarrierServiceCode="GROUND"
                              EnterpriseCode="%s" SCAC="%s"
                              SellerOrganizationCode="%s" ShipNode="%s"
                              TrackingNo="%s" ActualShipmentDate="%s"
                              ExpectedDeliveryDate="%s" PkMSShipmentTimestamp="%s"
                              ConsolidatorAddressCode="">
                        <ShipmentLines>
                            <ShipmentLine Action="Create" DocumentType="0001" ItemID="%s" OrderNo="%s" PrimeLineNo="%s"
                                          ProductClass="" Quantity="%s" UnitOfMeasure="EACH" SubLineNo="1" ShipAdviceNo="%s"/>
                        </ShipmentLines>
                        <Containers>
                            <Container Action="Create" ContainerNo="%s" TrackingNo="%s"
                                       Ucc128code="" ContainerGrossWeight="12.55" ContainerHeight="1.5"
                                       ContainerLength="22" ContainerWidth="15">
                                <ContainerDetails>
                                    <ContainerDetail Action="Create" Quantity="%s">
                                        <ShipmentLine OrderNo="%s" PrimeLineNo="%s" SubLineNo="1" ShipAdviceNo="%s"/>
                                        <ShipmentTagSerials>
                                            <ShipmentTagSerial Quantity="%s" ShipByDate="%s"/>
                                        </ShipmentTagSerials>
                                    </ContainerDetail>
                                </ContainerDetails>
                            </Container>
                        </Containers>
                        <ToAddress AddressLine1="867 Peachtree St NE" AddressLine2="" AddressLine3=""
                                   City="ATLANTA" Country="US" State="GA" ZipCode="30308"/>
                        <Extn ExtnCarrierService="UPS"/>
                    </Shipment>
                    """.formatted(
                    ctx.enterpriseCode(),
                    item.scac(),
                    ctx.sellerOrgCode(),
                    item.shipNode(),
                    ctx.trackingNo(),
                    ctx.timestamp(),
                    ctx.timestamp(),
                    ctx.timestamp(),
                    item.itemId(),
                    ctx.orderNo(),
                    item.primeLineNo(),
                    item.qty(),
                    item.shipAdviceNo(),
                    ctx.containerNo(),
                    ctx.trackingNo(),
                    item.qty(),
                    ctx.orderNo(),
                    item.primeLineNo(),
                    item.shipAdviceNo(),
                    item.qty(),
                    ctx.timestamp()
            );
            runSterling(driver, "api", "confirmShipment", xml);
            waitAndReturn(driver, 15);
        }
    }

    public static List<ItemDetail> splitCmoItems(List<ItemDetail> items) {
        List<ItemDetail> result = new ArrayList<>();
        for (ItemDetail item : items) {
            if ("CMO".equalsIgnoreCase(item.itemType())) {
                result.add(item);
            }
        }
        return result;
    }

    public static List<ItemDetail> splitNonCmoItems(List<ItemDetail> items) {
        List<ItemDetail> result = new ArrayList<>();
        for (ItemDetail item : items) {
            if (!"CMO".equalsIgnoreCase(item.itemType())) {
                result.add(item);
            }
        }
        return result;
    }

    public static List<ItemDetail> splitItemDetails(List<ItemDetail> items) {
        return new ArrayList<>(items);
    }

    public static void apiDeliverShipmentCmo(OrderContext ctx, SterlingSession driver) {
        String query = """
                select ys.SHIPMENT_NO
                  from YANTRA_OWNER.YFS_SHIPMENT ys
                 where ys.SHIPMENT_KEY in (
                       select ysl.SHIPMENT_KEY
                         from YANTRA_OWNER.YFS_SHIPMENT_LINE ysl
                        where ysl.order_no = ?
                 )
                """;

        List<Map<String, Object>> rows = executeQuery(query, List.of(ctx.orderNo()));
        if (rows.isEmpty()) {
            System.out.println("No shipment # is generated for CMO Order - " + ctx.orderNo());
            return;
        }

        for (Map<String, Object> row : rows) {
            String shipmentNo = safeTrim(row.get("SHIPMENT_NO"));
            System.out.println("shipmentNo for CMO Order - " + shipmentNo);

            String xml = """
                    <Shipment ShipmentNo="%s" SellerOrganizationCode="%s" ShipNode="%s"/>
                    """.formatted(shipmentNo, ctx.sellerOrgCode(), ctx.shipNode());

            runSterling(driver, "api", "deliverShipment", xml);
            waitAndReturn(driver, 10);
        }
    }

    public static void apiMultiApiNonCmo(OrderContext ctx, List<ItemDetail> nonCmoItems, SterlingSession driver) {
        StringBuilder containerDetails = new StringBuilder();
        StringBuilder orderLines = new StringBuilder();
        for (ItemDetail item : nonCmoItems) {
            containerDetails.append("""
                               <ContainerDetail ItemID="%s" Quantity="%s">
                                   <ShipmentLine OrderNo="%s" PrimeLineNo="%s" SubLineNo="1"/>
                               </ContainerDetail>
                    """.formatted(item.itemId(), item.qty(), ctx.orderNo(), item.primeLineNo()));

            orderLines.append("""
                               <OrderLine OrderedQty="%s" PrimeLineNo="%s" SubLineNo="1">
                                   <Item ItemID="%s"/>
                               </OrderLine>
                    """.formatted(item.qty(), item.primeLineNo(), item.itemId()));
        }

        String xml = """
                <MultiApi>
                    <API FlowName="ExecutePOD">
                        <Input>
                            <Shipment OrderNo="%s" DocumentType="0001"
                                      EnterpriseCode="%s" StatusDate="%s">
                                <Containers>
                                    <Container TrackingNo="%s" ContainerNo="%s" ISDropShipContainer="N">
                                        <ContainerDetails>
                %s
                                        </ContainerDetails>
                                    </Container>
                                </Containers>
                            </Shipment>
                        </Input>
                    </API>
                    <API FlowName="ExecuteContainerActivity">
                        <Input>
                            <Container IsDropShipContainer="N" ContainerNo="%s">
                                <ContainerActivities>
                                    <ContainerActivity ActivityCode="DELIVERED TO CUSTOMER" ActivityTimeStamp="%s">
                                        <Extn ExtnActivityTime="%s"/>
                                        <ActivityLocation/>
                                    </ContainerActivity>
                                </ContainerActivities>
                                <Order DocumentType="0001" EnterpriseCode="%s" OrderNo="%s">
                                    <OrderLines>
                %s
                                    </OrderLines>
                                </Order>
                            </Container>
                        </Input>
                    </API>
                </MultiApi>
                """.formatted(
                ctx.orderNo(),
                ctx.enterpriseCode(),
                ctx.timestamp(),
                ctx.trackingNo(),
                ctx.containerNo(),
                containerDetails,
                ctx.containerNo(),
                ctx.timestamp(),
                ctx.timestamp(),
                ctx.enterpriseCode(),
                ctx.orderNo(),
                orderLines
        );

        runSterling(driver, "api", "multiApi", xml);
        waitAndReturn(driver, 15);
    }

    public static String getTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }

    public static List<ItemDetailWithoutShipAdvice> parseItemDetailsWithoutShipAdvice(String raw) {
        List<ItemDetailWithoutShipAdvice> details = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return details;
        }

        for (String pair : raw.split(",")) {
            if (pair.isBlank()) {
                continue;
            }

            String[] parts = pair.split(":");
            if (parts.length != 7) {
                continue;
            }

            details.add(new ItemDetailWithoutShipAdvice(
                    trimToNull(parts[0]),
                    trimToNull(parts[1]),
                    trimToNull(parts[2]),
                    trimToNull(parts[3]),
                    trimToNull(parts[4]),
                    trimToNull(parts[5]),
                    trimToNull(parts[6])
            ));
        }

        return details;
    }

    public static List<ItemDetail> parseItemDetails(String raw) {
        List<ItemDetail> details = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return details;
        }

        for (String pair : raw.split(",")) {
            if (pair.isBlank()) {
                continue;
            }

            String[] parts = pair.split(":");
            if (parts.length != 8) {
                continue;
            }

            String shipAdviceNo = trimToNull(parts[2]);
            if ("None".equals(shipAdviceNo)) {
                shipAdviceNo = null;
            }

            details.add(new ItemDetail(
                    trimToNull(parts[0]),
                    trimToNull(parts[1]),
                    shipAdviceNo,
                    trimToNull(parts[3]),
                    trimToNull(parts[4]),
                    trimToNull(parts[5]),
                    trimToNull(parts[6]),
                    trimToNull(parts[7])
            ));
        }

        return details;
    }

    public static OrderContext buildOrderContext(String orderNo, String enterpriseCode) {
        String query = """
                select yoh.ORDER_HEADER_KEY,
                       yoh.ENTERPRISE_KEY,
                       yol.SHIPNODE_KEY,
                       yol.item_id,
                       yol.ORDERED_QTY,
                       yol.PRODUCT_LINE,
                       yol.UOM,
                       yol.scac,
                       yol.PRIME_LINE_NO
                  from yantra_owner.yfs_order_line yol
                  join yantra_owner.yfs_order_header yoh
                    on yoh.ORDER_HEADER_KEY = yol.ORDER_HEADER_KEY
                 where yol.CURRENT_WORK_ORDER_KEY is null
                   and yoh.order_no = ?
                """;

        QueryItemData itemData = getItemsQtyFromDb(orderNo, query);
        String enterpriseCodeFinal = itemData.enterpriseKey() != null ? itemData.enterpriseKey() : enterpriseCode;
        String sellerOrg = enterpriseCodeFinal + "DTC";
        String trackingNo = "1Z" + orderNo;
        String containerNo = trackingNo;
        String timestamp = getTimestamp();

        List<ItemDetail> items = new ArrayList<>();
        for (ItemDetailWithoutShipAdvice item : parseItemDetailsWithoutShipAdvice(itemData.itemDetails())) {
            items.add(new ItemDetail(
                    item.itemId(),
                    item.qty(),
                    null,
                    item.itemType(),
                    item.uom(),
                    item.scac(),
                    item.shipNode(),
                    item.primeLineNo()
            ));
        }

        System.out.println("items - " + items);
        System.out.println("Item_details_input - " + itemData.itemDetails());

        return new OrderContext(
                orderNo,
                enterpriseCodeFinal,
                itemData.shipNode(),
                sellerOrg,
                trackingNo,
                containerNo,
                timestamp,
                items
        );
    }

    public static OrderContext buildOrderContextWithShipAdviceNo(String orderNo, String enterpriseCode) {
        String query = """
                select distinct yoh.ORDER_HEADER_KEY,
                                yoh.ENTERPRISE_KEY,
                                yol.SHIPNODE_KEY,
                                yol.item_id,
                                yol.ORDERED_QTY,
                                yor.SHIP_ADVICE_NO,
                                yol.PRODUCT_LINE,
                                yol.UOM,
                                yol.scac,
                                yol.PRIME_LINE_NO
                  from yantra_owner.yfs_order_line yol
                  join yantra_owner.yfs_order_header yoh
                    on yoh.ORDER_HEADER_KEY = yol.ORDER_HEADER_KEY
                  join yantra_owner.yfs_order_release yor
                    on yor.ORDER_HEADER_KEY = yoh.ORDER_HEADER_KEY
                   and yol.order_header_key = yor.order_header_key
                  join yantra_owner.yfs_order_release_Status yors
                    on yors.order_line_key = yol.order_line_key
                   and yors.order_release_key = yor.order_release_key
                 where yol.CURRENT_WORK_ORDER_KEY is null
                   and yoh.order_no = ?
                """;

        QueryItemData itemData = getItemsQtyShipAdviceNoFromDb(orderNo, query);
        String enterpriseCodeFinal = itemData.enterpriseKey() != null ? itemData.enterpriseKey() : enterpriseCode;
        String sellerOrg = enterpriseCodeFinal + "DTC";
        String trackingNo = "1Z" + orderNo;
        String containerNo = trackingNo;
        String timestamp = getTimestamp();
        List<ItemDetail> items = parseItemDetails(itemData.itemDetails());

        System.out.println("items - " + items);
        System.out.println("Item_details_input - " + itemData.itemDetails());

        return new OrderContext(
                orderNo,
                enterpriseCodeFinal,
                itemData.shipNode(),
                sellerOrg,
                trackingNo,
                containerNo,
                timestamp,
                items
        );
    }

    public static void serviceCreateOrderInvoiceForBdrds(OrderEnterpriseDetail ctx, SterlingSession driver) {
        String query = """
                select yoh.ORDER_HEADER_KEY, yol.ORDER_LINE_KEY, yol.ORDERED_QTY
                  from yantra_owner.yfs_order_line yol
                  join yantra_owner.yfs_order_header yoh
                    on yoh.ORDER_HEADER_KEY = yol.ORDER_HEADER_KEY
                 where yoh.order_no = ?
                """;

        List<Map<String, Object>> rows = executeQuery(query, List.of(ctx.orderNo()));
        if (rows.isEmpty()) {
            System.out.println("No order invoice rows found for Order - " + ctx.orderNo());
            return;
        }

        StringBuilder orderLineLines = new StringBuilder();
        for (Map<String, Object> row : rows) {
            orderLineLines.append("<OrderLine OrderLineKey=\"")
                    .append(safeTrim(row.get("ORDER_LINE_KEY")))
                    .append("\" Quantity=\"")
                    .append(String.valueOf(row.get("ORDERED_QTY")))
                    .append("\"/>");
        }

        String orderHeaderKey = safeTrim(rows.get(0).get("ORDER_HEADER_KEY"));
        String xml = """
                <Order IgnoreStatusCheck="Y" IgnoreTransactionDependencies="Y"
                       OrderHeaderKey="%s" TransactionId="CREATE_ORDER_INVOICE.0001.ex">
                    <OrderLines>
                        %s
                    </OrderLines>
                </Order>
                """.formatted(orderHeaderKey, orderLineLines);

        runSterling(driver, "service", "CreateOrderInvoiceForBDRDS", xml);
        waitAndReturn(driver, 10);
    }

    public static void apiCreateChainedOrder(OrderEnterpriseDetail ctx, SterlingSession driver) {
        String xml = "<Order OrderNo=\"%s\" EnterpriseCode=\"%s\" DocumentType=\"0001\"/>"
                .formatted(ctx.orderNo(), ctx.enterpriseCode());
        runSterling(driver, "api", "createChainedOrder", xml);
        waitAndReturn(driver, 15);
    }

    public static void serviceMkpdsReceiveAsnSync(OrderContext ctx, List<ItemDetail> itemDetails, SterlingSession driver) {
        System.out.println("itemDetails - " + itemDetails);

        StringBuilder containerDetailLines = new StringBuilder();
        StringBuilder shipmentLines = new StringBuilder();
        for (ItemDetail item : itemDetails) {
            containerDetailLines.append("""
                                <ContainerDetail Quantity="%s">
                                    <ShipmentLine ItemID="%s" PrimeLineNo="%s" UnitOfMeasure="%s"/>
                                </ContainerDetail>
                    """.formatted(item.qty(), item.itemId(), item.primeLineNo(), item.uom()));

            shipmentLines.append("""
                           <ShipmentLine ItemID="%s" PrimeLineNo="%s" Quantity="%s" UnitOfMeasure="%s"/>
                    """.formatted(item.itemId(), item.primeLineNo(), item.qty(), item.uom()));
        }

        LocalDateTime ts = LocalDateTime.parse(ctx.timestamp(), TIMESTAMP_FORMAT);
        LocalDateTime expectedDelivery = ts.plusDays(1);
        String bolNo = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String scac = itemDetails.isEmpty() ? "" : itemDetails.get(0).scac();

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Shipment BolNo="%s" ExpectedDeliveryDate="%s" ExpectedShipmentDate="%s" OrderNo="%s" SCAC="%s">
                    <Containers>
                        <Container TrackingNo="%s" ContainerNo="%s">
                            <ContainerDetails>
                                %s
                            </ContainerDetails>
                        </Container>
                    </Containers>
                    <ShipmentLines>
                        %s
                    </ShipmentLines>
                    <Extn ExtnSourceShipmentNo="61"/>
                </Shipment>
                """.formatted(
                bolNo,
                expectedDelivery,
                ctx.timestamp(),
                ctx.orderNo(),
                scac,
                ctx.trackingNo(),
                ctx.containerNo(),
                containerDetailLines,
                shipmentLines
        );

        System.out.println("XML -> " + xml);
        runSterling(driver, "service", "MKPDSReceiveASNSync", xml);
        waitAndReturn(driver, 15);
    }

    private static QueryItemData getItemsQtyFromDb(String orderNo, String query) {
        List<Map<String, Object>> rows = executeQuery(query, List.of(orderNo));
        if (rows.isEmpty()) {
            return new QueryItemData(null, null, "");
        }

        String enterpriseKey = safeTrim(rows.get(0).get("ENTERPRISE_KEY"));
        String shipNode = safeTrim(rows.get(0).get("SHIPNODE_KEY"));
        List<String> cleanedItems = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String itemId = safeTrim(row.get("ITEM_ID"));
            String orderQty = normalizeQty(row.get("ORDERED_QTY"));
            String productLine = safeTrim(row.get("PRODUCT_LINE"));
            String uom = safeTrim(row.get("UOM"));
            String scac = safeTrim(row.get("SCAC"));
            String shipNodeValue = safeTrim(row.get("SHIPNODE_KEY"));
            String primeLineNo = safeTrim(row.get("PRIME_LINE_NO"));
            cleanedItems.add(String.join(":", itemId, orderQty, productLine, uom, scac, shipNodeValue, primeLineNo));
        }

        return new QueryItemData(enterpriseKey, shipNode, String.join(",", cleanedItems));
    }

    private static QueryItemData getItemsQtyShipAdviceNoFromDb(String orderNo, String query) {
        List<Map<String, Object>> rows = executeQuery(query, List.of(orderNo));
        if (rows.isEmpty()) {
            return new QueryItemData(null, null, "");
        }

        String enterpriseKey = safeTrim(rows.get(0).get("ENTERPRISE_KEY"));
        String shipNode = safeTrim(rows.get(0).get("SHIPNODE_KEY"));
        List<String> cleanedItems = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String itemId = safeTrim(row.get("ITEM_ID"));
            String orderQty = normalizeQty(row.get("ORDERED_QTY"));
            String shipAdviceNo = safeTrim(row.get("SHIP_ADVICE_NO"));
            String productLine = safeTrim(row.get("PRODUCT_LINE"));
            String uom = safeTrim(row.get("UOM"));
            String scac = safeTrim(row.get("SCAC"));
            String shipNodeValue = safeTrim(row.get("SHIPNODE_KEY"));
            String primeLineNo = safeTrim(row.get("PRIME_LINE_NO"));
            cleanedItems.add(String.join(":", itemId, orderQty, String.valueOf(shipAdviceNo), productLine, uom, scac, shipNodeValue, primeLineNo));
        }

        return new QueryItemData(enterpriseKey, shipNode, String.join(",", cleanedItems));
    }

    private static List<Map<String, Object>> executeQuery(String query, List<Object> params) {
        try (Connection connection = getOracleConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {

            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();

                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnLabel(i), resultSet.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            System.out.println("Database error: " + e.getMessage());
            return List.of();
        }
    }

    private static Connection getOracleConnection() throws SQLException {
        String host = requireEnv("ORACLE_STERLING_HOST");
        String port = getEnv("ORACLE_STERLING_PORT", "1521");
        String serviceName = requireEnv("ORACLE_STERLING_SERVICE_NAME");
        String username = requireEnv("ORACLE_STERLING_USERNAME");
        String password = requireEnv("ORACLE_STERLING_PASSWORD");

        String jdbcUrl = "jdbc:oracle:thin:@//%s:%s/%s".formatted(host, port, serviceName);
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sleep interrupted", e);
        }
    }

    private static String normalizeQty(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            return String.valueOf(number.intValue());
        }
        try {
            return String.valueOf((int) Double.parseDouble(value.toString()));
        } catch (NumberFormatException ignored) {
            return value.toString().trim();
        }
    }

    private static String requireEnv(String key) {
        String value = getEnv(key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment value: " + key);
        }
        return value;
    }

    private static String getEnv(String key, String defaultValue) {
        String override = EnvOverrides.get(key);
        if (override != null) {
            return override;
        }
        String sysValue = System.getenv(key);
        if (sysValue != null) {
            return sysValue;
        }
        return defaultValue;
    }

    private static String stripQuotes(String value) {
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String safeTrim(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record QueryItemData(String enterpriseKey, String shipNode, String itemDetails) {
    }

    private static final class EnvOverrides {
        private static final Map<String, String> VALUES = new HashMap<>();

        private EnvOverrides() {
        }

        private static void set(String key, String value) {
            VALUES.put(key, value);
        }

        private static String get(String key) {
            return VALUES.get(key);
        }
    }
}
