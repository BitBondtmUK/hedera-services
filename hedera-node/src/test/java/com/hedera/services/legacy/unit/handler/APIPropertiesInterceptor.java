package com.hedera.services.legacy.unit.handler;

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

import com.hedera.services.fees.calculation.FeeCalcUtilsTest;
import com.hedera.services.legacy.unit.FCStorageWrapper;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hedera.services.legacy.config.PropertiesLoader;
import com.hedera.services.legacy.logic.ApplicationConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class APIPropertiesInterceptor implements GenericInterceptor {


  private static final Logger log = LogManager.getLogger(APIPropertiesInterceptor.class);
  public APIPropertiesInterceptor() {

  }

  @Override
  public void update(FCStorageWrapper storageWrapper, FileID fid) {
    if(fid.getFileNum()!= 122) {
      return; // Don't update if FileID is not a API Properties File
    }
    String fileDataPath = FeeCalcUtilsTest.pathOf(fid);
    ServicesConfigurationList configValues = null;
    if (storageWrapper.fileExists(fileDataPath)) {
      try {
        byte[] configValuesBytes = storageWrapper.fileRead(fileDataPath);
        configValues = ServicesConfigurationList.parseFrom(configValuesBytes);	        
        if( configValues.getNameValueList() != null) {
    		PropertiesLoader.populateAPIPropertiesWithProto(configValues);
        	log.info("***Valid API Properties File refreshed in System.. loaded! ***");	        
        return;
        }
      } catch (Exception e1) {
        log.error(
            "Error while updating API Properties from latest Proto... earlier one will be used", e1);
      }
    }
  }
}
