package com.watchdl.core

/**
 * 主题配色表。
 *
 * 六套取值取自 legado-novel 的 `ui/theme/colorScheme` 目录（GPL-3.0），
 * 从每套 M3 的 40 个色角色里挑出本项目实际用到的 16 个。
 *
 * 放 core 而不是 ui/theme：这里全是 Int，不碰 Android API，
 * 纯 JVM 自检才能验对比度与层级（见 `test/PaletteTest.kt`）。
 * 装配到 `Md3` 的是 `ui/theme/Md3.applyPalette`。
 *
 * shortcut: 只做深色档与 6 套。要浅色档或更多套时在 [all] 里加一条 `Palette` 即可，
 * 调用方只认 id。
 */
class Palette(
    val id: String,
    val name: String,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    /** 底色。surface1..4 逐级抬升，用于卡片、输入框、导航。 */
    val surface: Int,
    val surface1: Int,
    val surface2: Int,
    val surface3: Int,
    val surface4: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val error: Int,
) {
    companion object {

        /** 默认色。紫，与原写死的 MD3 基线色最接近。 */
        const val DEFAULT_ID = "inkgreen"

        val all: List<Palette> = listOf(
            Palette(
                id = "inkgreen", name = "墨绿书页",
                primary = 0xFFB6E1C8.toInt(), onPrimary = 0xFF14251C.toInt(),
                primaryContainer = 0xFF294B39.toInt(), onPrimaryContainer = 0xFFD5F2DF.toInt(),
                secondaryContainer = 0xFF303F35.toInt(), onSecondaryContainer = 0xFFD9E8DD.toInt(),
                surface = 0xFF131A17.toInt(), surface1 = 0xFF19231D.toInt(), surface2 = 0xFF1D2721.toInt(),
                surface3 = 0xFF202C25.toInt(), surface4 = 0xFF2D3B32.toInt(),
                onSurface = 0xFFEDF1EC.toInt(), onSurfaceVariant = 0xFFA5B8AC.toInt(),
                outline = 0xFF81998A.toInt(), outlineVariant = 0xFF3E5448.toInt(),
                error = 0xFFEFB5A6.toInt(),
            ),
            Palette(
                id = "yuuka", name = "紫",
                primary = 0xFFBFC2FF.toInt(), onPrimary = 0xFF272B60.toInt(),
                primaryContainer = 0xFF3E4278.toInt(), onPrimaryContainer = 0xFFE0E0FF.toInt(),
                secondaryContainer = 0xFF444559.toInt(), onSecondaryContainer = 0xFFE1E0F9.toInt(),
                surface = 0xFF0E0E13.toInt(), surface1 = 0xFF1B1B21.toInt(), surface2 = 0xFF1F1F25.toInt(),
                surface3 = 0xFF2A292F.toInt(), surface4 = 0xFF34343A.toInt(),
                onSurface = 0xFFE4E1E9.toInt(), onSurfaceVariant = 0xFFC7C5D0.toInt(),
                outline = 0xFF918F9A.toInt(), outlineVariant = 0xFF46464F.toInt(),
                error = 0xFFFFB4AB.toInt(),
            ),
            Palette(
                // 原方案的 surfaceContainer 是纯黑、比 surfaceContainerLowest 还暗，
                // 层级是反的。这里改用它的 high/highest 两档顶上，保证 surface1..4 单调抬升。
                id = "elink", name = "素",
                primary = 0xFFFFFFFF.toInt(), onPrimary = 0xFF000000.toInt(),
                primaryContainer = 0xFFFFFFFF.toInt(), onPrimaryContainer = 0xFF000000.toInt(),
                secondaryContainer = 0xFF808080.toInt(), onSecondaryContainer = 0xFFFFFFFF.toInt(),
                surface = 0xFF0E0E0E.toInt(), surface1 = 0xFF1C1B1B.toInt(), surface2 = 0xFF201F1F.toInt(),
                surface3 = 0xFF2A2A2A.toInt(), surface4 = 0xFF353434.toInt(),
                onSurface = 0xFFFFFFFF.toInt(), onSurfaceVariant = 0xFFC4C7C5.toInt(),
                outline = 0xFF8E918F.toInt(), outlineVariant = 0xFF444846.toInt(),
                error = 0xFFFFB4AB.toInt(),
            ),
            Palette(
                id = "mujika", name = "粉",
                primary = 0xFFFFB1C0.toInt(), onPrimary = 0xFF551D2B.toInt(),
                primaryContainer = 0xFF713341.toInt(), onPrimaryContainer = 0xFFFFD9DF.toInt(),
                secondaryContainer = 0xFF5C3F44.toInt(), onSecondaryContainer = 0xFFFFD9DF.toInt(),
                surface = 0xFF140C0D.toInt(), surface1 = 0xFF22191B.toInt(), surface2 = 0xFF261D1F.toInt(),
                surface3 = 0xFF312829.toInt(), surface4 = 0xFF3C3234.toInt(),
                onSurface = 0xFFEFDEE0.toInt(), onSurfaceVariant = 0xFFD6C2C4.toInt(),
                outline = 0xFF9F8C8F.toInt(), outlineVariant = 0xFF524345.toInt(),
                error = 0xFFFFB4AB.toInt(),
            ),
            Palette(
                id = "gr", name = "青",
                primary = 0xFFB1D18A.toInt(), onPrimary = 0xFF1F3701.toInt(),
                primaryContainer = 0xFF354E16.toInt(), onPrimaryContainer = 0xFFCDEDA3.toInt(),
                secondaryContainer = 0xFF404A33.toInt(), onSecondaryContainer = 0xFFDCE7C8.toInt(),
                surface = 0xFF0C0F09.toInt(), surface1 = 0xFF1A1C16.toInt(), surface2 = 0xFF1E201A.toInt(),
                surface3 = 0xFF282B24.toInt(), surface4 = 0xFF33362E.toInt(),
                onSurface = 0xFFE2E3D8.toInt(), onSurfaceVariant = 0xFFC5C8BA.toInt(),
                outline = 0xFF8F9285.toInt(), outlineVariant = 0xFF44483D.toInt(),
                error = 0xFFFFB4AB.toInt(),
            ),
            Palette(
                id = "phoebe", name = "金",
                primary = 0xFFD9C76F.toInt(), onPrimary = 0xFF383000.toInt(),
                primaryContainer = 0xFF524700.toInt(), onPrimaryContainer = 0xFFF6E388.toInt(),
                secondaryContainer = 0xFF2F4578.toInt(), onSecondaryContainer = 0xFFD9E2FF.toInt(),
                surface = 0xFF100E07.toInt(), surface1 = 0xFF1E1C13.toInt(), surface2 = 0xFF222017.toInt(),
                surface3 = 0xFF2C2A21.toInt(), surface4 = 0xFF37352B.toInt(),
                onSurface = 0xFFE8E2D4.toInt(), onSurfaceVariant = 0xFFCCC6B4.toInt(),
                outline = 0xFF969180.toInt(), outlineVariant = 0xFF4A4739.toInt(),
                error = 0xFFFFB4AB.toInt(),
            ),
            Palette(
                id = "sora", name = "蓝",
                primary = 0xFFA5C9FE.toInt(), onPrimary = 0xFF00315D.toInt(),
                primaryContainer = 0xFF214876.toInt(), onPrimaryContainer = 0xFFD4E3FF.toInt(),
                secondaryContainer = 0xFF3D4758.toInt(), onSecondaryContainer = 0xFFD8E3F8.toInt(),
                surface = 0xFF0C0E13.toInt(), surface1 = 0xFF191C20.toInt(), surface2 = 0xFF1D2024.toInt(),
                surface3 = 0xFF282A2F.toInt(), surface4 = 0xFF32353A.toInt(),
                onSurface = 0xFFE1E2E9.toInt(), onSurfaceVariant = 0xFFC3C6CF.toInt(),
                outline = 0xFF8D9199.toInt(), outlineVariant = 0xFF43474E.toInt(),
                error = 0xFFFFB4AB.toInt(),
            ),
        )

        /** id 认不出来时回默认色，不抛。老存档的 id 作废不该让应用起不来。 */
        fun find(id: String?): Palette = all.firstOrNull { it.id == id } ?: all[0]
    }
}
