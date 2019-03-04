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
package com.okta.commons.http.impl

import com.okta.commons.http.DefaultRequestAuthenticatorFactory
import com.okta.commons.http.DefaultResponse
import com.okta.commons.http.ClientCredentials
import com.okta.commons.http.DefaultClientCredentialsResolver
import com.okta.commons.http.HttpClientConfiguration
import com.okta.commons.http.HttpHeaders
import com.okta.commons.http.HttpMethod
import com.okta.commons.http.Proxy
import com.okta.commons.http.QueryString
import com.okta.commons.http.Request
import com.okta.commons.http.RequestExecutor
import com.okta.commons.http.Response
import com.okta.commons.http.RestException
import com.okta.commons.http.AuthenticationScheme
import com.okta.commons.http.RequestAuthenticator
import com.okta.commons.http.RequestAuthenticatorFactory
import org.hamcrest.MatcherAssert
import org.testng.Assert
import org.testng.annotations.Test

import java.text.SimpleDateFormat
import java.time.Duration
import java.util.concurrent.CompletableFuture

import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.*
import static org.mockito.Mockito.*

class RetryRequestExecutorTest {

    @Test
    void testClientConfigurationConstructor() {

        def clientCredentials = mock(ClientCredentials)

        def clientConfig = new HttpClientConfiguration()
        clientConfig.setClientCredentialsResolver(new DefaultClientCredentialsResolver(clientCredentials))
        clientConfig.setProxy(new Proxy("example.com", 3333, "proxy-username", "proxy-password"))
        clientConfig.setConnectionTimeout(Duration.ofSeconds(1111L))
        clientConfig.setRetryMaxElapsed(2)
        clientConfig.setRetryMaxAttempts(3)

        def delegate = mock(RequestExecutor)

        def requestExecutor = new RetryRequestExecutor(clientConfig, delegate)

        assertThat requestExecutor.maxElapsedMillis, is(2000)
        assertThat requestExecutor.maxRetries, is(3)
    }

    @Test
    void testExecuteRequest() {

        def content = "my-content"
        def request = mockRequest()
        def httpResponse = stubResponse(content)

        def requestExecutor = createRequestExecutor()
        def future = new CompletableFuture<>()
        future.complete(httpResponse)
        when(requestExecutor.delegate.executeRequestAsync(request)).thenReturn(future)

        def response = requestExecutor.executeRequestAsync(request).get()
        def responseBody = response.body.text

        MatcherAssert.assertThat responseBody, is(content)
        assertThat response.httpStatus, is(200)
        assertThat response.isClientError(), is(false)
        assertThat response.isError(), is(false)
        assertThat response.isServerError(), is(false)
    }

    @Test
    void testExecuteRequestWith429() {

        def content1 = "fake-429-content"
        def content2 = "some-content"
        def request = mockRequest()
        def dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz")
        long currentTime = System.currentTimeMillis()
        long resetTime = ((currentTime / 1000L) + 5L) as Long // current time plus 5 seconds
        def dateHeaderValue1 = dateFormat.format(new Date(currentTime))
        def dateHeaderValue2 = dateFormat.format(new Date(currentTime + 10 * 1000))

        HttpHeaders headers1 = new HttpHeaders()
        headers1.add("X-Rate-Limit-Reset", resetTime.toString())
        headers1.add("Date", dateHeaderValue1)
        headers1.add("X-Okta-Request-Id", "a-request-id")

        HttpHeaders headers2 = new HttpHeaders()
        headers2.add("Date", dateHeaderValue2)
        headers2.add("X-Okta-Request-Id", "another-request-id")

        def httpResponse1 = stubResponse(content1, 429, headers1)
        def httpResponse2 = stubResponse(content2, 200, headers2)
        def requestAuthenticator = mock(RequestAuthenticator)

        def requestExecutor = createRequestExecutor(requestAuthenticator)
        def future1 = new CompletableFuture()
        future1.complete(httpResponse1)
        def future2 = new CompletableFuture()
        future2.complete(httpResponse2)
        when(requestExecutor.delegate.executeRequestAsync(request)).thenReturn(future1)
                                                              .thenReturn(future2)

        Response response = null
        def totalTime = time { response = requestExecutor.executeRequestAsync(request).get() }

        def responseBody = response.body.text

        MatcherAssert.assertThat responseBody, is(content2)
        assertThat response.httpStatus, is(200)
        assertThat response.isClientError(), is(false)
        assertThat response.isError(), is(false)
        assertThat response.isServerError(), is(false)

        def headers = request.getHeaders()

        assertThat headers.get("X-Okta-Retry-For"), is(['a-request-id'])
        assertThat headers.get("X-Okta-Retry-Count"), is(["2"])

        // plus 500ms for slow CPUs
        assertThat totalTime as Integer, is(both(
                                                greaterThanOrEqualTo(6000))
                                            .and(
                                                lessThan(6500)))
    }

    @Test
    void test429DelayTooLong() {

        def content = "error-content"
        def request = mockRequest()
        def requestAuthenticator = mock(RequestAuthenticator)

        def dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz")
        long currentTime = System.currentTimeMillis()
        long resetTime = ((currentTime / 1000L) + 20L) as Long // current time plus 20 seconds
        def dateHeaderValue = dateFormat.format(new Date(currentTime))
        HttpHeaders headers = new HttpHeaders()
        headers.add("X-Rate-Limit-Reset", resetTime.toString())
        headers.add("Date", dateHeaderValue)
        headers.add("X-Okta-Request-Id", "a-request-id")
        def httpResponse = stubResponse(content, 429, headers)

        def requestExecutor = createRequestExecutor(requestAuthenticator)
        def future = new CompletableFuture()
        future.complete(httpResponse)
        when(requestExecutor.delegate.executeRequestAsync(request)).thenReturn(future)

        def totalTime = time {
            def response = requestExecutor.executeRequestAsync(request).get()
            assertThat response.httpStatus, is(429)
        }

        // should be almost instant, but we are just making sure we didn't sleep for 20 sec
        assertThat totalTime, lessThan(1000L)
    }

        @Test
    void test429RetryHeadersWithDuplicateXRateLimitRest() {

        long currentTime = System.currentTimeMillis()
        long resetTime1 = ((currentTime / 1000L) + 10L) as Long // current time plus 10 seconds
        long resetTime2 = ((currentTime / 1000L) + 20L) as Long // current time plus 20 seconds

        HttpHeaders headers = new HttpHeaders()
        headers.add("X-Rate-Limit-Reset", resetTime1.toString())
        headers.add("X-Rate-Limit-Reset", resetTime2.toString())

        def httpResponse = stubResponse("content", 429, headers)

        def requestExecutor = createRequestExecutor()
        assertThat requestExecutor.get429DelayMillis(httpResponse), is(-1L)

    }

    @Test
    void test429RetryHeadersMissing() {

        HttpHeaders headers = new HttpHeaders()
        def httpResponse = stubResponse("content", 429, headers)
        def requestExecutor = createRequestExecutor()
        assertThat requestExecutor.get429DelayMillis(httpResponse), is(-1L)
    }

    @Test
    void test429RetryHeaders() {

        long currentTime = System.currentTimeMillis()
        long resetTime1 = ((currentTime / 1000L) + 10L) as Long // current time plus 10 seconds

        HttpHeaders headers = new HttpHeaders()
        headers.add("X-Rate-Limit-Reset", resetTime1.toString())
        headers.setDate(currentTime)

        def httpResponse = stubResponse("content", 429, headers)
        def requestExecutor = createRequestExecutor()

        // 10 seconds 500ms for slow CPU
        assertThat requestExecutor.get429DelayMillis(httpResponse), both(greaterThanOrEqualTo(11000L)).and(lessThan(11500L))
    }

    @Test
    void shouldRetryTest() {

        def response = mock(Response)
        when(response.getHttpStatus()).thenReturn(429)

        def requestExecutor = createRequestExecutor()
        assertThat requestExecutor.shouldRetry(response, 1, 10000L), is(true)
        assertThat requestExecutor.shouldRetry(response, 1, 20000L), is(false) // max elapsed expired
        assertThat requestExecutor.shouldRetry(response, 5, 10000L), is(false) // max retry count

        // both disabled
        requestExecutor.maxRetries = 0
        requestExecutor.maxElapsedMillis = 0
        assertThat requestExecutor.shouldRetry(response, 1, 10000L), is(false)

        // maxElapsed enabled
        requestExecutor.maxRetries = 0
        requestExecutor.maxElapsedMillis = 15000L
        assertThat requestExecutor.shouldRetry(response, 1, 10000L), is(true)

        // maxRetries enabled
        requestExecutor.maxRetries = 4
        requestExecutor.maxElapsedMillis = 0
        assertThat requestExecutor.shouldRetry(response, 1, 10000L), is(true)

        requestExecutor.maxRetries = 2
        requestExecutor.maxElapsedMillis = 30L
        assertThat requestExecutor.shouldRetry(1, 31L), is(false)
    }

    @Test
    void pauseBeforeRetryNegativeDelay() {

        def requestExecutor = createRequestExecutor()
        requestExecutor.maxElapsedMillis = 30
        def httpResponse = stubResponse("mock error", 400)
        expect RestException, {requestExecutor.pauseBeforeRetry(1, httpResponse, 31L)}
    }

    private static long time(Closure closure) {
        def startTime = System.currentTimeMillis()
        closure.call()
        return (System.currentTimeMillis() - startTime)
    }

    static <T extends Throwable> T expect(Class<T> catchMe, Closure closure) {
        try {
            closure.call()
            Assert.fail("Expected ${catchMe.getName()} to be thrown.")
        } catch(e) {
            if (!e.class.isAssignableFrom(catchMe)) {
                throw e
            }
            return e
        }
    }

    private RetryRequestExecutor createRequestExecutor(RequestAuthenticator requestAuthenticator = mock(RequestAuthenticator), int maxElapsed = 15, int maxAttempts = 4, RequestExecutor delegate = mock(RequestExecutor)) {
        return new RetryRequestExecutor(createClientConfiguration(requestAuthenticator, maxElapsed, maxAttempts), delegate)
    }

    private HttpClientConfiguration createClientConfiguration(RequestAuthenticator requestAuthenticator = mock(RequestAuthenticator), int maxElapsed = 15, int maxAttempts = 4) {

        def clientCredentials = mock(ClientCredentials)
        def clientConfig = mock(HttpClientConfiguration)
        def requestAuthFactory = mock(RequestAuthenticatorFactory)

        when(clientConfig.getClientCredentialsResolver()).thenReturn(new DefaultClientCredentialsResolver(clientCredentials))
        when(clientConfig.getConnectionTimeout()).thenReturn(Duration.ofSeconds(1111L))
        when(clientConfig.getRetryMaxElapsed()).thenReturn(maxElapsed)
        when(clientConfig.getRetryMaxAttempts()).thenReturn(maxAttempts)

        when(requestAuthFactory.create(AuthenticationScheme.SSWS, clientCredentials)).thenReturn(requestAuthenticator)

        return clientConfig
    }

    private Request mockRequest(String uri = "https://example.com/a-resource",
                                HttpMethod method = HttpMethod.GET,
                                HttpHeaders headers = new HttpHeaders(),
                                QueryString queryString = new QueryString()) {

        def request = mock(Request)

        when(request.getQueryString()).thenReturn(queryString)
        when(request.getHeaders()).thenReturn(headers)
        when(request.getResourceUrl()).thenReturn(new URI(uri))
        when(request.getMethod()).thenReturn(method)

        return request
    }

    private Response stubResponse(String content, int statusCode = 200, HttpHeaders headers = null) {

        byte[] bytes = content.bytes
        def response =  new DefaultResponse(statusCode, null, new ByteArrayInputStream(bytes), bytes.size())
        if (headers != null) {
            response.setHeaders(headers)
        }
        return response
    }
}