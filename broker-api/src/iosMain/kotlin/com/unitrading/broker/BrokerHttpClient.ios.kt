package com.unitrading.broker

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

actual fun createBrokerHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient { configure(this) }
