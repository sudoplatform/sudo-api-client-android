/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudoapiclient.http

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.HttpURLConnection

/**
 * Convert any SSL errors emitted by the certificate transparency verification interceptor
 * into an HTTP response that is interpreted as fatal and should not be retried by the
 * AppSync client.
 *
 */
class ConvertSslErrorsInterceptor : Interceptor {
    private val errorResponseBuilder =
        okhttp3.Response
            .Builder()
            .code(HttpURLConnection.HTTP_FORBIDDEN)
            .protocol(Protocol.HTTP_1_1)
            .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response =
        try {
            chain.proceed(chain.request())
        } catch (e: javax.net.ssl.SSLException) {
            errorResponseBuilder
                .request(chain.request())
                .message("$e")
                .build()
        }
}
