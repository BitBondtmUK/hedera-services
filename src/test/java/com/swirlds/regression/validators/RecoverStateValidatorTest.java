/*
 * (c) 2016-2019 Swirlds, Inc.
 *
 * This software is the confidential and proprietary information of
 * Swirlds, Inc. ("Confidential Information"). You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Swirlds.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

package com.swirlds.regression.validators;


import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoverStateValidatorTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"logs/recoverState/successRun"
	})
	void validRecoverStateLog(String testDir) throws IOException {
		System.out.println("Dir: " + testDir);
		List<NodeData> nodeData = ValidatorTestUtil.loadNodeData(testDir, "PlatformTesting", 1);
		NodeValidator validator = new RecoverStateValidator(nodeData);
		validator.validate();
		for (String msg : validator.getInfoMessages()) {
			System.out.println("INFO: " + msg);
		}
		for (String msg : validator.getErrorMessages()) {
			System.out.println("ERROR: " + msg);
		}
		assertEquals(true, validator.isValid());
	}


	@ParameterizedTest
	@ValueSource(strings = {
			"logs/recoverState/successRun"
	})
	void validateSuccess(final String testDir) {
		final List<StreamingServerData> data = ValidatorTestUtil.loadStreamingServerData(testDir);
		final StreamingServerValidator validator = new StreamingServerValidator(data, false);
		validator.validate();

		System.out.println(validator.concatAllMessages());

		assertTrue(validator.isValid());
	}
}