package com.unitrading.broker

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/** A broker client with the platform's default TLS and proxy behavior. */
expect fun createBrokerHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient
