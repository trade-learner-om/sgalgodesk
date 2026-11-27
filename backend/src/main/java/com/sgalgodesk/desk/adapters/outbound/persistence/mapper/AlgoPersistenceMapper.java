package com.sgalgodesk.desk.adapters.outbound.persistence.mapper;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.AlgoStrategyEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.entity.AlgoStrategyEventEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.entity.AlgoStrategyRunEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.entity.AlgoStrategyTradeEntity;
import com.sgalgodesk.desk.domain.algo.AlgoStrategy;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyEvent;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyRun;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;

/**
 * Maps algo persistence entities to and from pure domain models.
 * {@code toEntity(domain)} flags the entity as new (insert); use {@code toEntity(domain, false)}
 * when updating an existing row.
 */
public final class AlgoPersistenceMapper {

	private AlgoPersistenceMapper() {
	}

	// --- Strategy -------------------------------------------------------------

	public static AlgoStrategy toDomain(AlgoStrategyEntity entity) {
		if (entity == null) {
			return null;
		}
		return new AlgoStrategy(
				entity.getId(),
				entity.getUserId(),
				entity.getName(),
				entity.getStatus(),
				entity.getExecutionMode(),
				entity.getConfigJson(),
				entity.getCreatedAt(),
				entity.getUpdatedAt());
	}

	public static AlgoStrategyEntity toEntity(AlgoStrategy strategy) {
		return toEntity(strategy, true);
	}

	public static AlgoStrategyEntity toEntity(AlgoStrategy strategy, boolean isNew) {
		if (strategy == null) {
			return null;
		}
		AlgoStrategyEntity entity = new AlgoStrategyEntity();
		entity.setId(strategy.id());
		entity.setUserId(strategy.userId());
		entity.setName(strategy.name());
		entity.setStatus(strategy.status());
		entity.setExecutionMode(strategy.executionMode());
		entity.setConfigJson(strategy.configJson());
		entity.setCreatedAt(strategy.createdAt());
		entity.setUpdatedAt(strategy.updatedAt());
		entity.setNewEntity(isNew);
		return entity;
	}

	// --- Run ------------------------------------------------------------------

	public static AlgoStrategyRun toDomain(AlgoStrategyRunEntity entity) {
		if (entity == null) {
			return null;
		}
		return new AlgoStrategyRun(
				entity.getId(),
				entity.getStrategyId(),
				entity.getUserId(),
				entity.getRunDate(),
				entity.getExecutionMode(),
				entity.getLifecycleState(),
				entity.getRealisedPnl(),
				entity.getUnrealisedPnl(),
				entity.getReentryCount(),
				entity.getTrailFloorPnl(),
				entity.getPeakPnl(),
				entity.getStartedAt(),
				entity.getEndedAt());
	}

	public static AlgoStrategyRunEntity toEntity(AlgoStrategyRun run) {
		return toEntity(run, true);
	}

	public static AlgoStrategyRunEntity toEntity(AlgoStrategyRun run, boolean isNew) {
		if (run == null) {
			return null;
		}
		AlgoStrategyRunEntity entity = new AlgoStrategyRunEntity();
		entity.setId(run.id());
		entity.setStrategyId(run.strategyId());
		entity.setUserId(run.userId());
		entity.setRunDate(run.runDate());
		entity.setExecutionMode(run.executionMode());
		entity.setLifecycleState(run.lifecycleState());
		entity.setRealisedPnl(run.realisedPnl());
		entity.setUnrealisedPnl(run.unrealisedPnl());
		entity.setReentryCount(run.reentryCount());
		entity.setTrailFloorPnl(run.trailFloorPnl());
		entity.setPeakPnl(run.peakPnl());
		entity.setStartedAt(run.startedAt());
		entity.setEndedAt(run.endedAt());
		entity.setNewEntity(isNew);
		return entity;
	}

	// --- Trade ----------------------------------------------------------------

	public static AlgoStrategyTrade toDomain(AlgoStrategyTradeEntity entity) {
		if (entity == null) {
			return null;
		}
		return new AlgoStrategyTrade(
				entity.getId(),
				entity.getStrategyId(),
				entity.getRunId(),
				entity.getUserId(),
				entity.getLegIndex(),
				entity.getInstrumentKey(),
				entity.getTradingSymbol(),
				entity.getSide(),
				entity.getQuantity(),
				entity.getExecutionMode(),
				entity.getBrokerOrderId(),
				entity.getSlOrderId(),
				entity.getSlTriggerPrice(),
				entity.getEntryPrice(),
				entity.getExitPrice(),
				entity.getPnl(),
				entity.getTriggerReason(),
				entity.getStatus(),
				entity.getExecutedAt(),
				entity.getClosedAt(),
				entity.getLotSize(),
				entity.getStrike(),
				entity.getExpiry(),
				entity.getOptionType());
	}

	public static AlgoStrategyTradeEntity toEntity(AlgoStrategyTrade trade) {
		return toEntity(trade, true);
	}

	public static AlgoStrategyTradeEntity toEntity(AlgoStrategyTrade trade, boolean isNew) {
		if (trade == null) {
			return null;
		}
		AlgoStrategyTradeEntity entity = new AlgoStrategyTradeEntity();
		entity.setId(trade.id());
		entity.setStrategyId(trade.strategyId());
		entity.setRunId(trade.runId());
		entity.setUserId(trade.userId());
		entity.setLegIndex(trade.legIndex());
		entity.setInstrumentKey(trade.instrumentKey());
		entity.setTradingSymbol(trade.tradingSymbol());
		entity.setSide(trade.side());
		entity.setQuantity(trade.quantity());
		entity.setExecutionMode(trade.executionMode());
		entity.setBrokerOrderId(trade.brokerOrderId());
		entity.setSlOrderId(trade.slOrderId());
		entity.setSlTriggerPrice(trade.slTriggerPrice());
		entity.setEntryPrice(trade.entryPrice());
		entity.setExitPrice(trade.exitPrice());
		entity.setPnl(trade.pnl());
		entity.setTriggerReason(trade.triggerReason());
		entity.setStatus(trade.status());
		entity.setExecutedAt(trade.executedAt());
		entity.setClosedAt(trade.closedAt());
		entity.setLotSize(trade.lotSize());
		entity.setStrike(trade.strike());
		entity.setExpiry(trade.expiry());
		entity.setOptionType(trade.optionType());
		entity.setNewEntity(isNew);
		return entity;
	}

	// --- Event ----------------------------------------------------------------

	public static AlgoStrategyEvent toDomain(AlgoStrategyEventEntity entity) {
		if (entity == null) {
			return null;
		}
		return new AlgoStrategyEvent(
				entity.getId(),
				entity.getStrategyId(),
				entity.getRunId(),
				entity.getUserId(),
				entity.getEventType(),
				entity.getMessage(),
				entity.getCreatedAt());
	}

	public static AlgoStrategyEventEntity toEntity(AlgoStrategyEvent event) {
		return toEntity(event, true);
	}

	public static AlgoStrategyEventEntity toEntity(AlgoStrategyEvent event, boolean isNew) {
		if (event == null) {
			return null;
		}
		AlgoStrategyEventEntity entity = new AlgoStrategyEventEntity();
		entity.setId(event.id());
		entity.setStrategyId(event.strategyId());
		entity.setRunId(event.runId());
		entity.setUserId(event.userId());
		entity.setEventType(event.eventType());
		entity.setMessage(event.message());
		entity.setCreatedAt(event.createdAt());
		entity.setNewEntity(isNew);
		return entity;
	}
}
