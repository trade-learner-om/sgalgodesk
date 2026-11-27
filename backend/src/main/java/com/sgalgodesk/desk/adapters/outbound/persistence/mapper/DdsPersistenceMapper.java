package com.sgalgodesk.desk.adapters.outbound.persistence.mapper;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.DdsEventEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.entity.DdsRunEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.entity.DdsStrategyEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.entity.DdsTradeEntity;
import com.sgalgodesk.desk.domain.dds.DdsEvent;
import com.sgalgodesk.desk.domain.dds.DdsRun;
import com.sgalgodesk.desk.domain.dds.DdsStrategy;
import com.sgalgodesk.desk.domain.dds.DdsTrade;

public final class DdsPersistenceMapper {

	private DdsPersistenceMapper() {
	}

	public static DdsStrategy toDomain(DdsStrategyEntity entity) {
		if (entity == null) {
			return null;
		}
		return new DdsStrategy(
				entity.getId(),
				entity.getUserId(),
				entity.getName(),
				entity.getStatus(),
				entity.getExecutionMode(),
				entity.getSettingsJson(),
				entity.getCreatedAt(),
				entity.getUpdatedAt());
	}

	public static DdsStrategyEntity toEntity(DdsStrategy strategy, boolean isNew) {
		if (strategy == null) {
			return null;
		}
		DdsStrategyEntity entity = new DdsStrategyEntity();
		entity.setId(strategy.id());
		entity.setUserId(strategy.userId());
		entity.setName(strategy.name());
		entity.setStatus(strategy.status());
		entity.setExecutionMode(strategy.executionMode());
		entity.setSettingsJson(strategy.settingsJson());
		entity.setCreatedAt(strategy.createdAt());
		entity.setUpdatedAt(strategy.updatedAt());
		entity.setNewEntity(isNew);
		return entity;
	}

	public static DdsRun toDomain(DdsRunEntity entity) {
		if (entity == null) {
			return null;
		}
		return new DdsRun(
				entity.getId(),
				entity.getStrategyId(),
				entity.getUserId(),
				entity.getRunDate(),
				entity.getExecutionMode(),
				entity.getLifecycleState(),
				entity.getRealisedPnl(),
				entity.getUnrealisedPnl(),
				entity.getStartedAt(),
				entity.getEndedAt());
	}

	public static DdsRunEntity toEntity(DdsRun run, boolean isNew) {
		if (run == null) {
			return null;
		}
		DdsRunEntity entity = new DdsRunEntity();
		entity.setId(run.id());
		entity.setStrategyId(run.strategyId());
		entity.setUserId(run.userId());
		entity.setRunDate(run.runDate());
		entity.setExecutionMode(run.executionMode());
		entity.setLifecycleState(run.lifecycleState());
		entity.setRealisedPnl(run.realisedPnl());
		entity.setUnrealisedPnl(run.unrealisedPnl());
		entity.setStartedAt(run.startedAt());
		entity.setEndedAt(run.endedAt());
		entity.setNewEntity(isNew);
		return entity;
	}

	public static DdsTrade toDomain(DdsTradeEntity entity) {
		if (entity == null) {
			return null;
		}
		return new DdsTrade(
				entity.getId(),
				entity.getStrategyId(),
				entity.getRunId(),
				entity.getUserId(),
				entity.getLegRole(),
				entity.getInstrumentKey(),
				entity.getTradingSymbol(),
				entity.getSide(),
				entity.getQuantity(),
				entity.getExecutionMode(),
				entity.getBrokerOrderId(),
				entity.getEntryPrice(),
				entity.getExitPrice(),
				entity.getPnl(),
				entity.getTriggerReason(),
				entity.getStatus(),
				entity.getDeltaAtEntry(),
				entity.getLotSize(),
				entity.getStrike(),
				entity.getExpiry(),
				entity.getOptionType(),
				entity.getExecutedAt(),
				entity.getClosedAt());
	}

	public static DdsTradeEntity toEntity(DdsTrade trade, boolean isNew) {
		if (trade == null) {
			return null;
		}
		DdsTradeEntity entity = new DdsTradeEntity();
		entity.setId(trade.id());
		entity.setStrategyId(trade.strategyId());
		entity.setRunId(trade.runId());
		entity.setUserId(trade.userId());
		entity.setLegRole(trade.legRole());
		entity.setInstrumentKey(trade.instrumentKey());
		entity.setTradingSymbol(trade.tradingSymbol());
		entity.setSide(trade.side());
		entity.setQuantity(trade.quantity());
		entity.setExecutionMode(trade.executionMode());
		entity.setBrokerOrderId(trade.brokerOrderId());
		entity.setEntryPrice(trade.entryPrice());
		entity.setExitPrice(trade.exitPrice());
		entity.setPnl(trade.pnl());
		entity.setTriggerReason(trade.triggerReason());
		entity.setStatus(trade.status());
		entity.setDeltaAtEntry(trade.deltaAtEntry());
		entity.setLotSize(trade.lotSize());
		entity.setStrike(trade.strike());
		entity.setExpiry(trade.expiry());
		entity.setOptionType(trade.optionType());
		entity.setExecutedAt(trade.executedAt());
		entity.setClosedAt(trade.closedAt());
		entity.setNewEntity(isNew);
		return entity;
	}

	public static DdsEvent toDomain(DdsEventEntity entity) {
		if (entity == null) {
			return null;
		}
		return new DdsEvent(
				entity.getId(),
				entity.getStrategyId(),
				entity.getRunId(),
				entity.getUserId(),
				entity.getEventType(),
				entity.getMessage(),
				entity.getCreatedAt());
	}

	public static DdsEventEntity toEntity(DdsEvent event, boolean isNew) {
		if (event == null) {
			return null;
		}
		DdsEventEntity entity = new DdsEventEntity();
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
