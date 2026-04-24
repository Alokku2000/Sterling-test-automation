package com.sterling.orderprocess.model;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

public final class SterlingSession implements AutoCloseable {
    private final Playwright playwright;
    private final Browser browser;
    private final Page page;

    public SterlingSession(Playwright playwright, Browser browser, Page page) {
        this.playwright = playwright;
        this.browser = browser;
        this.page = page;
    }

    public Page page() {
        return page;
    }

    @Override
    public void close() {
        browser.close();
        playwright.close();
    }
}
