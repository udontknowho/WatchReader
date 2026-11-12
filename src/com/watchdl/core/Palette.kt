package com.watchdl.core

/**
 * 主题配色表。
 *
 * 原有六套取值取自 legado-novel 的 `ui/theme/colorScheme` 目录（GPL-3.0）。
 * 新增六套以 `ui/theme-icon-six.html` 的五个预览色为基准，补齐容器与语义色。
 *
 * 放 core 而不是 ui/theme：这里全是 Int，不碰 Android API，
 * 纯 JVM 自检才能验对比度与层级（见 `test/PaletteTest.kt`）。
 * 装配到 `Md3` 的是 `ui/theme/Md3.applyPalette`。
 *
 * 全局与阅读共用日夜色角色。旧配色只用于迁移深浅模式。
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
    /** 底色。surface1..4 深色逐级变亮、浅色逐级变暗，用于卡片、输入框、导航。 */
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

        /** 日间为默认模式，夜间沿用低亮度中性色。 */
        const val DEFAULT_ID = "day"

        private val legacy: List<Palette> = listOf(
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
                secondaryContainer = 0xFF707070.toInt(), onSecondaryContainer = 0xFFFFFFFF.toInt(),
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
            Palette(
                id = "duskblue", name = "暮蓝书签",
                primary = 0xFFBBD8FF.toInt(), onPrimary = 0xFF16324F.toInt(),
                primaryContainer = 0xFF294A6C.toInt(), onPrimaryContainer = 0xFFDCEAFF.toInt(),
                secondaryContainer = 0xFF32445C.toInt(), onSecondaryContainer = 0xFFDCE7F7.toInt(),
                surface = 0xFF101B2B.toInt(), surface1 = 0xFF17263A.toInt(), surface2 = 0xFF1E3046.toInt(),
                surface3 = 0xFF293D54.toInt(), surface4 = 0xFF344A62.toInt(),
                onSurface = 0xFFEDF3FF.toInt(), onSurfaceVariant = 0xFFAABDD5.toInt(),
                outline = 0xFF889EB9.toInt(), outlineVariant = 0xFF435A73.toInt(),
                error = 0xFFFFB4AB.toInt(),
            ),
            Palette(
                id = "warmpaper", name = "暖纸灰黑",
                primary = 0xFF586650.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
                primaryContainer = 0xFFF5D0C4.toInt(), onPrimaryContainer = 0xFF54251C.toInt(),
                secondaryContainer = 0xFFDFCFB4.toInt(), onSecondaryContainer = 0xFF403326.toInt(),
                surface = 0xFFF2EFE7.toInt(), surface1 = 0xFFECE9E0.toInt(), surface2 = 0xFFE5E1D6.toInt(),
                surface3 = 0xFFDDD8CC.toInt(), surface4 = 0xFFD3CDBF.toInt(),
                onSurface = 0xFF34342F.toInt(), onSurfaceVariant = 0xFF615F56.toInt(),
                outline = 0xFF7D6957.toInt(), outlineVariant = 0xFFB7A68C.toInt(),
                error = 0xFFB3261E.toInt(),
            ),
            Palette(
                id = "amberblack", name = "曜黑琥珀",
                primary = 0xFFEFBD76.toInt(), onPrimary = 0xFF402A0D.toInt(),
                primaryContainer = 0xFF604319.toInt(), onPrimaryContainer = 0xFFFFDDB0.toInt(),
                secondaryContainer = 0xFF493D30.toInt(), onSecondaryContainer = 0xFFF0DECB.toInt(),
                surface = 0xFF0C0C0D.toInt(), surface1 = 0xFF181717.toInt(), surface2 = 0xFF242222.toInt(),
                surface3 = 0xFF302C29.toInt(), surface4 = 0xFF3D3833.toInt(),
                onSurface = 0xFFF2E8DB.toInt(), onSurfaceVariant = 0xFFB9AAA0.toInt(),
                outline = 0xFFA18E7D.toInt(), outlineVariant = 0xFF54483D.toInt(),
                error = 0xFFFFB4AB.toInt(),
            ),
            Palette(
                id = "violetmist", name = "紫雾星页",
                primary = 0xFFD4B9FA.toInt(), onPrimary = 0xFF3C245A.toInt(),
                primaryContainer = 0xFF563D71.toInt(), onPrimaryContainer = 0xFFF0DDFF.toInt(),
                secondaryContainer = 0xFF493B58.toInt(), onSecondaryContainer = 0xFFEBDDF5.toInt(),
                surface = 0xFF1C1629.toInt(), surface1 = 0xFF261E34.toInt(), surface2 = 0xFF30253F.toInt(),
                surface3 = 0xFF3C314C.toInt(), surface4 = 0xFF493D5B.toInt(),
                onSurface = 0xFFF3EAFA.toInt(), onSurfaceVariant = 0xFFBEB0CE.toInt(),
                outline = 0xFFA18DB5.toInt(), outlineVariant = 0xFF5B486E.toInt(),
                error = 0xFFFFB4AB.toInt(),
            ),
            Palette(
                id = "seasalt", name = "海盐晨光",
                primary = 0xFF285F69.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
                primaryContainer = 0xFFB6DDE2.toInt(), onPrimaryContainer = 0xFF153F47.toInt(),
                secondaryContainer = 0xFFC8DDE0.toInt(), onSecondaryContainer = 0xFF293F44.toInt(),
                surface = 0xFFE9F1F1.toInt(), surface1 = 0xFFDFEBEB.toInt(), surface2 = 0xFFD4E3E4.toInt(),
                surface3 = 0xFFC8DADD.toInt(), surface4 = 0xFFBBD0D4.toInt(),
                onSurface = 0xFF20383D.toInt(), onSurfaceVariant = 0xFF455F65.toInt(),
                outline = 0xFF5D787E.toInt(), outlineVariant = 0xFF99B5BA.toInt(),
                error = 0xFFB3261E.toInt(),
            ),
            Palette(
                id = "terracotta", name = "赤陶夜读",
                primary = 0xFFEFB49B.toInt(), onPrimary = 0xFF48291E.toInt(),
                primaryContainer = 0xFF6D4232.toInt(), onPrimaryContainer = 0xFFFFDBCA.toInt(),
                secondaryContainer = 0xFF573C33.toInt(), onSecondaryContainer = 0xFFFFDDCD.toInt(),
                surface = 0xFF241715.toInt(), surface1 = 0xFF30201C.toInt(), surface2 = 0xFF3C2722.toInt(),
                surface3 = 0xFF49332C.toInt(), surface4 = 0xFF584037.toInt(),
                onSurface = 0xFFFAE9DF.toInt(), onSurfaceVariant = 0xFFD0B0A3.toInt(),
                outline = 0xFFB08D7F.toInt(), outlineVariant = 0xFF715044.toInt(),
                error = 0xFFFF9FA8.toInt(),
            ),
        )

        val all: List<Palette> = listOf(
            legacy.first { it.id == "warmpaper" }.mode("day", "日间"),
            legacy.first { it.id == "elink" }.mode("night", "夜间"),
        )

        private fun Palette.mode(id: String, name: String) = Palette(
            id, name, primary, onPrimary, primaryContainer, onPrimaryContainer,
            secondaryContainer, onSecondaryContainer, surface, surface1, surface2,
            surface3, surface4, onSurface, onSurfaceVariant, outline, outlineVariant, error,
        )

        /** 旧主题保持原深浅倾向，不触碰字号、排版与阅读进度。 */
        fun find(id: String?): Palette {
            all.firstOrNull { it.id == id }?.let { return it }
            val old = legacy.firstOrNull { it.id == id }
            val night = old != null && ((old.surface ushr 16) and 255) < 128
            return all.first { it.id == if (night) "night" else DEFAULT_ID }
        }
    }
}
