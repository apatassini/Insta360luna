package it.persoft.lunaultra.data

import it.persoft.lunaultra.camera.CameraMode

/**
 * Combinazioni Luna Ultra pubblicate da Insta360 per cui è noto anche il valore
 * `VideoResolution` del protocollo. Risoluzione e FPS non sono due campi separati: il firmware
 * usa un unico enum, quindi ogni riga è una combinazione atomica.
 */
data class LunaVideoProfile(
    val code: Int,
    val resolution: String,
    val fps: Int,
    val aspect: String,
    val width: Int,
    val height: Int,
) {
    val label: String get() = "$resolution · $fps fps"
}

object LunaVideoProfiles {
    val all: List<LunaVideoProfile> = listOf(
        // Valori misurati sulla Luna Ultra (firmware 1.0.238/1.0.283).
        profile(154, "8K", 30, "16:9", 7680, 4320),
        profile(211, "8K", 25, "16:9", 7680, 4320),
        profile(210, "8K", 24, "16:9", 7680, 4320),

        // 4K 16:9 e cinema 2.35:1
        profile(214, "4K", 120, "16:9", 3840, 2160),
        profile(220, "4K", 100, "16:9", 3840, 2160),
        profile(23, "4K", 60, "16:9", 3840, 2160),
        profile(92, "4K", 50, "16:9", 3840, 2160),
        profile(258, "4K", 48, "16:9", 3840, 2160),
        profile(24, "4K", 30, "16:9", 3840, 2160),
        profile(48, "4K", 25, "16:9", 3840, 2160),
        profile(49, "4K", 24, "16:9", 3840, 2160),
        profile(433, "4K", 120, "2.35:1", 3840, 1632),
        profile(434, "4K", 100, "2.35:1", 3840, 1632),
        profile(435, "4K", 60, "2.35:1", 3840, 1632),
        profile(436, "4K", 50, "2.35:1", 3840, 1632),
        profile(437, "4K", 48, "2.35:1", 3840, 1632),
        profile(438, "4K", 30, "2.35:1", 3840, 1632),
        profile(439, "4K", 25, "2.35:1", 3840, 1632),
        profile(440, "4K", 24, "2.35:1", 3840, 1632),

        profile(446, "3K", 60, "1:1", 3072, 3072),
        profile(447, "3K", 50, "1:1", 3072, 3072),

        // Il nome e i codici 242…248 sono quelli Luna: i vecchi 2720×1530 sono di un'altra camera.
        profile(242, "2.7K", 120, "16:9", 2688, 1520),
        profile(243, "2.7K", 100, "16:9", 2688, 1520),
        profile(244, "2.7K", 60, "16:9", 2688, 1520),
        profile(245, "2.7K", 50, "16:9", 2688, 1520),
        profile(331, "2.7K", 48, "16:9", 2688, 1520),
        profile(246, "2.7K", 30, "16:9", 2688, 1520),
        profile(247, "2.7K", 25, "16:9", 2688, 1520),
        profile(248, "2.7K", 24, "16:9", 2688, 1520),

        profile(27, "1080p", 240, "16:9", 1920, 1080),
        profile(26, "1080p", 200, "16:9", 1920, 1080),
        profile(28, "1080p", 120, "16:9", 1920, 1080),
        profile(150, "1080p", 100, "16:9", 1920, 1080),
        profile(40, "1080p", 60, "16:9", 1920, 1080),
        profile(81, "1080p", 50, "16:9", 1920, 1080),
        profile(260, "1080p", 48, "16:9", 1920, 1080),
        profile(29, "1080p", 30, "16:9", 1920, 1080),
        profile(52, "1080p", 25, "16:9", 1920, 1080),
        profile(53, "1080p", 24, "16:9", 1920, 1080),
    )

    fun forMode(mode: CameraMode): List<LunaVideoProfile> = when (mode) {
        CameraMode.PURE_VIDEO -> all.filter {
            it.width <= 3840 && it.width != it.height && it.fps in setOf(60, 50, 48, 30, 25, 24)
        }
        CameraMode.SLOW_MOTION -> all.filter {
            it.resolution in setOf("4K", "2.7K", "1080p") && it.fps in setOf(240, 200, 120, 100)
        }
        CameraMode.TIMELAPSE -> all.filter {
            it.fps == 30 && it.aspect == "16:9" && it.resolution in setOf("4K", "2.7K", "1080p")
        }
        else -> all
    }

    fun selected(code: Int, mode: CameraMode): LunaVideoProfile =
        forMode(mode).firstOrNull { it.code == code } ?: defaultFor(mode)

    fun defaultFor(mode: CameraMode): LunaVideoProfile = when (mode) {
        CameraMode.SLOW_MOTION -> all.first { it.code == 214 }
        CameraMode.TIMELAPSE -> all.first { it.code == 24 }
        else -> all.first { it.code == 24 }
    }

    private fun profile(
        code: Int,
        resolution: String,
        fps: Int,
        aspect: String,
        width: Int,
        height: Int,
    ) = LunaVideoProfile(code, resolution, fps, aspect, width, height)
}
