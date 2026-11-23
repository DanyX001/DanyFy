package app.danyfy.shared.data

object Sha1 {
    fun hash(bytes: ByteArray): String {
        var h0 = 0x67452301
        var h1 = -0x10325477 // 0xEFCDAB89.toInt()
        var h2 = -0x67452302 // 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = -0x3C2D1E10 // 0xC3D2E1F0.toInt()

        val bitLength = bytes.size.toLong() * 8L
        val paddingLength = (((56 - (bytes.size + 1) % 64) % 64) + 64) % 64
        val totalLength = bytes.size + 1 + paddingLength + 8
        val padded = ByteArray(totalLength)
        bytes.copyInto(padded, 0, 0, bytes.size)
        padded[bytes.size] = 0x80.toByte()

        for (i in 0 until 8) {
            padded[totalLength - 1 - i] = ((bitLength ushr (i * 8)) and 0xFF).toByte()
        }

        val w = IntArray(80)
        for (chunkStart in 0 until totalLength step 64) {
            for (i in 0 until 16) {
                val offset = chunkStart + i * 4
                w[i] = ((padded[offset].toInt() and 0xFF) shl 24) or
                        ((padded[offset + 1].toInt() and 0xFF) shl 16) or
                        ((padded[offset + 2].toInt() and 0xFF) shl 8) or
                        (padded[offset + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) {
                w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4

            for (i in 0 until 80) {
                val (f, k) = when (i) {
                    in 0..19 -> Pair((b and c) or (b.inv() and d), 0x5A827999)
                    in 20..39 -> Pair(b xor c xor d, 0x6ED9EBA1)
                    in 40..59 -> Pair((b and c) or (b and d) or (c and d), -0x70E44324) // 0x8F1BBCDC.toInt()
                    else -> Pair(b xor c xor d, -0x359D3E2A) // 0xCA62C1D6.toInt()
                }
                val temp = a.rotateLeft(5) + f + e + k + w[i]
                e = d
                d = c
                c = b.rotateLeft(30)
                b = a
                a = temp
            }

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
        }

        return buildString(40) {
            for (h in intArrayOf(h0, h1, h2, h3, h4)) {
                for (byteIdx in 3 downTo 0) {
                    val b = (h ushr (byteIdx * 8)) and 0xFF
                    val hex = b.toString(16)
                    if (hex.length == 1) append('0')
                    append(hex)
                }
            }
        }
    }

    fun hashString(input: String): String = hash(input.encodeToByteArray())
}

fun sapisidFromCookies(cookieHeader: String?): String? {
    if (cookieHeader.isNullOrBlank()) return null
    val pairs = cookieHeader.split(';')
    for (p in pairs) {
        val trimmed = p.trim()
        val eq = trimmed.indexOf('=')
        if (eq > 0) {
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (name == "SAPISID" || name == "__Secure-3PAPISID") {
                if (value.isNotBlank()) return value
            }
        }
    }
    return null
}

fun sapisidHashAuth(
    sapisid: String,
    timestampSeconds: Long,
    origin: String = "https://music.youtube.com",
): String {
    val message = "$timestampSeconds $sapisid $origin"
    val digest = Sha1.hashString(message)
    return "SAPISIDHASH ${timestampSeconds}_$digest"
}
