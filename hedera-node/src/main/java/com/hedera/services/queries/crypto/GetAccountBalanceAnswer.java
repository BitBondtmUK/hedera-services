package com.hedera.services.queries.crypto;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.context.primitives.StateView;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.queries.AnswerService;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoGetAccountBalanceQuery;
import com.hederahashgraph.api.proto.java.CryptoGetAccountBalanceResponse;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hedera.services.legacy.core.MapKey;
import com.hedera.services.context.domain.haccount.HederaAccount;
import com.swirlds.fcmap.FCMap;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoGetAccountBalance;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.services.legacy.core.MapKey.getMapKey;

import java.util.Optional;
import static com.hedera.services.utils.EntityIdUtils.asAccount;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

public class GetAccountBalanceAnswer implements AnswerService {
	private final OptionValidator optionValidator;

	public GetAccountBalanceAnswer(OptionValidator optionValidator) {
		this.optionValidator = optionValidator;
	}

	@Override
	public ResponseCodeEnum checkValidity(Query query, StateView view) {
		FCMap<MapKey, HederaAccount> accounts = view.accounts();
		CryptoGetAccountBalanceQuery op = query.getCryptogetAccountBalance();
		return validityOf(op, accounts);
	}

	@Override
	public boolean requiresNodePayment(Query query) {
		return false;
	}

	@Override
	public boolean needsAnswerOnlyCost(Query query) {
		return false;
	}

	@Override
	public Response responseGiven(Query query, StateView view, ResponseCodeEnum validity, long cost) {
		FCMap<MapKey, HederaAccount> accounts = view.accounts();
		CryptoGetAccountBalanceQuery op = query.getCryptogetAccountBalance();

		AccountID id = targetOf(op);
		CryptoGetAccountBalanceResponse.Builder opAnswer = CryptoGetAccountBalanceResponse.newBuilder()
				.setHeader(answerOnlyHeader(validity))
				.setAccountID(id);

		if (validity == OK) {
			MapKey key = getMapKey(id);
			opAnswer.setBalance(accounts.get(key).getBalance());
		}

		return Response.newBuilder().setCryptogetAccountBalance(opAnswer).build();
	}

	@Override
	public Optional<SignedTxnAccessor> extractPaymentFrom(Query query) {
		return Optional.empty();
	}

	private AccountID targetOf(CryptoGetAccountBalanceQuery op) {
		return op.hasAccountID()
				? op.getAccountID()
				: (op.hasContractID() ? asAccount(op.getContractID()) : AccountID.getDefaultInstance());
	}

	private ResponseCodeEnum validityOf(
			CryptoGetAccountBalanceQuery op,
			FCMap<MapKey, HederaAccount> accounts
	) {
		if (op.hasContractID()) {
			return optionValidator.queryableContractStatus(op.getContractID(), accounts);
		} else if (op.hasAccountID()) {
			return optionValidator.queryableAccountStatus(op.getAccountID(), accounts);
		} else {
			return INVALID_ACCOUNT_ID;
		}
	}

	@Override
	public ResponseCodeEnum extractValidityFrom(Response response) {
		return response.getCryptogetAccountBalance().getHeader().getNodeTransactionPrecheckCode();
	}

	@Override
	public HederaFunctionality canonicalFunction() {
		return CryptoGetAccountBalance;
	}
}