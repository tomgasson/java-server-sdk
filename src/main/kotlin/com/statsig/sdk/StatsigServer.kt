package com.statsig.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Properties
import java.util.concurrent.CompletableFuture

interface StatsigServer {

    @JvmSynthetic
    suspend fun initialize()

    @JvmSynthetic
    suspend fun checkGate(user: StatsigUser, gateName: String): Boolean

    @JvmSynthetic
    suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig

    @JvmSynthetic
    suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig

    @JvmSynthetic
    suspend fun shutdown()

    fun logEvent(user: StatsigUser?, eventName: String) {
        logEvent(user, eventName, null)
    }

    fun logEvent(user: StatsigUser?, eventName: String, value: String? = null) {
        logEvent(user, eventName, value, null)
    }

    fun logEvent(user: StatsigUser?, eventName: String, value: String? = null, metadata: Map<String, String>? = null)

    fun logEvent(user: StatsigUser?, eventName: String, value: Double) {
        logEvent(user, eventName, value, null)
    }

    fun logEvent(user: StatsigUser?, eventName: String, value: Double, metadata: Map<String, String>? = null)

    fun initializeAsync(): CompletableFuture<Unit>

    fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean>

    fun getConfigAsync(user: StatsigUser, dynamicConfigName: String): CompletableFuture<DynamicConfig>

    fun getExperimentAsync(user: StatsigUser, experimentName: String): CompletableFuture<DynamicConfig>

    fun shutdownSync()

    companion object {

        @JvmStatic
        @JvmOverloads
        fun createServer(serverSecret: String, options: StatsigOptions = StatsigOptions()): StatsigServer = StatsigServerImpl(serverSecret, options)
    }
}

private const val VERSION = "0.7.1+"

private class StatsigServerImpl(
    serverSecret: String,
    private val options: StatsigOptions
): StatsigServer {

    init {
        if (serverSecret.isEmpty() || !serverSecret.startsWith("secret-")) {
            throw IllegalArgumentException(
                "Statsig Server SDKs must be initialized with a secret key"
            )
        }
    }

    private val version = try {
        val properties = Properties()
        properties.load(StatsigServerImpl::class.java.getResourceAsStream("/statsigsdk.properties"))
        properties.getProperty("version")
    } catch (e: Exception) {
        VERSION
    }

    private val statsigJob = SupervisorJob()
    private val statsigScope = CoroutineScope(statsigJob)
    private val statsigMetadata = mapOf("sdkType" to "java-server", "sdkVersion" to version)
    private val network = StatsigNetwork(serverSecret, options, statsigMetadata)
    private var configEvaluator = Evaluator()
    private var logger: StatsigLogger = StatsigLogger(statsigScope, network, statsigMetadata)
    private val pollingJob = statsigScope.launch(start = CoroutineStart.LAZY) {
        network.pollForChanges().collect {
            if (it == null || !it.hasUpdates) {
                return@collect
            }
            configEvaluator.setDownloadedConfigs(it)
        }
    }

    override suspend fun initialize() {
        if (statsigJob.isCancelled) {
            throw IllegalStateException("StatsigServer was shutdown")
        }
        val downloadedConfigs = network.downloadConfigSpecs()
        if (downloadedConfigs != null) {
            configEvaluator.setDownloadedConfigs(downloadedConfigs)
        }
        pollingJob.start()
    }

    override suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
        if (statsigJob.isCancelled) {
            throw IllegalStateException("StatsigServer was shutdown")
        }
        if (!pollingJob.isActive) {
            throw IllegalStateException("Must initialize before calling checkGate")
        }
        val normalizedUser = normalizeUser(user)
        var result: ConfigEvaluation = configEvaluator.checkGate(normalizedUser, gateName)
        if (result.fetchFromServer) {
            result = network.checkGate(normalizedUser, gateName)
        } else {
            logger.logGateExposure(
                normalizedUser,
                gateName,
                result.booleanValue,
                result.ruleID ?: ""
            )
        }
        return result.booleanValue
    }

    override suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        if (statsigJob.isCancelled) {
            throw IllegalStateException("StatsigServer was shutdown")
        }
        if (!pollingJob.isActive) {
            throw IllegalStateException("Must initialize before calling getConfig")
        }
        val normalizedUser = normalizeUser(user)
        var result: ConfigEvaluation = configEvaluator.getConfig(normalizedUser, dynamicConfigName)
        if (result.fetchFromServer) {
            result = network.getConfig(normalizedUser, dynamicConfigName)
        } else {
            logger.logConfigExposure(normalizedUser, dynamicConfigName, result.ruleID ?: "")
        }
        return DynamicConfig(
            Config(dynamicConfigName, result.jsonValue as Map<String, Any>, result.ruleID)
        )
    }

    override suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
        if (statsigJob.isCancelled) {
            throw IllegalStateException("StatsigServer was shutdown")
        }
        if (!pollingJob.isActive) {
            throw IllegalStateException("Must initialize before calling getExperiment")
        }
        return getConfig(user, experimentName)
    }

    override fun logEvent(user: StatsigUser?, eventName: String, value: String?, metadata: Map<String, String>?) {
        if (statsigJob.isCancelled) {
            throw IllegalStateException("StatsigServer was shutdown")
        }
        statsigScope.launch {
            val normalizedUser = normalizeUser(user)
            val event =
                StatsigEvent(
                    eventName = eventName,
                    eventValue = value,
                    eventMetadata = metadata,
                    user = normalizedUser,
                )
            logger.log(event)
        }
    }

    override fun logEvent(user: StatsigUser?, eventName: String, value: Double, metadata: Map<String, String>?) {
        if (statsigJob.isCancelled) {
            throw IllegalStateException("StatsigServer was shutdown")
        }
        statsigScope.launch {
            val normalizedUser = normalizeUser(user)
            val event =
                StatsigEvent(
                    eventName = eventName,
                    eventValue = value,
                    eventMetadata = metadata,
                    user = normalizedUser,
                )
            logger.log(event)
        }
    }

    override suspend fun shutdown() {
        if (statsigJob.isCancelled) {
            throw IllegalStateException("StatsigServer was shutdown")
        }
        pollingJob.cancel()
        pollingJob.join()
        logger.shutdown()
    }

    override fun initializeAsync(): CompletableFuture<Unit> {
        return statsigScope.future {
            initialize()
        }
    }

    override fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
        return statsigScope.future {
            return@future checkGate(user, gateName)
        }
    }

    override fun getConfigAsync(user: StatsigUser, dynamicConfigName: String): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getConfig(user, dynamicConfigName)
        }
    }

    override fun getExperimentAsync(user: StatsigUser, experimentName: String): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getExperiment(user, experimentName)
        }
    }

    override fun shutdownSync() {
        runBlocking {
            shutdown()
            statsigJob.cancel() // Cancels any remaining jobs
            statsigJob.join() // Awaits for jobs to complete
        }
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        val normalizedUser = user ?: StatsigUser("")
        if (options.getEnvironment() != null && user?.statsigEnvironment == null) {
            normalizedUser.statsigEnvironment = options.getEnvironment()
        }
        return normalizedUser
    }
}
