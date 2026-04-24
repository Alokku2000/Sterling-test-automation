# Sterling Test Automation

Java + Playwright automation for running Sterling order processing flows against different environments.

## What this project does

This project automates Sterling order lifecycle actions by:

- opening the Sterling HTTP API tester in a Playwright browser session
- loading environment-specific database and Sterling URL settings from `.env.*` files
- reading order details from Oracle
- executing Sterling APIs and services in sequence
- checking order status between steps

## Main entry points

### 1. Full order flow

Main class:

`com.sterling.orderprocess.SterlingOrderFlowApp`

This is the primary starting point for the project. It:

- asks for environment: `eqa1`, `eqa2`, or `eqa3`
- asks for `OrderNo`
- asks for `EnterpriseCode`
- runs the full order progression based on current Sterling status

Typical statuses handled in the flow:

- `Created`
- `Awaiting DS PO Creation`
- `DS PO Created`
- `DTCScheduled`
- `Dropped For Fulfillment`
- `Invoiced PreShip`
- `Acknowledged`
- `Shipped`
- `Invoiced`

### 2. E-gift card order flow

Main class:

`com.sterling.orderprocess.SterlingEgiftCardOrderProcessingApp`

This is a separate flow for e-gift card order processing. It performs targeted API/service calls such as:

- `processOrderPayments`
- `scheduleOrder`
- `releaseOrder`
- `ProcessOrderDropAck`
- `triggerAgent`

## Project structure

```text
src/main/java/com/sterling/orderprocess/
  SterlingOrderFlowApp.java
  SterlingEgiftCardOrderProcessingApp.java
  model/
  util/
    SterlingUtils.java
```

Important utility class:

- `com.sterling.orderprocess.util.SterlingUtils`

This class contains most of the reusable logic for:

- environment loading
- Playwright browser setup
- Oracle DB access
- Sterling API/service execution
- status lookup and order context building

## Environment files

The application loads environment files from the project root using this naming pattern:

```text
.env.<environment>
```

Current supported files:

- `.env.eqa1`
- `.env.eqa2`
- `.env.eqa3`

These files are expected in the repository root directory.

## Prerequisites

- Java 17
- Maven 3.8+
- network access to the configured Oracle databases and Sterling URLs
- Playwright browser dependencies available for Java

## Build the project

```bash
mvn clean compile
```

## Run the full order flow

```bash
mvn exec:java "-Dexec.mainClass=com.sterling.orderprocess.SterlingOrderFlowApp"
```

You will be prompted for:

- environment
- order number
- enterprise code

## Run the e-gift card flow

```bash
mvn exec:java "-Dexec.mainClass=com.sterling.orderprocess.SterlingEgiftCardOrderProcessingApp"
```

## How the full flow works

At a high level, the main flow:

1. loads the selected environment from `.env.eqa1`, `.env.eqa2`, or `.env.eqa3`
2. opens the Sterling HTTP API tester page in Chromium using Playwright
3. checks the current order status from Oracle
4. runs only the next required Sterling API/service steps
5. re-checks order status after major actions
6. finishes when the order reaches its final expected state

## Notes

- The Playwright browser currently launches in non-headless mode.
- The app depends on live environment configuration and database connectivity.
- Generated files under `target/` are build outputs.

## Dependencies

Defined in `pom.xml`:

- `com.microsoft.playwright:playwright`
- `com.oracle.database.jdbc:ojdbc11`

## Suggested next improvements

- add a `.gitignore` for build output and IDE files
- add a safe `.env.example` file without secrets
- move sensitive credentials out of version control
- add logging and error handling around environment validation and DB connectivity
