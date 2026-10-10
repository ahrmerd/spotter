package dev.ahmed.spotter.engine.http

class NeedsRefreshException(val code: Int, message: String) : Exception(message)

class HttpStatusException(val code: Int, message: String) : Exception(message)

class RestartDownloadException(message: String) : Exception(message)
