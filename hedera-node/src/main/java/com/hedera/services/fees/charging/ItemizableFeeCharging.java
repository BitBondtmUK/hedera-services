package com.hedera.services.fees.charging;

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

import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.fees.FeeExemptions;
import com.hedera.services.fees.TxnFeeType;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TransferList;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static com.hedera.services.fees.TxnFeeType.*;

/**
 * A {@link FieldSourcedFeeScreening} which also implements
 * {@link TxnScopedFeeCharging} via an injected {@link HederaLedger}.
 * Keeps a history of per-transaction fees charging activity to
 * make it easy to construct the final {@link com.hederahashgraph.api.proto.java.TransactionRecord}.
 *
 * @author Michael Tinker
 */
public class ItemizableFeeCharging extends FieldSourcedFeeScreening implements TxnScopedFeeCharging {
	public static EnumSet<TxnFeeType> NODE_FEE = EnumSet.of(NODE);
	public static EnumSet<TxnFeeType> NETWORK_FEE = EnumSet.of(NETWORK);
	public static EnumSet<TxnFeeType> NETWORK_NODE_SERVICE_FEES = EnumSet.of(NETWORK, NODE, SERVICE);
	public static EnumSet<TxnFeeType> CACHE_RECORD_FEE = EnumSet.of(CACHE_RECORD);
	public static EnumSet<TxnFeeType> THRESHOLD_RECORD_FEE = EnumSet.of(THRESHOLD_RECORD);

	private HederaLedger ledger;
	private final FeeExemptions exemptions;
	private final PropertySource properties;

	AccountID node;
	AccountID funding;
	AccountID submittingNode;
	Set<AccountID> thresholdFeePayers = new HashSet<>();
	EnumMap<TxnFeeType, Long> payerFeesCharged = new EnumMap<>(TxnFeeType.class);
	EnumMap<TxnFeeType, Long> submittingNodeFeesCharged = new EnumMap<>(TxnFeeType.class);

	public ItemizableFeeCharging(
			FeeExemptions exemptions,
			PropertySource properties
	) {
		super(exemptions);
		this.exemptions = exemptions;
		this.properties = properties;
	}

	public void setLedger(HederaLedger ledger) {
		this.ledger = ledger;
		setBalanceCheck((payer, amount) -> ledger.getBalance(payer) >= amount);
	}

	public long totalNonThresholdFeesChargedToPayer() {
		return payerFeesCharged.values().stream().mapToLong(Long::longValue).sum();
	}

	public long chargedToPayer(TxnFeeType fee) {
		return Optional.ofNullable(payerFeesCharged.get(fee)).orElse(0L);
	}

	public long chargedToSubmittingNode(TxnFeeType fee) {
		return Optional.ofNullable(submittingNodeFeesCharged.get(fee)).orElse(0L);
	}

	public int numThresholdFeesCharged() {
		return thresholdFeePayers.size();
	}

	public void resetFor(SignedTxnAccessor accessor, AccountID submittingNode) {
		super.resetFor(accessor);

		node = accessor.getTxn().getNodeAccountID();
		funding = properties.getAccountProperty("ledger.funding.account");
		this.submittingNode = submittingNode;

		payerFeesCharged.clear();
		thresholdFeePayers.clear();
		submittingNodeFeesCharged.clear();
	}

	/**
	 * Fees for a txn submitted by an a irresponsible node are itemized as:
	 * <ol>
	 *    <li>Received by funding, sent by the submitting node, for network operating costs.</li>
	 * </ol>
	 *
	 * Fees for a correctly signed txn submitted by a responsible node are itemized as:
	 * <ol>
	 *    <li>Received by funding, sent by the txn payer, for network operating costs.</li>
	 *    <li>Received by the submitting node, sent by the txn payer, for handling costs.</li>
	 *    <li>Received by funding, sent by the txn payer, for service costs.</li>
	 *    <li>Sent by the txn payer, received by funding, for record caching costs.</li>
	 *    <li>Received by funding, sent by an interested participant, for a threshold record.</li>
	 *    <li><i>...[ordered by account numbers of interested participants]...</i></li>
	 *    <li>Received by funding, sent by an interested participant, for a threshold record.</li>
	 * </ol>
	 *
	 * <b>NOTE:</b> We reverse the order of {@link com.hederahashgraph.api.proto.java.AccountAmount}
	 * entries when itemizing the record caching fee to distinguish it from the service fee or a
	 * threshold record fee assessed to the txn payer.
	 *
	 * @return the itemized charges in canonical order
	 */
	public TransferList itemizedFees() {
		TransferList.Builder fees = TransferList.newBuilder();

		if (!submittingNodeFeesCharged.isEmpty()) {
			includeIfCharged(NETWORK, submittingNode, submittingNodeFeesCharged, fees);
		} else {
			AccountID payer = accessor.getPayer();

			includeIfCharged(NETWORK, payer, payerFeesCharged, fees);
			includeIfCharged(NODE, payer, payerFeesCharged, fees);
			includeIfCharged(SERVICE, payer, payerFeesCharged, fees);
			includeIfCharged(CACHE_RECORD, payer, payerFeesCharged, fees);
		}

		if (!thresholdFeePayers.isEmpty()) {
			long thresholdRecordFee = feeAmounts.get(THRESHOLD_RECORD);
			thresholdFeePayers.stream()
					.sorted(HederaLedger.ACCOUNT_ID_COMPARATOR)
					.forEach(id -> fees.addAllAccountAmounts(receiverFirst(id, funding, thresholdRecordFee)));
		}

		return fees.build();
	}

	private void includeIfCharged(
			TxnFeeType fee,
			AccountID source,
			EnumMap<TxnFeeType, Long> feesCharged,
			TransferList.Builder fees
	) {
		if (feesCharged.containsKey(fee)) {
			AccountID receiver = (fee == NODE) ? node : funding;
			if (fee == CACHE_RECORD) {
				fees.addAllAccountAmounts(senderFirst(source, receiver, feesCharged.get(CACHE_RECORD)));
			} else {
				fees.addAllAccountAmounts(receiverFirst(source, receiver, feesCharged.get(fee)));
			}
		}
	}

	private List<AccountAmount> receiverFirst(AccountID payer, AccountID receiver, long amount) {
		return itemized(payer, receiver, amount, true);
	}
	private List<AccountAmount> senderFirst(AccountID payer, AccountID receiver, long amount) {
		return itemized(payer, receiver, amount, false);
	}
	private List<AccountAmount> itemized(AccountID payer, AccountID receiver, long amount, boolean isReceiverFirst) {
		return List.of(
				AccountAmount.newBuilder()
						.setAccountID(isReceiverFirst ? receiver : payer)
						.setAmount(isReceiverFirst ? amount : -1 * amount)
						.build(),
				AccountAmount.newBuilder()
						.setAccountID(isReceiverFirst ? payer : receiver)
						.setAmount(isReceiverFirst ? -1 * amount : amount)
						.build());
	}

	@Override
	public void chargeSubmittingNodeUpTo(EnumSet<TxnFeeType> fees) {
		pay(
				fees,
				() -> {},
				(fee) -> chargeUpTo(submittingNode, funding, fee));
	}

	@Override
	public void chargePayer(EnumSet<TxnFeeType> fees) {
		chargeParticipant(accessor.getPayer(), fees);
	}

	@Override
	public void chargePayerUpTo(EnumSet<TxnFeeType> fees) {
		pay(
				fees,
				() -> chargeUpTo(accessor.getPayer(), node, NODE),
				(fee) -> chargeUpTo(accessor.getPayer(), funding, fee));
	}

	@Override
	public void chargeParticipant(AccountID participant, EnumSet<TxnFeeType> fees) {
		pay(
				fees,
				() -> charge(participant, node, NODE),
				fee -> charge(participant, funding, fee));
	}

	private void pay(
			EnumSet<TxnFeeType> fees,
			Runnable nodePayment,
			Consumer<TxnFeeType> fundingPayment
	) {
		/* Treasury gets priority over node. */
		for (TxnFeeType fee : fees) {
			if (fee != NODE) {
				fundingPayment.accept(fee);
			}
		}

		if (fees.contains(NODE)) {
			nodePayment.run();
		}
	}

	private void charge(AccountID payer, AccountID payee, TxnFeeType fee) {
		if (noCharge(payer, payee, fee)) {
			return;
		}
		long amount = feeAmounts.get(fee);
		completeNonVanishing(payer, payee, amount, fee);
	}

	private void chargeUpTo(AccountID payer, AccountID payee, TxnFeeType fee) {
		if (noCharge(payer, payee, fee)) {
			return;
		}
		long actionableAmount = Math.min(ledger.getBalance(payer), feeAmounts.get(fee));
		completeNonVanishing(payer, payee, actionableAmount, fee);
	}

	private void completeNonVanishing(AccountID payer, AccountID payee, long amount, TxnFeeType fee) {
		if (amount > 0)	 {
			ledger.doTransfer(payer, payee, amount);
			updateRecords(payer, fee, amount);
		}
	}

	private boolean noCharge(AccountID payer, AccountID payee, TxnFeeType fee) {
		if (payer.equals(payee)) {
			return true;
		} else if (payer.equals(accessor.getPayer()) && isPayerExempt()) {
			return true;
		} else if (fee == THRESHOLD_RECORD && exemptions.isExemptFromRecordFees(payer)) {
			return true;
		} else {
			return false;
		}
	}

	private void updateRecords(AccountID source, TxnFeeType fee, long amount) {
		if (fee == THRESHOLD_RECORD) {
			thresholdFeePayers.add(source);
		} else {
			if (source.equals(accessor.getPayer())) {
				payerFeesCharged.put(fee, amount);
			}
			if (source.equals(submittingNode)) {
				submittingNodeFeesCharged.put(fee, amount);
			}
		}
	}
}
