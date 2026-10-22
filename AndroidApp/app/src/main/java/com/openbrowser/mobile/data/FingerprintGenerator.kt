package com.openbrowser.mobile.data

/**
 * 随机指纹生成器：从真实机型的参数池里组合出一套自洽的硬件指纹。
 *
 * 只覆盖设备层面的指纹（UA / 平台 / 屏幕 / DPR / 触点 / WebGL / CPU / 内存），
 * **不动语言、时区与代理**——这三项要和出口 IP 对齐，一起随机化反而更容易被识别。
 *
 * 机型、屏幕、GPU、Chrome / iOS 版本都是独立随机组合，因此同一个机型也可能出现
 * 不同的分辨率或显卡，整体可选组合上千种。同时写入 [Profile.deviceModel]，
 * 让环境列表能直接显示机型名。
 */
object FingerprintGenerator {

    private data class Screen(val width: Int, val height: Int, val dpr: Double)

    private data class Gpu(val vendor: String, val renderer: String, val short: String)

    private data class Variant(
        val userAgent: String,
        val platform: String,
        val screen: Screen,
        val touchPoints: Int,
        val mobile: Boolean,
        val gpu: Gpu,
        val label: String,
    )

    private data class AndroidModel(
        val name: String,
        val model: String,
        val versions: List<Int>,
        val screens: List<Screen>,
        val gpus: List<Gpu>,
    )

    private data class IosModel(
        val family: String,
        val versions: List<String>,
        val screens: List<Screen>,
    )

    // ---------- 屏幕 ----------

    private val s360x780 = Screen(360, 780, 3.0)
    private val s393x852 = Screen(393, 852, 2.75)
    private val s393x873 = Screen(393, 873, 2.75)
    private val s412x915 = Screen(412, 915, 2.625)
    private val s412x919 = Screen(412, 919, 2.625)
    private val s412x920 = Screen(412, 920, 2.625)
    private val s384x823 = Screen(384, 823, 3.75)
    private val s448x998 = Screen(448, 998, 2.625)
    private val s411x914 = Screen(411, 914, 2.625)
    private val s420x907 = Screen(420, 907, 3.0)
    private val s400x913 = Screen(400, 913, 3.0)

    // ---------- GPU ----------

    private val adreno740 = Gpu("Qualcomm", "Adreno (TM) 740", "Adreno 740")
    private val adreno750 = Gpu("Qualcomm", "Adreno (TM) 750", "Adreno 750")
    private val adreno730 = Gpu("Qualcomm", "Adreno (TM) 730", "Adreno 730")
    private val adreno660 = Gpu("Qualcomm", "Adreno (TM) 660", "Adreno 660")
    private val adreno650 = Gpu("Qualcomm", "Adreno (TM) 650", "Adreno 650")
    private val maliG715 = Gpu("ARM", "Mali-G715", "Mali-G715")
    private val maliG710 = Gpu("ARM", "Mali-G710", "Mali-G710")
    private val maliG78 = Gpu("ARM", "Mali-G78", "Mali-G78")
    private val maliG68 = Gpu("ARM", "Mali-G68", "Mali-G68")
    private val appleGpu = Gpu("Apple Inc.", "Apple GPU", "Apple GPU")

    // ---------- 机型 ----------

    private val androidModels: List<AndroidModel> = listOf(
        AndroidModel("Pixel 8", "Pixel 8", listOf(14), listOf(s412x915), listOf(adreno740)),
        AndroidModel("Pixel 8 Pro", "Pixel 8 Pro", listOf(14), listOf(s448x998), listOf(adreno740)),
        AndroidModel("Pixel 7", "Pixel 7", listOf(13, 14), listOf(s412x915), listOf(adreno730)),
        AndroidModel("Pixel 7a", "Pixel 7a", listOf(13, 14), listOf(s412x915), listOf(maliG710)),
        AndroidModel("Pixel 6", "Pixel 6", listOf(12, 13), listOf(s411x914), listOf(maliG78)),
        AndroidModel("Pixel 6a", "Pixel 6a", listOf(12, 13), listOf(s412x915), listOf(maliG78)),
        AndroidModel("Galaxy S24", "SM-S9210", listOf(14), listOf(s360x780), listOf(adreno750)),
        AndroidModel("Galaxy S23", "SM-S9110", listOf(13, 14), listOf(s360x780), listOf(adreno740)),
        AndroidModel("Galaxy S23 Ultra", "SM-S9180", listOf(13, 14), listOf(s384x823), listOf(adreno740)),
        AndroidModel("Galaxy A54", "SM-A5460", listOf(13, 14), listOf(s412x915, s360x780), listOf(maliG68)),
        AndroidModel("Galaxy A34", "SM-A3460", listOf(13, 14), listOf(s412x915), listOf(maliG68)),
        AndroidModel("小米 14", "23127PN0CC", listOf(14), listOf(s393x852), listOf(adreno750)),
        AndroidModel("Redmi K70", "23113RKC6C", listOf(14), listOf(s393x873), listOf(adreno740)),
        AndroidModel("小米 13", "2211133C", listOf(13, 14), listOf(s393x852, s393x873), listOf(adreno740)),
        AndroidModel("Redmi K60", "23013RK75C", listOf(13, 14), listOf(s393x873), listOf(adreno730)),
        AndroidModel("小米 11", "M2102K1AC", listOf(12, 13), listOf(s393x873), listOf(adreno660)),
        AndroidModel("Redmi K50 Ultra", "22081212C", listOf(13), listOf(s393x873), listOf(adreno730)),
        AndroidModel("Redmi Note 11", "2201117TG", listOf(12, 13), listOf(s393x873), listOf(adreno650)),
        AndroidModel("Redmi K40", "M2012K11AC", listOf(12, 13), listOf(s393x873), listOf(adreno660)),
        AndroidModel("华为 Mate 60 Pro", "ALN-AL00", listOf(12), listOf(s420x907), listOf(maliG715)),
        AndroidModel("华为 Mate 40 Pro", "NOH-AN00", listOf(10, 12), listOf(s400x913), listOf(maliG78)),
        AndroidModel("荣耀 Magic6", "BVL-AN00", listOf(14), listOf(s412x919), listOf(adreno750)),
        AndroidModel("荣耀 Magic5", "PGT-AN10", listOf(13, 14), listOf(s412x919), listOf(adreno740)),
        AndroidModel("OPPO Find X7", "PHY110", listOf(14), listOf(s412x919), listOf(maliG715)),
        AndroidModel("一加 12", "PJD110", listOf(14), listOf(s412x919), listOf(adreno750)),
        AndroidModel("一加 Ace 2 Pro", "PHK110", listOf(13, 14), listOf(s412x919), listOf(adreno740)),
        AndroidModel("vivo X100", "V2309A", listOf(14), listOf(s412x920), listOf(maliG715)),
        AndroidModel("vivo X90", "V2227A", listOf(13, 14), listOf(s412x920), listOf(adreno740)),
        AndroidModel("iQOO 11", "V2241A", listOf(13, 14), listOf(s412x920), listOf(adreno740)),
        AndroidModel("realme GT5", "RMX3820", listOf(13, 14), listOf(s412x919), listOf(adreno740)),
        AndroidModel("Nothing Phone (2)", "A065", listOf(13, 14), listOf(s412x919), listOf(adreno730)),
        AndroidModel("moto Edge 40", "XT2301-5", listOf(13, 14), listOf(s412x919), listOf(maliG715)),
        AndroidModel("魅族 21", "M461Q", listOf(14), listOf(s393x873), listOf(adreno750)),
    )

    private val iosModels: List<IosModel> = listOf(
        IosModel("iPhone", listOf("18_0", "18_1"), listOf(Screen(440, 956, 3.0))),
        IosModel("iPhone", listOf("18_0", "18_1"), listOf(Screen(402, 874, 3.0))),
        IosModel("iPhone", listOf("18_0"), listOf(Screen(430, 932, 3.0))),
        IosModel("iPhone", listOf("18_0"), listOf(Screen(393, 852, 3.0))),
        IosModel("iPhone", listOf("17_5", "18_0"), listOf(Screen(430, 932, 3.0), Screen(393, 852, 3.0))),
        IosModel("iPhone", listOf("17_5", "18_0"), listOf(Screen(390, 844, 3.0))),
        IosModel("iPhone", listOf("16_6", "17_5"), listOf(Screen(393, 852, 3.0), Screen(390, 844, 3.0))),
        IosModel("iPhone", listOf("16_6", "17_5"), listOf(Screen(375, 812, 3.0))),
        IosModel("iPhone", listOf("16_6", "17_5"), listOf(Screen(375, 667, 2.0))),
        IosModel("iPad", listOf("17_5", "18_0"), listOf(Screen(834, 1210, 2.0))),
        IosModel("iPad", listOf("17_5", "18_0"), listOf(Screen(820, 1180, 2.0))),
        IosModel("iPad", listOf("16_6", "17_5"), listOf(Screen(744, 1133, 2.0))),
    )

    private val iosLabels: Map<Screen, String> = mapOf(
        Screen(440, 956, 3.0) to "iPhone 16 Pro Max",
        Screen(402, 874, 3.0) to "iPhone 16 Pro",
        Screen(430, 932, 3.0) to "iPhone 15 Pro Max",
        Screen(393, 852, 3.0) to "iPhone 15 Pro",
        Screen(390, 844, 3.0) to "iPhone 15",
        Screen(375, 812, 3.0) to "iPhone 13 mini",
        Screen(375, 667, 2.0) to "iPhone SE",
        Screen(834, 1210, 2.0) to "iPad Pro 11",
        Screen(820, 1180, 2.0) to "iPad Air",
        Screen(744, 1133, 2.0) to "iPad mini",
    )

    private val windowsScreens = listOf(
        Screen(1920, 1080, 1.0),
        Screen(1536, 864, 1.25),
        Screen(1366, 768, 1.0),
        Screen(2560, 1440, 1.0),
        Screen(1600, 900, 1.0),
        Screen(2560, 1600, 1.5),
        Screen(2880, 1800, 2.0),
    )

    private val windowsGpus = listOf(
        Gpu("Google Inc. (Intel)", "ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)", "UHD 630"),
        Gpu("Google Inc. (Intel)", "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)", "Iris Xe"),
        Gpu("Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce GTX 1650 Direct3D11 vs_5_0 ps_5_0, D3D11)", "GTX 1650"),
        Gpu("Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)", "RTX 3060"),
        Gpu("Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 4060 Direct3D11 vs_5_0 ps_5_0, D3D11)", "RTX 4060"),
        Gpu("Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 6600 Direct3D11 vs_5_0 ps_5_0, D3D11)", "RX 6600"),
        Gpu("Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 7800 XT Direct3D11 vs_5_0 ps_5_0, D3D11)", "RX 7800 XT"),
    )

    private val macScreens = listOf(
        Screen(1440, 900, 2.0),
        Screen(1512, 982, 2.0),
        Screen(1728, 1117, 2.0),
        Screen(1680, 1050, 2.0),
        Screen(2560, 1440, 2.0),
    )

    private val macGpus = listOf(
        Gpu("Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M1, Unspecified Version)", "M1"),
        Gpu("Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M2, Unspecified Version)", "M2"),
        Gpu("Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M3, Unspecified Version)", "M3"),
        Gpu("Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M2 Pro, Unspecified Version)", "M2 Pro"),
        Gpu("Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M3 Pro, Unspecified Version)", "M3 Pro"),
        Gpu("Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M1 Max, Unspecified Version)", "M1 Max"),
    )

    private val chromeVersions = listOf("124.0.0.0", "125.0.0.0", "126.0.0.0", "127.0.0.0")

    /** 生成一套新的设备指纹，保留名称 / 分组 / 语言 / 时区 / 代理等配置。 */
    fun apply(profile: Profile): Profile {
        val variant = pickVariant()
        val cores = if (variant.mobile) {
            listOf(6, 8, 8, 8, 8, 12).random()
        } else {
            listOf(4, 8, 8, 8, 12, 16).random()
        }
        val memory = if (variant.mobile) {
            listOf(4, 6, 8, 8, 8, 12).random()
        } else {
            listOf(8, 16, 16, 16, 32).random()
        }
        return profile.copy(
            userAgent = variant.userAgent,
            platform = variant.platform,
            screenWidth = variant.screen.width,
            screenHeight = variant.screen.height,
            devicePixelRatio = variant.screen.dpr,
            touchPoints = variant.touchPoints,
            mobile = variant.mobile,
            webglVendor = variant.gpu.vendor,
            webglRenderer = variant.gpu.renderer,
            hardwareConcurrency = cores,
            deviceMemory = memory,
            deviceModel = variant.label,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun pickVariant(): Variant = when ((0 until 6).random()) {
        0, 1, 2 -> androidVariant()
        3 -> iosVariant()
        4 -> windowsVariant()
        else -> macVariant()
    }

    private fun androidVariant(): Variant {
        val model = androidModels.random()
        val version = model.versions.random()
        val chrome = chromeVersions.random()
        return Variant(
            userAgent = "Mozilla/5.0 (Linux; Android $version; ${model.model}) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$chrome Mobile Safari/537.36",
            platform = "Linux armv8l",
            screen = model.screens.random(),
            touchPoints = 5,
            mobile = true,
            gpu = model.gpus.random(),
            label = model.name,
        )
    }

    private fun iosVariant(): Variant {
        val model = iosModels.random()
        val version = model.versions.random()
        val screen = model.screens.random()
        val cpu = if (model.family == "iPad") "CPU OS" else "CPU iPhone OS"
        return Variant(
            userAgent = "Mozilla/5.0 (${model.family}; $cpu $version like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/${version.replace('_', '.')} " +
                "Mobile/15E148 Safari/604.1",
            platform = model.family,
            screen = screen,
            touchPoints = 5,
            mobile = true,
            gpu = appleGpu,
            label = iosLabels[screen] ?: model.family,
        )
    }

    private fun windowsVariant(): Variant {
        val chrome = chromeVersions.random()
        val gpu = windowsGpus.random()
        return Variant(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$chrome Safari/537.36",
            platform = "Win32",
            screen = windowsScreens.random(),
            touchPoints = 0,
            mobile = false,
            gpu = gpu,
            label = "Windows · ${gpu.short}",
        )
    }

    private fun macVariant(): Variant {
        val chrome = chromeVersions.random()
        val gpu = macGpus.random()
        return Variant(
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$chrome Safari/537.36",
            platform = "MacIntel",
            screen = macScreens.random(),
            touchPoints = 0,
            mobile = false,
            gpu = gpu,
            label = "macOS · ${gpu.short}",
        )
    }
}
