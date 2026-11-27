package com.sgalgodesk.desk.adapters.outbound.mstock;

/**
 * Script-master token mapped for MStock market WebSocket subscribe/parse.
 */
public record MstockFeedToken(String token, String instrumentKey, int exchangeType) {
}
