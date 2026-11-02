package com.mscient.intrapaper.simulation;

import java.time.Instant;

public record SimOrderEvent(Instant timestamp, String orderId, String event, String detail) { }
