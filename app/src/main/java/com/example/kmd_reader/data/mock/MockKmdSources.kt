package com.example.kmd_reader.data.mock

object MockKmdSources {
    private val sources = mapOf(
        "rain-city" to """
            ---
            title: 雨城慢镜
            mode: stage
            speed: 45
            designWidth: 1080
            designHeight: 1920
            ---

            雨从街灯边缘慢慢落下。 @ f.blue
            {玻璃窗上映着一座安静的城。} @ f.hold(0.8s).wave
            读者向下滑动时，文字像水迹一样离开屏幕。
        """.trimIndent(),
        "star-manual" to """
            ---
            title: 星图操作手册
            mode: stage
            speed: 55
            designWidth: 1440
            designHeight: 1080
            ---

            第一页：确认星门坐标。 @ f.yellow
            第二页：把航线锁定在蓝色刻度之间。 @ f.blue.hold(0.6s)
            第三页：若仪表闪烁，请等待下一次脉冲。
        """.trimIndent(),
        "glass-rail" to """
            ---
            title: 玻璃铁轨
            mode: stage
            speed: 50
            designWidth: 1920
            designHeight: 1080
            ---

            @ cam.move(120, 0, 1s)!
            {列车穿过冻结的桥面。} @ f.blue.hold(0.8s).white

            @ cam.zoom(0.9, 1s)!
            玻璃铁轨反射出远处的城市灯火。 @ f.shake(3)

            @ cam.reset(1s)!
            最后一节车厢消失在屏幕右侧。
        """.trimIndent(),
        "choice-room" to """
            ---
            title: 选择之室
            mode: stage
            speed: 48
            designWidth: 1920
            designHeight: 1080
            ---

            房间里只有一张桌子。 @ f.hold(0.5s)
            你听见门后传来一句很轻的询问。
            {向左看}，或{向右看}？ @ f.green f.yellow
        """.trimIndent()
    )

    fun sourceFor(workId: String): String? = sources[workId]
}
