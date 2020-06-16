package com.hedera.services.legacy.handler;

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

import com.hedera.services.files.HederaFs;
import com.hedera.services.legacy.service.GlobalFlag;
import com.hedera.services.utils.UnzipUtility;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.FreezeTransactionBody;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.builder.RequestBuilder;
import com.swirlds.common.Platform;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.hedera.services.context.SingletonContextsManager.CONTEXTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY;
import static com.hederahashgraph.builder.RequestBuilder.getTransactionReceipt;

/**
 * @author Qian
 * FreezeHandler is used in the HGCApp handleTransaction for performing Freeze
 * transactions. Documentation available at index.html#proto.FreezeTransactionBody
 */
public class FreezeHandler {
	private static final Logger log = LogManager.getLogger(FreezeHandler.class);
	private final Platform platform;
	private final HederaFs hfs;

	private GlobalFlag globalFlag;

	private static String TARGET_DIR = "./";
	private static String TEMP_DIR = "./temp";
	private static String TEMP_SDK_DIR = TEMP_DIR + File.separator + "sdk";
	private static String DELETE_FILE = TEMP_DIR + File.separator + "delete.txt";
	private static String CMD_SCRIPT = "exec.sh";
	private static String FULL_SCRIPT_PATH = TEMP_DIR + File.separator + CMD_SCRIPT;

	private FileID updateFeatureFile;

	public FreezeHandler(HederaFs hfs, Platform platform) {
		this.globalFlag = GlobalFlag.getInstance();
		this.platform = platform;
		this.hfs = hfs;
	}

	public TransactionRecord freeze(final TransactionBody transactionBody, final Instant consensusTime) {
		log.debug("FreezeHandler - Handling FreezeTransaction: " + transactionBody);
		FreezeTransactionBody freezeBody = transactionBody.getFreeze();
		TransactionReceipt receipt;
		if (transactionBody.getFreeze().hasUpdateFile()) {
			//save the file ID and will be used after platform goes into maintenance mode
			updateFeatureFile = transactionBody.getFreeze().getUpdateFile();
		}
		try {
			platform.setFreezeTime(
					freezeBody.getStartHour(),
					freezeBody.getStartMin(),
					freezeBody.getEndHour(),
					freezeBody.getEndMin());
			receipt = getTransactionReceipt(ResponseCodeEnum.SUCCESS, globalFlag.getExchangeRateSet());
			log.info("Freeze time starts {}:{} end {}:{}", freezeBody.getStartHour(),
					freezeBody.getStartMin(),
					freezeBody.getEndHour(),
					freezeBody.getEndMin());
		} catch (IllegalArgumentException ex) {
			log.warn("FreezeHandler - freeze fails. {}", ex.getMessage());
			receipt = getTransactionReceipt(INVALID_FREEZE_TRANSACTION_BODY, globalFlag.getExchangeRateSet());
		}

		TransactionRecord.Builder transactionRecord = RequestBuilder.getTransactionRecord(
				transactionBody.getTransactionFee(),
				transactionBody.getMemo(),
				transactionBody.getTransactionID(),
				RequestBuilder.getTimestamp(consensusTime), receipt);
		return transactionRecord.build();
	}

	public void handleUpdateFeature() {
		if (updateFeatureFile == null){
			return;
		}
		log.info("node " + platform.getSelfId() + " running update, CONTEXTS.getContextsCount() =" + CONTEXTS.getContextsCount());

		FileID fileIDtoUse = updateFeatureFile;
		updateFeatureFile = null; // reset to null since next freeze may not need file update
		try {
			File directory = new File(TEMP_DIR);
			if (directory.exists()) {
				// delete everything in it recursively
				FileUtils.cleanDirectory(directory);
			} else {
				directory.mkdir();
			}

			if (hfs.exists(fileIDtoUse)) {
				byte[] fileBytes = hfs.cat(fileIDtoUse);

				//unzip bytes stream to target directory
				UnzipUtility.unzip(fileBytes, TEMP_DIR);

				File sdk_directory = new File(TEMP_SDK_DIR);
				if (sdk_directory.exists()) {
					log.info("Copying files from {} to {}", TEMP_SDK_DIR, TARGET_DIR);
					// copy files recursively to sdk directory
					FileUtils.copyDirectory(new File(TEMP_SDK_DIR), new File(TARGET_DIR));
					FileUtils.deleteDirectory(sdk_directory);
				}

				File deleteTxt = new File(DELETE_FILE);
				if (deleteTxt.exists()) {
					deleteFileFromList(DELETE_FILE);
					deleteTxt.delete();
				}

				File script = new File(FULL_SCRIPT_PATH);
				if (script.exists()) {
					if (script.setExecutable(true)) {
						runScript(FULL_SCRIPT_PATH);
					} else {
						log.error("Could not change to executable permission for file {} ", FULL_SCRIPT_PATH);
					}
				}
			}

		} catch (SecurityException | IOException e) {
			log.error("Exception during handleUpdateFeature ", e);
		}
	}


	private void deleteFileFromList(String deleteFileListName) {
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				new FileInputStream(deleteFileListName), StandardCharsets.UTF_8));) {
			String line;
			// each line of the input stream is a file name to be deleted
			while ((line = br.readLine()) != null) {

				if (line.contains("..")) {
					log.warn("Skip delete file {}", line);
				} else {
					String fullPath = TARGET_DIR + File.separator + line;
					File file = new File(fullPath);
					if (file.exists()) {
						if (file.delete()) {
							log.info("Successfully deleted file {}", fullPath);
						} else {
							log.error("Could not delete file {}", fullPath);
						}
					}
				}
			}
		} catch (SecurityException | IOException e) {
			log.error("Delete file exception ", e);
		}
	}

	private void runScript(String scriptFullPath) {
		try {
			log.info("Start running script: {}", scriptFullPath);
			Runtime.getRuntime().exec(" nohup " + scriptFullPath );
		} catch (SecurityException | NullPointerException | IllegalArgumentException | IOException e) {
			log.error("Run script exception ", e);
		}
	}
}