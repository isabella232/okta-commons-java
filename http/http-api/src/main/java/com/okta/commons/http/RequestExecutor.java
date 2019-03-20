/*
 * Copyright 2014 Stormpath, Inc.
 * Modifications Copyright 2018 Okta, Inc.
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
package com.okta.commons.http;

import com.okta.commons.lang.Threads;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * @since 0.5.0
 */
public interface RequestExecutor {

    CompletableFuture<Response> executeRequestAsync(Request request, ExecutorService executorService) throws RestException;

    default CompletableFuture<Response> executeRequestAsync(Request request) throws RestException {
        return executeRequestAsync(request, ForkJoinPool.commonPool());
    }

    default Response executeRequest(Request request) throws RestException {
        return executeRequestAsync(request, Threads.synchronousExecutorService()).join();
    }

}
