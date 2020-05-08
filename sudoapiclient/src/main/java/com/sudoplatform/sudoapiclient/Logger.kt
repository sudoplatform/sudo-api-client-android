package com.sudoplatform.sudoapiclient

import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger

/**
 * A suggested implementation of a SudoLogging Logger with "sudoApiClient" as the identifier and loglevel `DEBUG`
 */
val sudoApiClientLogger: Logger = Logger("sudoApiClient", AndroidUtilsLogDriver(LogLevel.DEBUG))
