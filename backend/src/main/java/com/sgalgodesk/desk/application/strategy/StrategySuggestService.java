package com.sgalgodesk.desk.application.strategy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.indicators.IndicatorDtos.Bias;
import com.sgalgodesk.desk.application.indicators.IndicatorDtos.SignalResponse;
import com.sgalgodesk.desk.application.indicators.IndicatorSignalService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockInstrumentService;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockOptionChainService;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionChainResponse;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionSide;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionStrikeRow;
import com.sgalgodesk.desk.application.optionchain.OptionChainService;
import com.sgalgodesk.desk.application.strategy.StrategySuggestDtos.ExpiryScenario;
import com.sgalgodesk.desk.application.strategy.StrategySuggestDtos.SuggestRequest;
import com.sgalgodesk.desk.application.strategy.StrategySuggestDtos.SuggestResponse;
import com.sgalgodesk.desk.application.strategy.StrategySuggestDtos.SuggestedLeg;
import com.sgalgodesk.desk.application.strategy.StrategySuggestDtos.WingTrial;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class StrategySuggestService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private final IndicatorSignalService indicatorSignalService;
	private final MstockInstrumentService instrumentService;
	private final MstockOptionChainService mstockOptionChainService;
	private final OptionChainService optionChainService;

	public Mono<SuggestResponse> suggest(UUID userId, SuggestRequest request) {
		boolean debit = Boolean.TRUE.equals(request.debitOverride());
		return indicatorSignalService.signal(userId, request.instrumentKey())
				.flatMap(signal -> {
					if (signal.bias() == Bias.NONE) {
						return Mono.just(new SuggestResponse(
								request.instrumentKey(),
								null,
								signal.close(),
								Bias.NONE,
								debit,
								null,
								signal,
								List.of()));
					}
					if (!mstockOptionChainService.isNiftyUnderlying(request.instrumentKey())) {
						return Mono.error(new ValidationFailed("Only NIFTY option chain is supported for now"));
					}
					LocalDate today = LocalDate.now(IST);
					return mstockOptionChainService.listNiftyExpiries(userId)
							.flatMap(expiries -> {
								List<LocalDate> upcoming = expiries.stream()
										.filter(d -> !d.isBefore(today))
										.sorted()
										.limit(2)
										.toList();
								if (upcoming.isEmpty()) {
									return Mono.error(new ValidationFailed("No upcoming expiries"));
								}
								return Flux.fromIterable(upcoming)
										.concatMap(expiry -> optionChainService
												.getChain(userId, request.instrumentKey(), expiry.toString())
												.map(chain -> buildScenario(
														chain, signal, debit, expiry, today, upcoming)))
										.collectList()
										.map(scenarios -> {
											String underlyingSymbol = instrumentService
													.findByKey(request.instrumentKey())
													.map(r -> r.underlyingSymbol() != null
															? r.underlyingSymbol()
															: r.tradingSymbol())
													.orElse("NIFTY");
											String spreadType = signal.bias() == Bias.SHORT
													? (debit ? "bear_call_debit" : "bear_call_credit")
													: (debit ? "bull_put_debit" : "bull_put_credit");
											return new SuggestResponse(
													request.instrumentKey(),
													underlyingSymbol,
													signal.close(),
													signal.bias(),
													debit,
													spreadType,
													signal,
													scenarios);
										});
							});
				});
	}

	private ExpiryScenario buildScenario(
			OptionChainResponse chain,
			SignalResponse signal,
			boolean debit,
			LocalDate expiry,
			LocalDate today,
			List<LocalDate> upcoming) {
		int dte = (int) ChronoUnit.DAYS.between(today, expiry);
		boolean preferred = upcoming.size() == 1
				|| (upcoming.getFirst().equals(expiry) && dte > 3)
				|| (upcoming.size() > 1 && upcoming.get(1).equals(expiry) && ChronoUnit.DAYS.between(today, upcoming.getFirst()) <= 3);

		double spot = chain.underlyingSpot() != null ? chain.underlyingSpot() : signal.close();
		List<Double> strikes = chain.strikes().stream().map(OptionStrikeRow::strike).sorted().toList();
		if (strikes.isEmpty()) {
			return new ExpiryScenario(expiry.toString(), dte, preferred, null, List.of());
		}

		int atmIdx = nearestStrikeIndex(strikes, spot);
		List<WingTrial> trials = new ArrayList<>();

		if (signal.bias() == Bias.SHORT) {
			// Bear Call: sell ITM+1 CE (one strike below ATM for calls = ITM), buy OTM+1 / OTM+2
			int sellIdx = Math.max(0, atmIdx - 1); // ITM+1 call
			for (int otmSteps : List.of(1, 2)) {
				int buyIdx = Math.min(strikes.size() - 1, atmIdx + otmSteps);
				if (buyIdx <= sellIdx) {
					continue;
				}
				WingTrial trial = buildCallSpread(chain, strikes, sellIdx, buyIdx, otmSteps, debit, expiry);
				if (trial != null) {
					trials.add(trial);
				}
			}
		}
		else {
			// Bull Put: sell ITM+1 PE (one strike above ATM for puts = ITM), buy OTM-1 / OTM-2
			int sellIdx = Math.min(strikes.size() - 1, atmIdx + 1);
			for (int otmSteps : List.of(1, 2)) {
				int buyIdx = Math.max(0, atmIdx - otmSteps);
				if (buyIdx >= sellIdx) {
					continue;
				}
				WingTrial trial = buildPutSpread(chain, strikes, sellIdx, buyIdx, otmSteps, debit, expiry);
				if (trial != null) {
					trials.add(trial);
				}
			}
		}

		WingTrial best = trials.stream()
				.max(Comparator.comparingDouble(WingTrial::riskReward)
						.thenComparingDouble(WingTrial::creditPerLot))
				.orElse(null);
		return new ExpiryScenario(expiry.toString(), dte, preferred, best, trials);
	}

	private WingTrial buildCallSpread(
			OptionChainResponse chain,
			List<Double> strikes,
			int sellIdx,
			int buyIdx,
			int otmSteps,
			boolean debit,
			LocalDate expiry) {
		double sellStrike = strikes.get(sellIdx);
		double buyStrike = strikes.get(buyIdx);
		OptionSide sellSide = findSide(chain, sellStrike, true);
		OptionSide buySide = findSide(chain, buyStrike, true);
		if (sellSide == null || buySide == null || sellSide.ltp() == null || buySide.ltp() == null) {
			return null;
		}
		return metrics(sellSide, buySide, sellStrike, buyStrike, otmSteps, debit, expiry, "CE");
	}

	private WingTrial buildPutSpread(
			OptionChainResponse chain,
			List<Double> strikes,
			int sellIdx,
			int buyIdx,
			int otmSteps,
			boolean debit,
			LocalDate expiry) {
		double sellStrike = strikes.get(sellIdx);
		double buyStrike = strikes.get(buyIdx);
		OptionSide sellSide = findSide(chain, sellStrike, false);
		OptionSide buySide = findSide(chain, buyStrike, false);
		if (sellSide == null || buySide == null || sellSide.ltp() == null || buySide.ltp() == null) {
			return null;
		}
		return metrics(sellSide, buySide, sellStrike, buyStrike, otmSteps, debit, expiry, "PE");
	}

	private WingTrial metrics(
			OptionSide shortCandidate,
			OptionSide longCandidate,
			double sellStrike,
			double buyStrike,
			int otmSteps,
			boolean debit,
			LocalDate expiry,
			String kind) {
		// Credit default: SELL higher premium (ITM), BUY cheaper (OTM)
		OptionSide sold = debit ? longCandidate : shortCandidate;
		OptionSide bought = debit ? shortCandidate : longCandidate;
		double soldStrike = debit ? buyStrike : sellStrike;
		double boughtStrike = debit ? sellStrike : buyStrike;
		String soldSide = "SELL";
		String boughtSide = "BUY";

		double soldPrem = sold.ltp();
		double boughtPrem = bought.ltp();
		double width = Math.abs(soldStrike - boughtStrike);
		double netCredit = soldPrem - boughtPrem; // positive = credit
		if (debit) {
			// Inverted: buy expensive, sell cheap → net debit (negative credit)
			netCredit = soldPrem - boughtPrem;
		}
		double creditPerLot = netCredit;
		double maxProfitPerLot;
		double maxLossPerLot;
		if (creditPerLot >= 0) {
			maxProfitPerLot = creditPerLot;
			maxLossPerLot = width - creditPerLot;
		}
		else {
			maxProfitPerLot = width + creditPerLot; // credit negative
			maxLossPerLot = Math.abs(creditPerLot);
		}
		if (maxLossPerLot <= 0) {
			maxLossPerLot = 0.01;
		}
		double rr = maxProfitPerLot / maxLossPerLot;
		Integer lotSize = sold.lotSize() != null ? sold.lotSize() : bought.lotSize();

		List<SuggestedLeg> legs = List.of(
				new SuggestedLeg(
						sold.instrumentKey(),
						sold.tradingSymbol(),
						kind,
						soldSide,
						soldStrike,
						expiry.toString(),
						1,
						lotSize,
						soldPrem),
				new SuggestedLeg(
						bought.instrumentKey(),
						bought.tradingSymbol(),
						kind,
						boughtSide,
						boughtStrike,
						expiry.toString(),
						1,
						lotSize,
						boughtPrem));
		return new WingTrial(otmSteps, soldStrike, boughtStrike, creditPerLot, maxProfitPerLot, maxLossPerLot, rr, legs);
	}

	private static OptionSide findSide(OptionChainResponse chain, double strike, boolean call) {
		for (OptionStrikeRow row : chain.strikes()) {
			if (Double.compare(row.strike(), strike) == 0) {
				return call ? row.call() : row.put();
			}
		}
		return null;
	}

	private static int nearestStrikeIndex(List<Double> strikes, double spot) {
		int best = 0;
		double bestDist = Double.MAX_VALUE;
		for (int i = 0; i < strikes.size(); i++) {
			double d = Math.abs(strikes.get(i) - spot);
			if (d < bestDist) {
				bestDist = d;
				best = i;
			}
		}
		return best;
	}
}
