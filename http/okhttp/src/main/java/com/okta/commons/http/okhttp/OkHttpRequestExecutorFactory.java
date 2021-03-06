/*
 * Copyright 2018-Present Okta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.okta.commons.http.okhttp;

import com.google.auto.service.AutoService;
import com.okta.commons.http.RequestExecutor;
import com.okta.commons.http.RequestExecutorFactory;
import com.okta.commons.http.RetryRequestExecutor;
import com.okta.commons.http.config.HttpClientConfiguration;

/**
 * @since 1.2.0
 */
@AutoService(RequestExecutorFactory.class)
public class OkHttpRequestExecutorFactory implements RequestExecutorFactory {

    @Override
    public RequestExecutor create(HttpClientConfiguration clientConfiguration) {
        return new RetryRequestExecutor(clientConfiguration, new OkHttpRequestExecutor(clientConfiguration));
    }
}
