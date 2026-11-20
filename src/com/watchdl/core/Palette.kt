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
    /**
     * 这套配色是不是深色。
     *
     * 判「夜」的唯一依据。以前各处写 `id == "night"`，一旦多出第二套深色
     * （米·夜）就会全走日间分支：状态栏字色、平台主题样式、`ok` 绿全错。
     * 带默认值 false：表外构造点（legacy）不用逐条改也不会编译不过。
     */
    val dark: Boolean = false,
    // ── MD3 规范色角色补齐（默认值必与 `all` 里日/夜两套的显式值一致）──
    // 带默认值：表外构造点（含 legacy）不用改。色调叠加色，组件表面 tints 用。
    val surfaceTint: Int = primary,
    /** 容器五档。日间由亮到暗、夜间由暗到亮，层级越低越靠近底色。 */
    val surfaceContainerLowest: Int = surface,
    val surfaceContainerLow: Int = surface1,
    val surfaceContainer: Int = surface2,
    val surfaceContainerHigh: Int = surface3,
    val surfaceContainerHighest: Int = surface4,
    /** 反色组：与底色互换，用于 Snackbar、反白标签。 */
    val inverseSurface: Int = onSurface,
    val inverseOnSurface: Int = surface,
    /** 反色组里的主色。夜间取日间 primary，日间取夜间 primary。 */
    val inversePrimary: Int = primary,
) {
    companion object {

        /** 日间为默认模式，夜间沿用低亮度中性色。 */
        const val DEFAULT_ID = "day"

        // ── 两维组合：(亮度, 配色族) → 主题 id ────────────────────────
        //
        // 设置页拆成「亮度」「配色」两条段式条，可选项从四套变二乘二。
        // 组合与反查都集中在这里：几处界面各自拼字符串，早晚会有一处拼错。

        /** 配色族：默认（番茄暖纸）。 */
        const val FAMILY_DEFAULT = "default"

        /** 配色族：米（MIUIX / HyperOS 观感）。 */
        const val FAMILY_MIUIX = "miuix"

        /** 配色族：astryx（Meta 开源设计系统的中性观感：近灰底、黑主色、无彩偏）。 */
        const val FAMILY_ASTRYX = "astryx"

        /** 三个维度组合成主题 id。表内一定有六套全部对应项。 */
        fun combine(dark: Boolean, family: String): String = when (family) {
            FAMILY_MIUIX -> if (dark) "miuixnight" else "miuix"
            FAMILY_ASTRYX -> if (dark) "astryxnight" else "astryx"
            else -> if (dark) "night" else "day"
        }

        /**
         * 反查配色族，供段式条回显选中态。
         *
         * 走 [find] 而不是比字串：旧存档（含 legacy 值）也能落到正确的那一族。
         */
        fun family(id: String?): String = when (find(id).id) {
            "miuix", "miuixnight" -> FAMILY_MIUIX
            "astryx", "astryxnight" -> FAMILY_ASTRYX
            else -> FAMILY_DEFAULT
        }

        private val legacy: List<Palette> = listOf(
            Palette(
                id = "inkgreen", name = "墨绿书页",
                primary = 0xFFB6E1C8.toInt(), onPrimary = 0xFF14251C.toInt(),
                primaryContainer = 0xFF294B39.toInt(), onPrimaryContainer = 0xFFD5F2DF.toInt(),
                secondaryContainer = 0xFF303F35.toInt(), onSecondaryContainer = 0xFFD9E8DD.toInt(),
                dark = true,
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
                dark = true,
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
                dark = true,
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
                dark = true,
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
                dark = true,
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
                dark = true,
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
                dark = true,
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
                dark = true,
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
                // 暖纸是浅底，迁移方向与下面那几条深色相反，dark 不能照抄 true。
                dark = false,
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
                dark = true,
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
                dark = true,
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
                // 海盐是浅底，同暖纸。
                dark = false,
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
                dark = true,
                surface = 0xFF241715.toInt(), surface1 = 0xFF30201C.toInt(), surface2 = 0xFF3C2722.toInt(),
                surface3 = 0xFF49332C.toInt(), surface4 = 0xFF584037.toInt(),
                onSurface = 0xFFFAE9DF.toInt(), onSurfaceVariant = 0xFFD0B0A3.toInt(),
                outline = 0xFFB08D7F.toInt(), outlineVariant = 0xFF715044.toInt(),
                error = 0xFFFF9FA8.toInt(),
            ),
        )

        /**
         * 可选主题。**唯一真源**：`Md3.applyPalette` 直接采用这里的值。
         *
         * 以前这两项从 `legacy` 派生（warmpaper / elink），而 `applyPalette` 另有一套
         * 硬编码常量，两边早已漂移：设置页色点画的是派生值，屏上却是另一套。
         * 现在改成显式写出与屏上一致的调校值，改这里的数就是改屏幕上的颜色。
         * 顺序不可换：`all[0]` 是启动默认（`DEFAULT_ID` 也是 `day`）。
         * 现在四套：日 / 夜 / 米 / 米·夜，后两套与 `combine` 的两维组合一一对应。
         */
        val all: List<Palette> = listOf(
            Palette(
                id = "day", name = "日间",
                primary = 0xFF3D5942.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
                primaryContainer = 0xFFD6E3CF.toInt(), onPrimaryContainer = 0xFF1E3622.toInt(),
                secondaryContainer = 0xFFD6E3CF.toInt(), onSecondaryContainer = 0xFF1E3622.toInt(),
                surface = 0xFFF3F0E8.toInt(), surface1 = 0xFFE8E4D9.toInt(), surface2 = 0xFFE3DED2.toInt(),
                surface3 = 0xFFDED8CA.toInt(), surface4 = 0xFFD4CDBE.toInt(),
                onSurface = 0xFF282922.toInt(), onSurfaceVariant = 0xFF606156.toInt(),
                outline = 0xFF737B6C.toInt(), outlineVariant = 0xFFB9BBAE.toInt(),
                error = 0xFFB3261E.toInt(),
                dark = false,
                surfaceTint = 0xFF3D5942.toInt(),
                surfaceContainerLowest = 0xFFF7F5EE.toInt(), surfaceContainerLow = 0xFFF1EEE5.toInt(),
                surfaceContainer = 0xFFEBE8DE.toInt(), surfaceContainerHigh = 0xFFE5E1D6.toInt(),
                surfaceContainerHighest = 0xFFDFDACE.toInt(),
                inverseSurface = 0xFF2F312B.toInt(), inverseOnSurface = 0xFFF3F0E8.toInt(),
                inversePrimary = 0xFFB6CFAD.toInt(),
            ),
            Palette(
                id = "night", name = "夜间",
                primary = 0xFFB6CFAD.toInt(), onPrimary = 0xFF20351F.toInt(),
                primaryContainer = 0xFF32422F.toInt(), onPrimaryContainer = 0xFFD8EBCF.toInt(),
                secondaryContainer = 0xFF32422F.toInt(), onSecondaryContainer = 0xFFD8EBCF.toInt(),
                surface = 0xFF101113.toInt(), surface1 = 0xFF1C1E21.toInt(), surface2 = 0xFF222428.toInt(),
                surface3 = 0xFF292C30.toInt(), surface4 = 0xFF34383D.toInt(),
                onSurface = 0xFFE4E5DF.toInt(), onSurfaceVariant = 0xFFB2B7B0.toInt(),
                outline = 0xFF90988C.toInt(), outlineVariant = 0xFF454B43.toInt(),
                error = 0xFFFFB4AB.toInt(),
                dark = true,
                surfaceTint = 0xFFB6CFAD.toInt(),
                surfaceContainerLowest = 0xFF15171A.toInt(), surfaceContainerLow = 0xFF1B1D21.toInt(),
                surfaceContainer = 0xFF212328.toInt(), surfaceContainerHigh = 0xFF272A2F.toInt(),
                surfaceContainerHighest = 0xFF2E3137.toInt(),
                inverseSurface = 0xFFE4E5DF.toInt(), inverseOnSurface = 0xFF101113.toInt(),
                inversePrimary = 0xFF3D5942.toInt(),
            ),
            /*
             * MIUIX / HyperOS 观感：近白底 + 中性灰层级 + 蓝色主色，层级比日间密。
             *
             * 不改任何几何（圆角、间距仍走 Md3 的那套），只换色：
             * 底色从暖纸变成冷白，容器五档压得更紧，主色换成蓝色。
             * 浅色系（dark = false），所以与亮度相关的分支一律按日间走。
             */
            Palette(
                id = "miuix", name = "米",
                primary = 0xFF0B6BCB.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
                primaryContainer = 0xFFD6E3FF.toInt(), onPrimaryContainer = 0xFF001B3E.toInt(),
                secondaryContainer = 0xFFDCE4EE.toInt(), onSecondaryContainer = 0xFF131C26.toInt(),
                surface = 0xFFF7F7F9.toInt(), surface1 = 0xFFEFEFF2.toInt(), surface2 = 0xFFE8E8EC.toInt(),
                surface3 = 0xFFE1E1E6.toInt(), surface4 = 0xFFD9D9DF.toInt(),
                onSurface = 0xFF1B1B1F.toInt(), onSurfaceVariant = 0xFF55555E.toInt(),
                outline = 0xFF6E6E78.toInt(), outlineVariant = 0xFFC3C3CB.toInt(),
                error = 0xFFB3261E.toInt(),
                dark = false,
                surfaceTint = 0xFF0B6BCB.toInt(),
                surfaceContainerLowest = 0xFFFFFFFF.toInt(), surfaceContainerLow = 0xFFF5F5F8.toInt(),
                surfaceContainer = 0xFFEFEFF2.toInt(), surfaceContainerHigh = 0xFFE9E9EE.toInt(),
                surfaceContainerHighest = 0xFFE3E3E9.toInt(),
                inverseSurface = 0xFF2F3033.toInt(), inverseOnSurface = 0xFFF1F1F4.toInt(),
                inversePrimary = 0xFFA8C8FF.toInt(),
            ),
            /*
             * 米色的夜间版。与 `miuix` 成对：同一族两种亮度，切换亮度时观感不变。
             *
             * 底色取近黑偏蓝（#121316），主色沿用米色日间那套蓝的浅色版（#A8C8FF），
             * 反色组的主色则回指日间的 #0B6BCB —— 与 night / day 的关系一致。
             * 层级密度照 night：surface1..4 与容器五档逐级变亮，步长压得比日间紧。
             * 数值已过 `test/PaletteTest.kt` 的对比度断言（正文 14.5:1）。
             */
            Palette(
                id = "miuixnight", name = "米·夜",
                primary = 0xFFA8C8FF.toInt(), onPrimary = 0xFF00305F.toInt(),
                primaryContainer = 0xFF23406B.toInt(), onPrimaryContainer = 0xFFD6E3FF.toInt(),
                secondaryContainer = 0xFF2A3140.toInt(), onSecondaryContainer = 0xFFDCE4EE.toInt(),
                surface = 0xFF121316.toInt(), surface1 = 0xFF191B1F.toInt(), surface2 = 0xFF1F2228.toInt(),
                surface3 = 0xFF262A31.toInt(), surface4 = 0xFF2E323A.toInt(),
                onSurface = 0xFFE3E3E9.toInt(), onSurfaceVariant = 0xFFB0B4BD.toInt(),
                outline = 0xFF8A8E98.toInt(), outlineVariant = 0xFF44474F.toInt(),
                error = 0xFFFFB4AB.toInt(),
                dark = true,
                surfaceTint = 0xFFA8C8FF.toInt(),
                surfaceContainerLowest = 0xFF15171A.toInt(), surfaceContainerLow = 0xFF191B1F.toInt(),
                surfaceContainer = 0xFF1F2228.toInt(), surfaceContainerHigh = 0xFF262A31.toInt(),
                surfaceContainerHighest = 0xFF2E323A.toInt(),
                inverseSurface = 0xFFE3E3E9.toInt(), inverseOnSurface = 0xFF121316.toInt(),
                inversePrimary = 0xFF0B6BCB.toInt(),
            ),
            /*
             * astryx 观感：取自 Meta 开源设计系统（Astryx）neutral 主题的实际取值。
             *
             * 特点是无彩：主色就是黑（夜间是白），层级靠中性灰拉开，没有色相偏。
             * 层级方向照本应用的约定（surface1..4 浅色逐级变暗），不是 Astryx 自己
             * 那套「卡片比底色亮」的排法——主题要能彼此替换，层级方向得一致。
             * 对比度：正文 #000 / #f1f1f1 ≈ 17.9:1，次要文字 ≈ 7.3:1。
             */
            Palette(
                id = "astryx", name = "astryx",
                primary = 0xFF1B1B1B.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
                primaryContainer = 0xFFD4D4D4.toInt(), onPrimaryContainer = 0xFF000000.toInt(),
                secondaryContainer = 0xFFE7E7E7.toInt(), onSecondaryContainer = 0xFF1B1B1B.toInt(),
                surface = 0xFFF1F1F1.toInt(), surface1 = 0xFFEDEDED.toInt(), surface2 = 0xFFE8E8E8.toInt(),
                surface3 = 0xFFE3E3E3.toInt(), surface4 = 0xFFDDDDDD.toInt(),
                onSurface = 0xFF000000.toInt(), onSurfaceVariant = 0xFF474747.toInt(),
                outline = 0xFFBDBDBD.toInt(), outlineVariant = 0xFFE0E0E0.toInt(),
                error = 0xFFC0262C.toInt(),
                dark = false,
                surfaceTint = 0xFF1B1B1B.toInt(),
                surfaceContainerLowest = 0xFFFFFFFF.toInt(), surfaceContainerLow = 0xFFF7F7F7.toInt(),
                surfaceContainer = 0xFFF1F1F1.toInt(), surfaceContainerHigh = 0xFFEBEBEB.toInt(),
                surfaceContainerHighest = 0xFFE4E4E4.toInt(),
                inverseSurface = 0xFF1B1B1B.toInt(), inverseOnSurface = 0xFFF1F1F1.toInt(),
                inversePrimary = 0xFFF1F1F1.toInt(),
            ),
            /*
             * astryx 的夜间版。底色 #1b1b1b 是 Astryx 的 body 色，卡片 #262626 是它的 surface 色；
             * 主色翻成白，与日间黑主色对称。
             */
            Palette(
                id = "astryxnight", name = "astryx·夜",
                primary = 0xFFF1F1F1.toInt(), onPrimary = 0xFF1B1B1B.toInt(),
                primaryContainer = 0xFF474747.toInt(), onPrimaryContainer = 0xFFF1F1F1.toInt(),
                secondaryContainer = 0xFF333333.toInt(), onSecondaryContainer = 0xFFE8E8E8.toInt(),
                surface = 0xFF1B1B1B.toInt(), surface1 = 0xFF212121.toInt(), surface2 = 0xFF262626.toInt(),
                surface3 = 0xFF2E2E2E.toInt(), surface4 = 0xFF383838.toInt(),
                onSurface = 0xFFFFFFFF.toInt(), onSurfaceVariant = 0xFF9E9E9E.toInt(),
                outline = 0xFF6B6B6B.toInt(), outlineVariant = 0xFF3A3A3A.toInt(),
                error = 0xFFFFAEA7.toInt(),
                dark = true,
                surfaceTint = 0xFFF1F1F1.toInt(),
                surfaceContainerLowest = 0xFF161616.toInt(), surfaceContainerLow = 0xFF1B1B1B.toInt(),
                surfaceContainer = 0xFF212121.toInt(), surfaceContainerHigh = 0xFF272727.toInt(),
                surfaceContainerHighest = 0xFF2E2E2E.toInt(),
                inverseSurface = 0xFFF1F1F1.toInt(), inverseOnSurface = 0xFF1B1B1B.toInt(),
                inversePrimary = 0xFF1B1B1B.toInt(),
            ),
        )

        /** 旧主题保持原深浅倾向，不触碰字号、排版与阅读进度。 */
        fun find(id: String?): Palette {
            all.firstOrNull { it.id == id }?.let { return it }
            val old = legacy.firstOrNull { it.id == id }
            val night = old != null && ((old.surface ushr 16) and 255) < 128
            // 返回的是 `all` 里的元素，`dark` 自然跟着对；legacy 上那个 `dark` 只是记录。
            return all.first { it.id == if (night) "night" else DEFAULT_ID }
        }
    }
}
