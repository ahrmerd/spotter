package dev.ahmed.spotter.engine.hls

import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object HlsCrypto {
    fun decryptingStream(input: InputStream, key: ByteArray, iv: ByteArray): InputStream {
        if (key.size != 16) throw IllegalArgumentException("AES-128 key must be 16 bytes")
        if (iv.size != 16) throw IllegalArgumentException("AES-128 IV must be 16 bytes")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return CipherInputStream(input, cipher)
    }

    fun ivFor(segment: HlsSegment): ByteArray {
        val key = segment.key
        return when {
            key?.iv != null -> key.iv
            else -> HlsPlaylist.sequenceIv(segment.sequence)
        }
    }

    fun unsupportedReason(key: HlsKey?): String? {
        if (key == null || key.method == HlsKeyMethod.NONE) return null
        return when (key.method) {
            HlsKeyMethod.AES_128 -> null
            HlsKeyMethod.SAMPLE_AES -> "SAMPLE-AES encryption is not supported"
            HlsKeyMethod.DRM -> "DRM-protected HLS is not supported"
            HlsKeyMethod.NONE -> null
        }
    }
}
