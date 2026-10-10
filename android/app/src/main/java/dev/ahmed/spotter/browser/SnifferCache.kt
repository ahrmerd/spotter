package dev.ahmed.spotter.browser

import dev.ahmed.spotter.engine.store.HeaderJson
import dev.ahmed.spotter.engine.store.SnifferCacheEntity

fun DetectedItem.toCacheEntity(tabId: Long) = SnifferCacheEntity(
    tabId = tabId,
    mediaUrl = url,
    kind = kind.name,
    type = type,
    size = size,
    headersJson = HeaderJson.encode(headers),
    pageUrl = pageUrl,
    pageTitle = pageTitle,
    suggestedName = suggestedName,
    detectedAt = detectedAt,
    fromPlay = fromPlay,
)

fun SnifferCacheEntity.toDetectedItem() = DetectedItem(
    url = mediaUrl,
    kind = runCatching { MediaKind.valueOf(kind) }.getOrDefault(MediaKind.UNKNOWN),
    type = type,
    size = size,
    headers = HeaderJson.decode(headersJson),
    pageUrl = pageUrl,
    pageTitle = pageTitle,
    suggestedName = suggestedName,
    detectedAt = detectedAt,
    fromPlay = fromPlay,
)
