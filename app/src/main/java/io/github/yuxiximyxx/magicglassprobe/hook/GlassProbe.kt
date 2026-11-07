package io.github.yuxiximyxx.magicglassprobe.hook

import android.app.Activity
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.WeakHashMap

/**
 * GlassProbe —— 最小验证探针。
 *
 * 目的只有一个：验证「第三方应用进程能否调用 MagicOS framework-magic.jar
 * 里的窗口级液态玻璃 API（HnLiquidGlassWindowManger），让系统替应用渲染
 * 底栏玻璃」。方法面来自对真机 framework-magic.jar 的 dexdump 提取：
 *
 *   HnLiquidGlassWindowManger
 *     getSurfaceControl(View) : SurfaceControl   ← 实现是 view.getViewRootImpl().getSurfaceControl()，即窗口级
 *     setBlurEnableForView(View, boolean)        ← 转调 View.setBlurEnableForView（荣耀给 View 加的方法）
 *     setWindowBlurParams(SurfaceControl, BlurParamEx)
 *     setWindowLiquidGlassEnable(SurfaceControl, boolean, HnLiquidGlassMaterial)
 *
 *   材质 = HnLiquidGlassMaterial（setBlurParamEx + setGlassParamEx + setShapeParamEx）
 *   形状 = ShapeParamEx(Shape.RRect) + setRect(窗口坐标) + setRadius —— 玻璃只在形状区域内生效，
 *   所以「窗口级」不等于「整窗变玻璃」：区域由形状圈定，这正是它能给底栏用的原因。
 *
 * 每一步独立记日志（LSPosed 日志搜 MagicGlassProbe），失败不抛、不用重试轰炸：
 * 第 1 次未完全成功时 2 秒后只重试 1 次（等 SurfaceControl 就绪）。
 */
object GlassProbe {

    private const val TAG = "MagicGlassProbe"

    private lateinit var module: XposedModule

    /** 已排过探针的 Activity 实例（弱引用集合，防重复应用）。 */
    private val scheduled = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    fun install(module: XposedModule, classLoader: ClassLoader) {
        this.module = module
        val activityClass = Class.forName("android.app.Activity", false, classLoader)
        val onResume = activityClass.getDeclaredMethod("onResume")
        module.hook(onResume).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val act = chain.thisObject as? Activity
                if (act != null && act.packageName == HookEntry.TARGET_PACKAGE) {
                    schedule(act)
                }
            }.onFailure { log("onResume 后处理异常（已吞）", it) }
            result
        }
        log("Activity.onResume hook 已安装")
    }

    private fun schedule(act: Activity) {
        if (!scheduled.add(act)) return
        val decor = act.window?.decorView ?: return
        decor.postDelayed({ apply(act, attempt = 1) }, 900)
    }

    private fun apply(act: Activity, attempt: Int) {
        val decor = act.window?.decorView ?: return
        val density = act.resources.displayMetrics.density

        val found = findBottomBar(decor, density)
        val target: View
        val rect: RectF
        val radiusPx: Float
        if (found != null) {
            target = found
            val loc = IntArray(2)
            found.getLocationInWindow(loc)
            rect = RectF(
                loc[0].toFloat(), loc[1].toFloat(),
                (loc[0] + found.width).toFloat(), (loc[1] + found.height).toFloat()
            )
            radiusPx = 20f * density
            val idName = runCatching { act.resources.getResourceEntryName(found.id) }.getOrNull()
            log("底栏命中: ${found.javaClass.name} id=$idName rect=$rect radius=$radiusPx")
        } else {
            target = decor
            val h = 64f * density
            rect = RectF(0f, decor.height - h, decor.width.toFloat(), decor.height.toFloat())
            radiusPx = 0f
            log("未找到底栏候选，退回装饰视图底部条带 rect=$rect")
        }

        val ok = runGlass(act.classLoader, target, rect, radiusPx)
        if (ok) {
            log("PROBE DONE —— 系统液态玻璃调用链全部走通，看微信底栏是否变化")
            runCatching {
                Toast.makeText(act, "MagicGlass 探针已应用，看底栏", Toast.LENGTH_LONG).show()
            }
        } else if (attempt == 1) {
            log("第 1 次未走通，2 秒后重试一次")
            decor.postDelayed({ apply(act, attempt = 2) }, 2000)
        } else {
            log("PROBE FAILED —— 两次都没走通，看上面每步的 FAIL 行定位卡点")
        }
    }

    /** 在视图树里找「贴底、全宽、高 36~110dp」的候选，取层级最深的一个（通常就是底栏容器）。 */
    private fun findBottomBar(decor: View, density: Float): View? {
        val minH = 36f * density
        val maxH = 110f * density
        var best: View? = null
        var bestDepth = -1
        val stack = ArrayDeque<Pair<View, Int>>()
        stack.addLast(decor to 0)
        while (stack.isNotEmpty()) {
            val (v, depth) = stack.removeLast()
            if (v.visibility == View.VISIBLE &&
                v.width >= decor.width * 0.85 &&
                v.height >= minH && v.height <= maxH
            ) {
                val loc = IntArray(2)
                v.getLocationInWindow(loc)
                if (loc[1] + v.height >= decor.height - 2) {
                    log("候选: ${v.javaClass.name} depth=$depth rect=[${loc[0]},${loc[1]} - ${loc[0] + v.width},${loc[1] + v.height}]")
                    if (depth > bestDepth) {
                        best = v
                        bestDepth = depth
                    }
                }
            }
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) {
                    stack.addLast(v.getChildAt(i) to depth + 1)
                }
            }
        }
        return best
    }

    /** 反射调用系统玻璃 API。返回最后一步 setWindowLiquidGlassEnable 是否未抛异常。 */
    private fun runGlass(cl: ClassLoader, view: View, rect: RectF, radiusPx: Float): Boolean {
        // ---- 加载类 ----
        val clsMgr = loadClass(cl, "com.hihonor.android.graphics.HnLiquidGlassWindowManger") ?: return false
        val clsMaterial = loadClass(cl, "com.hihonor.android.graphics.material.HnLiquidGlassMaterial") ?: return false
        val clsBlur = loadClass(cl, "com.hihonor.android.graphics.material.atomiceffect.BlurParamEx") ?: return false
        val clsGlass = loadClass(cl, "com.hihonor.android.graphics.material.atomiceffect.GlassParamEx") ?: return false
        val clsShape = loadClass(cl, "com.hihonor.android.graphics.material.atomiceffect.ShapeParamEx") ?: return false
        val clsShapeEnum = loadClass(cl, "com.hihonor.android.graphics.material.atomiceffect.ShapeParamEx\$Shape") ?: return false
        val clsSC = loadClass(cl, "android.view.SurfaceControl") ?: return false

        // ---- 组装参数 ----
        val blur = step("new BlurParamEx") { newInstance(clsBlur) } ?: return false
        step("BlurParamEx.setRadius(48)") { clsBlur.getMethod("setRadius", F).invoke(blur, 48f) }
        step("BlurParamEx.setScale(0.3)") { clsBlur.getMethod("setScale", F).invoke(blur, 0.3f) }
        step("BlurParamEx.setSaturation(1.35)") { clsBlur.getMethod("setSaturation", F).invoke(blur, 1.35f) }

        val glass = step("new GlassParamEx") { newInstance(clsGlass) } ?: return false
        step("GlassParamEx.setRefraction(1.0)") { clsGlass.getMethod("setRefraction", F).invoke(glass, 1.0f) }
        step("GlassParamEx.setThickness(1.4)") { clsGlass.getMethod("setThickness", F).invoke(glass, 1.4f) }
        step("GlassParamEx.setDepth(0.5)") { clsGlass.getMethod("setDepth", F).invoke(glass, 0.5f) }
        step("GlassParamEx.setDispersion(0.25)") { clsGlass.getMethod("setDispersion", F).invoke(glass, 0.25f) }

        val shape = step("new ShapeParamEx(RRect)") {
            val rrect = clsShapeEnum.enumConstants?.firstOrNull { it.toString() == "RRect" }
                ?: throw IllegalStateException("Shape.RRect 不存在")
            clsShape.getConstructor(clsShapeEnum).newInstance(rrect)
        } ?: return false
        step("ShapeParamEx.setRect($rect)") { clsShape.getMethod("setRect", RectF::class.java).invoke(shape, rect) }
        step("ShapeParamEx.setRadius($radiusPx)") { clsShape.getMethod("setRadius", F).invoke(shape, radiusPx) }

        val material = step("new HnLiquidGlassMaterial") { newInstance(clsMaterial) } ?: return false
        step("material.setBlurParamEx") { clsMaterial.getMethod("setBlurParamEx", clsBlur).invoke(material, blur) }
        step("material.setGlassParamEx") { clsMaterial.getMethod("setGlassParamEx", clsGlass).invoke(material, glass) }
        step("material.setShapeParamEx") { clsMaterial.getMethod("setShapeParamEx", clsShape).invoke(material, shape) }

        // ---- 调用窗口级 API ----
        val mgr = step("new HnLiquidGlassWindowManger") { newInstance(clsMgr) } ?: return false
        step("mgr.setBlurEnableForView(view, true)") {
            clsMgr.getMethod("setBlurEnableForView", View::class.java, Z).invoke(mgr, view, true)
        }
        val sc = step("mgr.getSurfaceControl(view)") {
            clsMgr.getMethod("getSurfaceControl", View::class.java).invoke(mgr, view)
        } ?: return false
        val valid = runCatching { clsSC.getMethod("isValid").invoke(sc) }.getOrNull()
        log("SurfaceControl=$sc isValid=$valid")
        step("mgr.setWindowBlurParams(sc, blur)") {
            clsMgr.getMethod("setWindowBlurParams", clsSC, clsBlur).invoke(mgr, sc, blur)
        }
        val enableResult = step("mgr.setWindowLiquidGlassEnable(sc, true, material)") {
            clsMgr.getMethod("setWindowLiquidGlassEnable", clsSC, Z, clsMaterial)
                .invoke(mgr, sc, true, material)
        }
        return enableResult != STEP_FAILED
    }

    // ---------- 反射小工具 ----------

    private val F: Class<*> = Float::class.javaPrimitiveType!!
    private val Z: Class<*> = Boolean::class.javaPrimitiveType!!

    private val STEP_FAILED = Any()

    private fun loadClass(cl: ClassLoader, name: String): Class<*>? {
        return try {
            val c = Class.forName(name, false, cl)
            log("loadClass OK: $name")
            c
        } catch (t: Throwable) {
            log("loadClass FAIL: $name -> $t")
            null
        }
    }

    private fun newInstance(clazz: Class<*>): Any =
        clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    /** 执行一步并记日志；失败返回 STEP_FAILED（与正常返回值 null 区分）。 */
    private fun step(name: String, block: () -> Any?): Any? {
        return try {
            val r = block()
            log("STEP OK: $name")
            r
        } catch (t: Throwable) {
            val cause = if (t is InvocationTargetException) t.targetException ?: t else t
            log("STEP FAIL: $name -> $cause")
            STEP_FAILED
        }
    }

    private fun log(msg: String, t: Throwable? = null) {
        if (t != null) module.log(Log.INFO, TAG, msg, t) else module.log(Log.INFO, TAG, msg)
    }
}
