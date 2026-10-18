package com.nascon.league.data

import okhttp3.OkHttpClient

/** Capture both URL and JWT before suspending. Account switches cannot change the sender. */
fun accountSyncClient(baseUrl: String, token: String, sharedClient: OkHttpClient): LeagueApi {
    val authenticated = buildHttpClient { token }.newBuilder()
        .connectionPool(sharedClient.connectionPool)
        .dispatcher(sharedClient.dispatcher)
        .build()
    return buildApi(baseUrl, authenticated)
}

/** A server alias may change, but another installation's saved actions must stay isolated. */
fun queueBelongsToServer(owner: String, serverIdentity: String): Boolean =
    owner.substringBeforeLast('|', "") == serverIdentity && owner.substringAfterLast('|').isNotBlank()
