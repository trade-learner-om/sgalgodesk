package com.sgalgodesk.desk.domain.spread;

/** One leg of a multi-leg spread; {@code side} is +1 for long, -1 for short. */
public record SpreadLeg(String instrumentKey, int side, int quantity, int lotSize, int lots, double entryPrice) {
}
