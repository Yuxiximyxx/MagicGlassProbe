package io.github.yuxiximyxx.magicglassprobe.hook

import android.app.Activity
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.WeakHashMap

/**
 * GlassProbe v0.2 —— 分阶段验证「视图级材质」路线。
 *
 * v0.1 结论（真机日志实证）：窗口级 HnLiquidGlassWindowManger 调用链全部走通、
 * SurfaceControl 有效、事务 apply 无异常，但画面无变化 —— 该路径服务的是
 * 独立玻璃窗口（控制中心那类），对普通应用窗口不渲染。
 *
 * framework 里真正的应用内入口（dexdump 实证）：
 *   android.view.View.setHnMaterial(Material[], MaterialOpt)
 *   com.hihonor.android.graphics.material.HnMaterialManager.setHnMaterial(View, HnMaterial, HnMaterialOpt)
 * 材质挂到 View / RenderNode 上，由应用自己的 RenderThread 采样窗内背景渲染。
 *
 * 本版在同一个底栏上按时间顺序跑四个阶段（每阶段 Toast 编号），一次安装测完：
 *   A 视图材质（裸调 View.setHnMaterial，形状用视图本地坐标）
 *   B 视图材质（走 HnMaterialManager 官方静态入口）
 *   C 视图材质 + MaterialOpt 标志（setLayerBackdrop / setClearBackground）
 *   D 兜底：HnBackgroundBlurDrawable 挂作底栏背景（系统磨砂，非液态）
 */
object GlassProbe {

    private const val TAG = "MagicGlassProbe"

    private lateinit var module: XposedModule

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
        log("Activity.onResume hook 已安装（v0.2 分阶段）")
    }

    private fun schedule(act: Activity) {
        if (!scheduled.add(act)) return
        val decor = act.window?.decorView ?: return
        decor.postDelayed({ runStages(act) }, 900)
    }

    private fun runStages(act: Activity) {
        val decor = act.window?.decorView ?: return
        val density = act.resources.displayMetrics.density
        // 阶段 1：HnMaterialManager 官方入口（v0.2 的 B，已证可见效）
        applyToBar(act, density, "阶段1") { cl, bar ->
            stageB(act, cl, bar, density)
        }
        // 阶段 2：裸调 View.setHnMaterial + MaterialOpt 标志（v0.2 的 C，已证可见效）
        decor.postDelayed({
            applyToBar(act, density, "阶段2") { cl, bar ->
                stageC(act, cl, bar, density)
            }
        }, 5000)
        // 阶段 3：系统磨砂 Drawable 兜底（v0.2 的 D，已证可见效）
        decor.postDelayed({
            applyToBar(act, density, "阶段3") { cl, bar ->
                stageD(act, cl, bar)
            }
        }, 10000)
    }

    /** 每个阶段都重新找一次底栏（几何会稳定下来），且校验命中的是真底栏。 */
    private fun applyToBar(
        act: Activity,
        density: Float,
        label: String,
        block: (ClassLoader, View) -> Unit
    ) {
        val decor = act.window?.decorView ?: return
        val bar = findBottomBar(decor, density)
        if (bar == null) {
            log("$label 未找到合格底栏候选，跳过")
            return
        }
        val idName = runCatching { act.resources.getResourceEntryName(bar.id) }.getOrNull()
        log("$label 底栏命中: ${bar.javaClass.name} id=$idName size=${bar.width}x${bar.height} 祖先链=${ancestorChain(bar)}")
        runCatching { block(act.classLoader, bar) }
            .onFailure { log("$label 执行异常（已吞）", it) }
    }

    private fun ancestorChain(v: View): String {
        val sb = StringBuilder()
        var p: View? = v.parent as? View
        var n = 0
        while (p != null && n < 8) {
            if (n > 0) sb.append(" < ")
            sb.append(p.javaClass.simpleName)
            p = p.parent as? View
            n++
        }
        return sb.toString()
    }

    // ---------------- 阶段实现 ----------------

    /** 组装一份液态材质；shapeRect 用视图本地坐标（视图级路径）。 */
    private fun buildMaterial(cl: ClassLoader, w: Int, h: Int, radiusPx: Float): Any? {
        val clsMaterial = loadClass(cl, "com.hihonor.android.graphics.material.HnLiquidGlassMaterial") ?: return null
        val clsBlur = loadClass(cl, "com.hihonor.android.graphics.material.atomiceffect.BlurParamEx") ?: return null
        val clsGlass = loadClass(cl, "com.hihonor.android.graphics.material.atomiceffect.GlassParamEx") ?: return null
        val clsShape = loadClass(cl, "com.hihonor.android.graphics.material.atomiceffect.ShapeParamEx") ?: return null
        val clsShapeEnum = loadClass(cl, "com.hihonor.android.graphics.material.atomiceffect.ShapeParamEx\$Shape") ?: return null

        val blur = step("new BlurParamEx") { newInstance(clsBlur) } ?: return null
        step("blur.setRadius(48)") { clsBlur.getMethod("setRadius", F).invoke(blur, 48f) }
        step("blur.setScale(0.3)") { clsBlur.getMethod("setScale", F).invoke(blur, 0.3f) }
        step("blur.setSaturation(1.35)") { clsBlur.getMethod("setSaturation", F).invoke(blur, 1.35f) }

        val glass = step("new GlassParamEx") { newInstance(clsGlass) } ?: return null
        step("glass.setRefraction(1.0)") { clsGlass.getMethod("setRefraction", F).invoke(glass, 1.0f) }
        step("glass.setThickness(1.4)") { clsGlass.getMethod("setThickness", F).invoke(glass, 1.4f) }
        step("glass.setDepth(0.5)") { clsGlass.getMethod("setDepth", F).invoke(glass, 0.5f) }
        step("glass.setDispersion(0.25)") { clsGlass.getMethod("setDispersion", F).invoke(glass, 0.25f) }

        val shape = step("new ShapeParamEx(RRect)") {
            val rrect = clsShapeEnum.enumConstants?.firstOrNull { it.toString() == "RRect" }
                ?: throw IllegalStateException("Shape.RRect 不存在")
            clsShape.getConstructor(clsShapeEnum).newInstance(rrect)
        } ?: return null
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        step("shape.setRect(local $rect)") { clsShape.getMethod("setRect", RectF::class.java).invoke(shape, rect) }
        step("shape.setRadius($radiusPx)") { clsShape.getMethod("setRadius", F).invoke(shape, radiusPx) }

        val material = step("new HnLiquidGlassMaterial") { newInstance(clsMaterial) } ?: return null
        step("material.setBlurParamEx") { clsMaterial.getMethod("setBlurParamEx", clsBlur).invoke(material, blur) }
        step("material.setGlassParamEx") { clsMaterial.getMethod("setGlassParamEx", clsGlass).invoke(material, glass) }
        step("material.setShapeParamEx") { clsMaterial.getMethod("setShapeParamEx", clsShape).invoke(material, shape) }
        return material
    }

    private fun newOpt(cl: ClassLoader): Any? {
        val clsOpt = loadClass(cl, "com.hihonor.android.graphics.material.HnMaterialOpt") ?: return null
        return step("new HnMaterialOpt") { newInstance(clsOpt) }
    }

    private fun stageA(act: Activity, cl: ClassLoader, bar: View, density: Float) {
        log("=== 阶段 A：View.setHnMaterial 裸调 ===")
        val material = buildMaterial(cl, bar.width, bar.height, 20f * density) ?: return
        val clsMaterial = material.javaClass
        val liquid = step("material.toLiquidGlassMaterial()") {
            clsMaterial.getMethod("toLiquidGlassMaterial").invoke(material)
        } ?: return
        log("toLiquidGlassMaterial -> $liquid")
        val opt = newOpt(cl) ?: return
        val clsOpt = loadClass(cl, "com.hihonor.android.graphics.material.HnMaterialOpt") ?: return
        val materialOpt = step("opt.getMaterialOpt()") {
            clsOpt.getMethod("getMaterialOpt").invoke(opt)
        } ?: return
        val clsBaseMaterial = loadClass(cl, "android.graphics.material.Material") ?: return
        val arr = ReflectArray.newInstance(clsBaseMaterial, 1)
        ReflectArray.set(arr, 0, liquid)
        step("view.setHnMaterial([liquid], opt)") {
            val m = View::class.java.getMethod(
                "setHnMaterial",
                ReflectArray.newInstance(clsBaseMaterial, 0).javaClass,
                materialOpt.javaClass
            )
            m.invoke(bar, arr, materialOpt)
        }
        step("view.setBlurEnableForView(true) via View") {
            View::class.java.getMethod("setBlurEnableForView", Z).invoke(bar, true)
        }
        toast(act, "阶段A 已应用：View 液态材质")
    }

    private fun stageB(act: Activity, cl: ClassLoader, bar: View, density: Float) {
        log("=== 阶段 1：HnMaterialManager.setHnMaterial(View,...) ===")
        val material = buildMaterial(cl, bar.width, bar.height, 20f * density) ?: return
        val opt = newOpt(cl) ?: return
        val clsMgr = loadClass(cl, "com.hihonor.android.graphics.material.HnMaterialManager") ?: return
        val clsMaterial = loadClass(cl, "com.hihonor.android.graphics.material.HnLiquidGlassMaterial") ?: return
        val clsOpt = loadClass(cl, "com.hihonor.android.graphics.material.HnMaterialOpt") ?: return
        step("HnMaterialManager.setHnMaterial(view, material, opt)") {
            clsMgr.getMethod("setHnMaterial", View::class.java, clsMaterial, clsOpt)
                .invoke(null, bar, material, opt)
        }
        toast(act, "阶段1 已应用：Manager 液态材质")
    }

    private fun stageC(act: Activity, cl: ClassLoader, bar: View, density: Float) {
        log("=== 阶段 2：View.setHnMaterial + MaterialOpt 标志 ===")
        val material = buildMaterial(cl, bar.width, bar.height, 20f * density) ?: return
        val liquid = step("material.toLiquidGlassMaterial()") {
            material.javaClass.getMethod("toLiquidGlassMaterial").invoke(material)
        } ?: return
        val opt = newOpt(cl) ?: return
        val clsOpt = opt.javaClass
        step("opt.setLayerBackdrop(true)") { clsOpt.getMethod("setLayerBackdrop", Z).invoke(opt, true) }
        step("opt.setClearBackground(true)") { clsOpt.getMethod("setClearBackground", Z).invoke(opt, true) }
        val materialOpt = step("opt.getMaterialOpt()") {
            clsOpt.getMethod("getMaterialOpt").invoke(opt)
        } ?: return
        val clsBaseMaterial = loadClass(cl, "android.graphics.material.Material") ?: return
        val arr = ReflectArray.newInstance(clsBaseMaterial, 1)
        ReflectArray.set(arr, 0, liquid)
        step("view.setHnMaterial([liquid], opt+flags)") {
            val m = View::class.java.getMethod(
                "setHnMaterial",
                ReflectArray.newInstance(clsBaseMaterial, 0).javaClass,
                materialOpt.javaClass
            )
            m.invoke(bar, arr, materialOpt)
        }
        toast(act, "阶段2 已应用：材质+backdrop 标志")
    }

    private fun stageD(act: Activity, cl: ClassLoader, bar: View) {
        log("=== 阶段 3：HnBackgroundBlurDrawable 兜底磨砂 ===")
        val clsD = loadClass(cl, "com.hihonor.android.graphics.drawable.HnBackgroundBlurDrawable") ?: return
        val d = step("new HnBackgroundBlurDrawable") { newInstance(clsD) } ?: return
        step("drawable.createBlurDrawable(bar)") {
            clsD.getMethod("createBlurDrawable", View::class.java).invoke(d, bar)
        }
        step("drawable.setBlurRadius(150)") {
            clsD.getMethod("setBlurRadius", Int::class.javaPrimitiveType).invoke(d, 150)
        }
        step("drawable.setSaturation(1.35)") { clsD.getMethod("setSaturation", F).invoke(d, 1.35f) }
        step("drawable.setColor(0x40FFFFFF)") {
            clsD.getMethod("setColor", Int::class.javaPrimitiveType).invoke(d, 0x40FFFFFF)
        }
        step("drawable.setBackgroundBlur(bar)") {
            clsD.getMethod("setBackgroundBlur", View::class.java).invoke(d, bar)
        }
        toast(act, "阶段3 已应用：系统模糊背景")
    }

    // ---------------- 找底栏（与 v0.1 相同口径） ----------------

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
                v.height >= minH && v.height <= maxH &&
                !insideScrollingContainer(v)
            ) {
                val loc = IntArray(2)
                v.getLocationInWindow(loc)
                if (loc[1] + v.height >= decor.height - 2) {
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

    /** v0.2 教训：聊天列表 item 会垫在底栏下面且几何一模一样，命中它效果会被
     *  RecyclerView 回收带到别的行上。底栏绝不会在滚动容器里面，先排除。 */
    private fun insideScrollingContainer(v: View): Boolean {
        var p: View? = v.parent as? View
        while (p != null) {
            val n = p.javaClass.name
            if (n.contains("ListView") || n.contains("RecyclerView") ||
                n.contains("AbsListView") || n.contains("ScrollView")
            ) return true
            p = p.parent as? View
        }
        return false
    }

    // ---------------- 反射小工具 ----------------

    private val F: Class<*> = Float::class.javaPrimitiveType!!
    private val Z: Class<*> = Boolean::class.javaPrimitiveType!!

    private fun loadClass(cl: ClassLoader, name: String): Class<*>? {
        return try {
            Class.forName(name, false, cl)
        } catch (t: Throwable) {
            log("loadClass FAIL: $name -> $t")
            null
        }
    }

    private fun newInstance(clazz: Class<*>): Any =
        clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun step(name: String, block: () -> Any?): Any? {
        return try {
            val r = block()
            log("STEP OK: $name")
            r
        } catch (t: Throwable) {
            val cause = if (t is InvocationTargetException) t.targetException ?: t else t
            log("STEP FAIL: $name -> $cause")
            null
        }
    }

    private fun toast(act: Activity, msg: String) {
        runCatching { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun log(msg: String, t: Throwable? = null) {
        if (t != null) module.log(Log.INFO, TAG, msg, t) else module.log(Log.INFO, TAG, msg)
    }
}
