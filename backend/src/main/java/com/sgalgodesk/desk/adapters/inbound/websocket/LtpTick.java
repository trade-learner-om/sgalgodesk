package com.sgalgodesk.desk.adapters.inbound.websocket;

public record LtpTick(String instrumentKey, double ltp, Double close) {
}
