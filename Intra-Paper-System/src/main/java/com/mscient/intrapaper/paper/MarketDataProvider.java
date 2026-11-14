package com.mscient.intrapaper.paper;

import java.util.List;

/** Read-only boundary. G0 keeps the permitted implementations restricted to frozen local input. */
public sealed interface MarketDataProvider permits LocalMarketDataProvider {
    List<PaperEvent> events();
}
