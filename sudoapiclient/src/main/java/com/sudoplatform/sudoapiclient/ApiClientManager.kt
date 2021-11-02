package com.sudoplatform.sudoapiclient

import android.content.Context
import com.amazonaws.mobile.config.AWSConfiguration
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.appmattus.certificatetransparency.certificateTransparencyInterceptor
import com.sudoplatform.sudoconfigmanager.DefaultSudoConfigManager
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.ConvertSslErrorsInterceptor
import com.sudoplatform.sudouser.GraphQLAuthProvider
import com.sudoplatform.sudouser.SudoUserClient
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Manages a singleton GraphQL client instance that may be shared by multiple service clients.
 */
object ApiClientManager {
    private var logger: Logger? = null
    private var client: AWSAppSyncClient? = null

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
        val apiConfig = sudoConfigManager.getConfigSet("apiService")
        val identityServiceConfig = sudoConfigManager.getConfigSet("identityService")

        require(identityServiceConfig != null && apiConfig != null) { "Identity or API service configuration is missing." }

        val apiUrl = apiConfig.get("apiUrl") as String?
        val region = apiConfig.get("region") as String?
        val poolId = identityServiceConfig.get("poolId") as String?
        val clientId = identityServiceConfig.get("clientId") as String?

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
                .okHttpClient(buildOkHttpClient())
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
    private fun buildOkHttpClient(): OkHttpClient {
        val interceptor = certificateTransparencyInterceptor {}
        val okHttpClient = OkHttpClient.Builder().apply {
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