package com.example.diabai.network

/** A plain (non-SSE) HTTP call returned a non-2xx status -- carries the code so callers can
 * classify it the same way [io.ktor.client.plugins.sse.SSEClientException] already is. */
class HttpStatusException(val statusCode: Int, message: String) : Exception(message)
