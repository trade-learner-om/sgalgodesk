package com.sgalgodesk.desk.application.optionchain;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockOptionChainService;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionChainResponse;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class OptionChainService {

	private final MstockOptionChainService mstockOptionChainService;

	public Mono<OptionChainResponse> getChain(UUID userId, String instrumentKey, String expiryDate) {
		return mstockOptionChainService.getChain(userId, instrumentKey, expiryDate);
	}
}
