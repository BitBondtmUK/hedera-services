package com.hedera.services.context.properties;

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

import com.hedera.services.legacy.config.PropertiesLoader;
import com.hedera.services.legacy.logic.ApplicationConstants;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.Setting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.context.properties.BootstrapProperties.BOOTSTRAP_PROP_NAMES;
import static com.hedera.services.context.properties.Profile.DEV;
import static com.hedera.services.context.properties.Profile.PROD;
import static com.hedera.services.context.properties.Profile.TEST;
import static com.hedera.services.legacy.config.PropertiesLoader.getEnvironment;
import static com.hedera.services.legacy.config.PropertiesLoader.getSaveAccounts;
import static com.hedera.services.legacy.config.PropertiesLoader.getUniqueListeningPortFlag;
import static com.hedera.services.throttling.ThrottlingPropsBuilder.API_THROTTLING_CONFIG_PREFIX;
import static com.hedera.services.throttling.ThrottlingPropsBuilder.API_THROTTLING_PREFIX;
import static com.hedera.services.throttling.ThrottlingPropsBuilder.DEFAULT_QUERY_CAPACITY_REQUIRED_PROPERTY;
import static com.hedera.services.throttling.ThrottlingPropsBuilder.DEFAULT_TXN_CAPACITY_REQUIRED_PROPERTY;
import static com.hedera.services.throttling.bucket.BucketConfig.DEFAULT_BURST_PROPERTY;
import static com.hedera.services.throttling.bucket.BucketConfig.DEFAULT_CAPACITY_PROPERTY;

/**
 * Implements a {@link PropertySources} that re-resolves every property
 * reference by delegating to a {@link PropertiesLoader} method or other
 * supplier.
 *
 * The main purpose of this implementation is standardize property naming
 * and access conventions across the codebase, which will greatly simplify
 * the task of refactoring {@link PropertiesLoader}.
 *
 * @author Michael Tinker
 */
public class StandardizedPropertySources implements PropertySources {
	public static final Logger log = LogManager.getLogger(StandardizedPropertySources.class);

	public static final String RESPECT_LEGACY_THROTTLING_PROPERTY = API_THROTTLING_CONFIG_PREFIX + ".useLegacyProps";

	private static final int ISS_RESET_PERIOD_SECS = 30;
	private static final int ISS_ROUNDS_TO_DUMP = 5;
	public static final String VERSION_INFO_PROPERTIES_FILE = "semantic-version.properties";
	public static final String VERSION_INFO_PROPERTIES_PROTO_KEY = "hapi.proto.version";
	public static final String VERSION_INFO_PROPERTIES_SERVICES_KEY = "hedera.services.version";
	public static final int MAX_MEMO_UTF8_BYTES = 100;
	public static final int PRE_CONSENSUS_ACCOUNT_KEY_MAX_LOOKUP_RETRIES = 10;
	public static final int PRE_CONSENSUS_ACCOUNT_KEY_RETRY_BACKOFF_INCREMENT_MS = 10;
	public static final long LONG_MASK = 0xffffffffL;

	private static final Profile[] LEGACY_ENV_ORDER = { DEV, PROD, TEST };

	private final PropertySource bootstrapProps;
	private final Predicate<String> fileSourceExists;
	private final Map<String, Object> throttlePropsFromSysFile = new HashMap<>();

	public StandardizedPropertySources(
			PropertySource bootstrapProps,
			Predicate<String> fileSourceExists
	) {
		this.bootstrapProps = bootstrapProps;
		this.fileSourceExists = fileSourceExists;

		throttlePropsFromSysFile.put(RESPECT_LEGACY_THROTTLING_PROPERTY, true);
	}

	public void updateThrottlePropsFrom(ServicesConfigurationList config) {
		log.info("Updating throttle props from {} candidates", config.getNameValueCount());
		throttlePropsFromSysFile.clear();
		for (Setting setting : config.getNameValueList())  {
			var name = setting.getName();
			if (!name.startsWith(API_THROTTLING_PREFIX)) {
				continue;
			}
			if (name.equals(RESPECT_LEGACY_THROTTLING_PROPERTY)) {
				putBoolean(RESPECT_LEGACY_THROTTLING_PROPERTY, setting.getValue());
			} else if (isDoubleProp(name)) {
				putDouble(name, setting.getValue());
			} else {
				throttlePropsFromSysFile.put(name, setting.getValue());
			}
		}
		if (!throttlePropsFromSysFile.containsKey(RESPECT_LEGACY_THROTTLING_PROPERTY)) {
			throttlePropsFromSysFile.put(RESPECT_LEGACY_THROTTLING_PROPERTY, true);
		}
	}

	private void putBoolean(String name, String literal) {
		throttlePropsFromSysFile.put(name, Boolean.parseBoolean(literal));
	}

	private static final Set<String> DEFAULT_DOUBLE_PROPS = Set.of(
			DEFAULT_BURST_PROPERTY,
			DEFAULT_CAPACITY_PROPERTY,
			DEFAULT_TXN_CAPACITY_REQUIRED_PROPERTY,
			DEFAULT_QUERY_CAPACITY_REQUIRED_PROPERTY);
	private boolean isDoubleProp(String name) {
		if (DEFAULT_DOUBLE_PROPS.contains(name)) {
			return true;
		}
		if (name.endsWith("burstPeriod") || name.endsWith("capacity") || name.endsWith("capacityRequired")) {
			return true;
		}
		return false;
	}
	private void putDouble(String name, String literal) {
		try {
			throttlePropsFromSysFile.put(name, Double.parseDouble(literal));
		} catch (NumberFormatException nfe) {
			log.warn("Ignoring config: {}={}", name, literal, nfe);
		}
	}

	@Override
	public void assertSourcesArePresent() {
		assertPropertySourcesExist();
	}

	private void assertPropertySourcesExist() {
		assertFileSourceExists(bootstrapProps.getStringProperty("bootstrap.networkProperties.path"));
		assertFileSourceExists(bootstrapProps.getStringProperty("bootstrap.hapiPermissions.path"));
	}

	private void assertFileSourceExists(String path) {
		if (!fileSourceExists.test(path)) {
			throw new IllegalStateException(String.format("Fatal error, no '%s' file exists!", path));
		}
	}

	@Override
	public PropertySource asResolvingSource() {
		var prioritySource = new SupplierMapPropertySource(sourceMap());

		return new DeferringPropertySource(prioritySource, throttlePropsFromSysFile);
	}

	private Map<String, Supplier<Object>> sourceMap() {
		Supplier<Object> maxLookupRetries = () -> PRE_CONSENSUS_ACCOUNT_KEY_MAX_LOOKUP_RETRIES;
		Supplier<Object> retryBackoffIncrementMs = () -> PRE_CONSENSUS_ACCOUNT_KEY_RETRY_BACKOFF_INCREMENT_MS;

		Map<String, Supplier<Object>> source = new HashMap<>();

		/* Bootstrap properties, which include all global/static properties. */
		BOOTSTRAP_PROP_NAMES.forEach(name -> source.put(name, () -> bootstrapProps.getProperty(name)));
		source.put("ledger.funding.account", PropertiesLoader::getFeeCollectionAccount);

		/* Global/dynamic properties. */
		source.put("cache.records.ttl", PropertiesLoader::getTxReceiptTTL);
		source.put("contracts.maxStorageKb", PropertiesLoader::getMaxContractStateSize);
		source.put("contracts.defaultSendThreshold", PropertiesLoader::getDefaultContractSenderThreshold);
		source.put("contracts.defaultReceiveThreshold", PropertiesLoader::getDefaultContractReceiverThreshold);
		source.put("exchangeRates.intradayChange.limitPercent", PropertiesLoader::getExchangeRateAllowedPercentage);
		source.put("files.maxSizeKb", PropertiesLoader::getMaxFileSize);
		source.put("hedera.transaction.maxMemoUtf8Bytes", () -> MAX_MEMO_UTF8_BYTES);
		source.put("hedera.transaction.maxValidDuration", () -> PropertiesLoader.getTxMaxDuration() & LONG_MASK);
		source.put("hedera.transaction.minValidDuration", () -> PropertiesLoader.getTxMinDuration() & LONG_MASK);
		source.put("hedera.transaction.minValidityBufferSecs", PropertiesLoader::getTxMinRemaining);
		source.put("ledger.autoRenewPeriod.maxDuration", PropertiesLoader::getMaximumAutorenewDuration);
		source.put("ledger.autoRenewPeriod.minDuration", PropertiesLoader::getMinimumAutorenewDuration);
		source.put("ledger.maxAccountNum", PropertiesLoader::getConfigAccountNum);
		source.put("ledger.records.ttl", PropertiesLoader::getThresholdTxRecordTTL);
		source.put("ledger.transfers.maxLen", PropertiesLoader::getTransferAccountListSize);
		source.put("hedera.recordStream.logDir", PropertiesLoader::getRecordLogDir);
		source.put("hedera.recordStream.logPeriod", PropertiesLoader::getRecordLogPeriod);
		source.put("throttlingTps", PropertiesLoader::getThrottlingTps);
		source.put("queriesTps", PropertiesLoader::getQueriesTps);
		source.put("simpletransferTps", PropertiesLoader::getSimpleTransferTps);
		source.put("getReceiptTps", PropertiesLoader::getGetReceiptTps);
		source.put("throttling.hcs.createTopic.tps", PropertiesLoader::getCreateTopicTps);
		source.put("throttling.hcs.createTopic.burstPeriod", PropertiesLoader::getCreateTopicBurstPeriod);
		source.put("throttling.hcs.updateTopic.tps", PropertiesLoader::getUpdateTopicTps);
		source.put("throttling.hcs.updateTopic.burstPeriod", PropertiesLoader::getUpdateTopicBurstPeriod);
		source.put("throttling.hcs.deleteTopic.tps", PropertiesLoader::getDeleteTopicTps);
		source.put("throttling.hcs.deleteTopic.burstPeriod", PropertiesLoader::getDeleteTopicBurstPeriod);
		source.put("throttling.hcs.submitMessage.tps", PropertiesLoader::getSubmitMessageTps);
		source.put("throttling.hcs.submitMessage.burstPeriod", PropertiesLoader::getSubmitMessageBurstPeriod);
		source.put("throttling.hcs.getTopicInfo.tps", PropertiesLoader::getGetTopicInfoTps);
		source.put("throttling.hcs.getTopicInfo.burstPeriod", PropertiesLoader::getGetTopicInfoBurstPeriod);

		/* Node-local/static properties. */
		source.put("dev.defaultListeningNodeAccount", PropertiesLoader::getDefaultListeningNodeAccount);
		source.put("dev.onlyDefaultNodeListens", () -> getUniqueListeningPortFlag() != 1);
		source.put("grpc.port", PropertiesLoader::getPort);
		source.put("grpc.tlsPort", PropertiesLoader::getTlsPort);
		source.put("hedera.accountsExportPath", PropertiesLoader::getExportedAccountPath);
		source.put("hedera.exportAccountsOnStartup", () -> getSaveAccounts().equals("YES"));
		source.put("hedera.exportBalancesOnNewSignedState", PropertiesLoader::isAccountBalanceExportEnabled);
		source.put("hedera.profiles.active", () -> LEGACY_ENV_ORDER[getEnvironment()]);
		source.put("hedera.versionInfo.resource", () -> VERSION_INFO_PROPERTIES_FILE);
		source.put("hedera.versionInfo.protoKey", () -> VERSION_INFO_PROPERTIES_PROTO_KEY);
		source.put("hedera.versionInfo.servicesKey", () -> VERSION_INFO_PROPERTIES_SERVICES_KEY);
		source.put("iss.reset.periodSecs", () -> ISS_RESET_PERIOD_SECS);
		source.put("iss.roundsToDump", () -> ISS_ROUNDS_TO_DUMP);
		source.put("validation.preConsensus.accountKey.maxLookupRetries", maxLookupRetries);
		source.put("validation.preConsensus.accountKey.retryBackoffIncrementMs", retryBackoffIncrementMs);

		/* Node-local/dynamic properties. */
		source.put("timer.stats.dump.started", PropertiesLoader::getStartStatsDumpTimer);
		source.put("timer.stats.dump.value", PropertiesLoader::getStatsDumpTimerValue);

		return source;
	}
}
