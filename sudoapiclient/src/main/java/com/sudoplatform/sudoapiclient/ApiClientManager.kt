/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudoapiclient

import android.content.Context
import com.amazonaws.mobile.config.AWSConfiguration
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.appmattus.certificatetransparency.certificateTransparencyInterceptor
import com.appmattus.certificatetransparency.loglist.LogListDataSourceFactory
import com.sudoplatform.sudoconfigmanager.DefaultSudoConfigManager
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.ConvertSslErrorsInterceptor
import com.sudoplatform.sudouser.GraphQLAuthProvider
import com.sudoplatform.sudouser.SudoUserClient
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages a singleton GraphQL client instance that may be shared by multiple service clients.
 */
object ApiClientManager {

    private const val CONFIG_NAMESPACE_IDENTITY_SERVICE = "identityService"
    private const val CONFIG_NAMESPACE_API_SERVICE = "apiService"
    private const val CONFIG_NAMESPACE_CT_LOG_LIST_SERVICE = "ctLogListService"

    private const val CONFIG_REGION = "region"
    private const val CONFIG_POOL_ID = "poolId"
    private const val CONFIG_CLIENT_ID = "clientId"
    private const val CONFIG_API_URL = "apiUrl"
    private const val CONFIG_LOG_LIST_URL = "logListUrl"

    private var logger: Logger? = null
    private var client: AWSAppSyncClient? = null

    /**
     * Checksum's for each file are generated and are used to create a checksum that is used when publishing to maven central.
     * In order to retry a failed publish without needing to change any functionality, we need a way to generate a different checksum
     * for the source code.  We can change the value of this property which will generate a different checksum for publishing
     * and allow us to retry.  The value of `version` doesn't need to be kept up-to-date with the version of the code.
     */
    private val version: String = "5.1.0"

    /**
     * Sets the SudoLogging `Logger` for the shared instance
     * @param logger Logger for logging all messages within manager
     */
    fun setLogger(logger: Logger) {
        this.logger = logger
    }

    /**
     * Returns the shared instance of an `AWSAppSyncClient`
     * @param context The context used for fetching the platform config json
     * @param sudoUserClient A SudoUserClient instance which provides the auth info for the client object
     * @return AWSAppSyncClient
     */
    @Throws
    fun getClient(context: Context, sudoUserClient: SudoUserClient): AWSAppSyncClient {
        // return the existing AWSAppSyncClient if it has already been created
        this.client?.let { return it }
        val sudoConfigManager = DefaultSudoConfigManager(context, logger)
        val apiConfig = sudoConfigManager.getConfigSet(CONFIG_NAMESPACE_API_SERVICE)
        val identityServiceConfig = sudoConfigManager.getConfigSet(CONFIG_NAMESPACE_IDENTITY_SERVICE)
        val ctLogListServiceConfig = sudoConfigManager.getConfigSet(CONFIG_NAMESPACE_CT_LOG_LIST_SERVICE)

        require(identityServiceConfig != null && apiConfig != null) { "Identity or API service configuration is missing." }

        val apiUrl = apiConfig.get(CONFIG_API_URL) as String?
        val region = apiConfig.get(CONFIG_REGION) as String?
        val poolId = identityServiceConfig.get(CONFIG_POOL_ID) as String?
        val clientId = identityServiceConfig.get(CONFIG_CLIENT_ID) as String?

        require(poolId != null
                && clientId != null
                && apiUrl != null
                && region != null) { "poolId or clientId or apiUrl or region was null." }

        try {

            // The AWSAppSyncClient auth provider requires the config to be in the following format
            val awsConfig = JSONObject(
                """
                {
                    'CognitoUserPool': {
                        'Default': {
                            'PoolId': '$poolId',
                            'AppClientId': '$clientId',
                            "Region": "$region"
                        }
                    },
                    'AppSync': {
                        'Default': {
                            'ApiUrl': '$apiUrl', 'Region': '$region', 'AuthMode': 'OPENID_CONNECT'}
                    }
                } 
                """.trimIndent()
            )

            val authProvider = GraphQLAuthProvider(sudoUserClient)
            val logListUrl = ctLogListServiceConfig?.getString(CONFIG_LOG_LIST_URL)
            val appSyncClient = AWSAppSyncClient.builder()
            	.context(context)
                // Currently realtime subscription does not support passing a custom Cognito User Pool
                // authentication provider. To workaround we are using OIDC authentication provider but
                // we should change to using Cognito User Pool authentication provider when it is
                // supported.
                // .cognitoUserPoolsAuthProvider(authProvider)
                .oidcAuthProvider { authProvider.latestAuthToken }
                .subscriptionsAutoReconnect(true)
                .awsConfiguration(AWSConfiguration(awsConfig))
                .okHttpClient(buildOkHttpClient(logListUrl))
                .build()

            this.client = appSyncClient
            return appSyncClient
        } catch (e: Exception) {
            throw(e)
        }
    }

    /**
     * Construct the [OkHttpClient] configured with the certificate transparency checking interceptor.
     */
    private fun buildOkHttpClient(ctLogListUrl: String?): OkHttpClient {
        val url = ctLogListUrl ?: "https://www.gstatic.com/ct/log_list/v3/"
        this.logger?.info("Using CT log list URL: $url")
        val interceptor = certificateTransparencyInterceptor {
            setLogListService(LogListDataSourceFactory.createLogListService(url))
        }
        val okHttpClient = OkHttpClient.Builder().readTimeout(30L, TimeUnit.SECONDS).apply {
            // Convert exceptions from certificate transparency into http errors that stop the
            // exponential backoff retrying of [AWSAppSyncClient]
            addInterceptor(ConvertSslErrorsInterceptor())

            // Certificate transparency checking
            addNetworkInterceptor(interceptor)
        }
        return okHttpClient.build()
    }

    /**
     * Clears caches on the client
     */
    fun reset() {
        this.client?.clearCaches()
    }
}
