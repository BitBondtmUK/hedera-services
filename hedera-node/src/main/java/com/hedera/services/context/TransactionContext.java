package com.hedera.services.context;

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

import com.hedera.services.utils.PlatformTxnAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TopicID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hederahashgraph.api.proto.java.TransferList;

import java.time.Instant;

/**
 * Defines a type that manages transaction-specific context for a node. (That is,
 * context built while processing a consensus transaction.) Most of this context
 * is ultimately captured by a {@link TransactionRecord}, so the core
 * responsibility of this type is to construct an appropriate record in method
 * {@code recordSoFar}.
 *
 * @author Michael Tinker
 */
public interface TransactionContext {
	/**
	 * Clear the context and processing history, initialize for a new consensus txn.
	 *
	 * @param accessor the consensus platform txn to manage context of.
	 * @param consensusTime when the txn reached consensus.
	 */
	void resetFor(PlatformTxnAccessor accessor, Instant consensusTime, long submittingMember);

	/**
	 * Checks if the payer is known to have an active signature (that is, whether
	 * the txn includes enough valid cryptographic signatures to activate the payer's
	 * Hedera key).
	 *
	 * @return whether the payer sig is known active
	 */
	boolean isPayerSigKnownActive();

	/**
	 * The Hedera account id of the node that submitted the current txn.
	 *
	 * @return the account id
	 */
	AccountID submittingNodeAccount();

	/**
	 * The member id of the node that submitted the current txn.
	 *
	 * @return the member id
	 */
	long submittingSwirldsMember();

	/**
	 * Returns the Hedera account id paying for the current txn
	 *
	 * @throws IllegalStateException if there is no active txn
	 * @return the ad
	 */
	AccountID activePayer();

	default AccountID effectivePayer() {
		return isPayerSigKnownActive() ? activePayer() : submittingNodeAccount();
	}

	/**
	 * If there is an active payer signature, returns the Hedera key used to sign.
	 *
	 * @return the Hedera key used for the active payer sig
	 */
	JKey activePayerKey();

	/**
	 * Gets the consensus time of the txn being processed.
	 *
	 * @return the instant of consensus for the current txn.
	 */
	Instant consensusTime();

	/**
	 * Gets the current status of the txn being processed. In general, the initial
	 * value should be {@code UNKNOWN}, and any final status other than {@code SUCCESS}
	 * should indicate an invalid transaction.
	 *
	 * @return the status of processing the current txn thus far.
	 */
	ResponseCodeEnum status();

	/**
	 * Constructs and gets a {@link TransactionRecord} which captures the history
	 * of processing the current txn up to the time of the call.
	 *
	 * @return the historical record of processing the current txn thus far.
	 */
	TransactionRecord recordSoFar();

	/**
	 * Returns the last record created by {@link TransactionContext#recordSoFar()},
	 * with the transfer list and fees updated.
	 *
	 * @param listWithNewFees the new transfer list to use in the record.
	 * @return the updated historical record of processing the current txn thus far.
	 * @throws IllegalStateException if {@code recordSoFar} has not been called for the active txn.
	 */
	TransactionRecord updatedRecordGiven(TransferList listWithNewFees);

	/**
	 * Gets an accessor to the consensus {@link com.swirlds.common.Transaction}
	 * currently being processed.
	 *
	 * @return accessor for the current txn.
	 */
	PlatformTxnAccessor accessor();

	/**
	 * Set a new status for the current txn's processing.
	 *
	 * @param status the new status of processing the current txn.
	 */
	void setStatus(ResponseCodeEnum status);

	/**
	 * Record that the current transaction created a file.
	 *
	 * @param id the created file.
	 */
	void setCreated(FileID id);

	/**
	 * Record that the current transaction created a crypto account.
	 *
	 * @param id the created account.
	 */
	void setCreated(AccountID id);

	/**
	 * Record that the current transaction created a smart contract.
	 *
	 * @param id the created contract.
	 */
	void setCreated(ContractID id);

	/**
	 * Record that the current transaction created a consensus topic.
	 *
	 * @param id the created topic.
	 */
	void setCreated(TopicID id);

	/**
	 * Record that the current transaction called a smart contract with
	 * a specified result.
	 *
	 * @param result the result of the contract call.
	 */
	void setCallResult(ContractFunctionResult result);

	/**
	 * Record that the current transaction created a smart contract with
	 * a specified result.
	 *
	 * @param result the result of the contract creation.
	 */
	void setCreateResult(ContractFunctionResult result);

	/**
	 * Record that an additional fee was deducted from the payer of the
	 * current txn.
	 *
	 * @param amount the extra amount deducted from the current txn's payer.
	 */
	void addNonThresholdFeeChargedToPayer(long amount);

	/**
	 * Record that the payer of the current txn is known to have an active
	 * signature (that is, the txn includes enough valid cryptographic
	 * signatures to activate the payer's Hedera key).
	 */
	void payerSigIsKnownActive();

	/**
	 * Update the topic's running hash and sequence number.
	 * @param runningHash
	 * @param sequenceNumber
	 */
	void setTopicRunningHash(byte[] runningHash, long sequenceNumber);
}
