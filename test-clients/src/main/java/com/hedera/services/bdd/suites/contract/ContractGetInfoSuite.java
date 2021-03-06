package com.hedera.services.bdd.suites.contract;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;

public class ContractGetInfoSuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(ContractGetInfoSuite.class);
	final String PATH_TO_DELEGATING_CONTRACT_BYTECODE = "testfiles/CreateTrivial.bin";

	public static void main(String... args) {
		new ContractGetInfoSuite().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return allOf(
			negativeSpecs(),
			positiveSpecs()
		);
	}

	private List<HapiApiSpec> negativeSpecs() {
		return Arrays.asList(
			invalidContractFails()
		);
	}

	private List<HapiApiSpec> positiveSpecs() {
		return Arrays.asList(
			vanillaSucceeds()
		);
	}

	private HapiApiSpec vanillaSucceeds() {
		return defaultHapiSpec("VanillaSuceeds")
				.given(
						fileCreate("parentDelegateBytecode").path(PATH_TO_DELEGATING_CONTRACT_BYTECODE),
						contractCreate("parentDelegate")
								.bytecode("parentDelegateBytecode").memo("This is a test.").autoRenewSecs(555L)
				).when().then(
						QueryVerbs.getContractInfo("parentDelegate").hasExpectedInfo());
	}

	private HapiApiSpec invalidContractFails() {
		return defaultHapiSpec("InvalidContractFails")
				.given().when().then(
						QueryVerbs.getContractInfo(HapiSpecSetup.getDefaultInstance().invalidContractName())
								.nodePayment(10L)
								.hasAnswerOnlyPrecheck(INVALID_CONTRACT_ID));
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
