package com.swiftproxy.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class SubscriptionUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val address = SecureStore.readSubscriptionUrl(applicationContext) ?: return Result.success()
        return runCatching {
            val body = fetchHttps(address)
            val refreshed = NodeParser.parseSubscription(body)
            check(refreshed.isNotEmpty()) { "订阅更新没有解析到节点" }

            val newUris = refreshed.map { it.rawUri }.toSet()
            val oldSubscriptionUris = SecureStore.readSubscriptionNodeUris(applicationContext)
            val allUris = SecureStore.readNodeUris(applicationContext).toMutableSet()
            allUris.removeAll(oldSubscriptionUris)
            allUris.addAll(newUris)
            SecureStore.saveSubscriptionNodeUris(applicationContext, newUris)
            SecureStore.saveNodeUris(applicationContext, allUris)
            applicationContext.getSharedPreferences("swiftproxy", Context.MODE_PRIVATE).edit()
                .putLong("subscription_last_update", System.currentTimeMillis())
                .apply()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    private fun fetchHttps(initialAddress: String): String {
        var current = URL(initialAddress)
        repeat(5) {
            require(current.protocol.equals("https", true)) { "订阅重定向必须保持 HTTPS" }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "SwiftProxy-Android")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: error("订阅重定向缺少目标地址")
                    current = URL(current, location)
                    return@repeat
                }
                check(code in 200..299) { "订阅服务器返回 HTTP $code" }
                return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        error("订阅重定向次数过多")
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "swiftproxy_subscription_refresh"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
