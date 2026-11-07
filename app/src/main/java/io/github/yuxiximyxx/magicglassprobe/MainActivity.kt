package io.github.yuxiximyxx.magicglassprobe

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** 探针模块的说明页：没有设置项，只交代验证方法。 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = """
                MagicGlass 探针 v0.2.0

                验证目标：第三方应用进程能不能直接调用 MagicOS
                framework 的窗口级液态玻璃 API
                （HnLiquidGlassWindowManger），让系统替应用渲染底栏玻璃。

                使用方法：
                1. 在 LSPosed 里启用本模块，作用域已预设为微信；
                2. 强停并重新打开微信，停在主界面（底栏可见）；
                3. 约 1 秒后模块会自动找到微信底栏，
                   对它所在的窗口调用系统液态玻璃；
                4. 看底栏是否变成系统液态玻璃效果。

                若无变化：在 LSPosed 日志里搜 MagicGlassProbe，
                每一步的成功/失败都记在里面，把相关行发回即可。
            """.trimIndent()
            textSize = 15f
            gravity = Gravity.START
            setPadding(48, 64, 48, 48)
        }
        setContentView(tv)
    }
}
