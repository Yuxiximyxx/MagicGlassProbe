# MagicGlassProbe

最小验证模块（LSPosed / libxposed API 102）：对微信底栏调用 MagicOS
framework-magic.jar 的窗口级液态玻璃 API `HnLiquidGlassWindowManger`，
验证第三方应用进程能否让系统替自己渲染底栏液态玻璃，从而替代
LiquidTab 那种进程内采样+shader 的重方案。

- 作用域：com.tencent.mm（微信主进程）
- 机制：hook Activity.onResume → 在视图树里找贴底全宽的底栏候选 →
  组装 HnLiquidGlassMaterial（BlurParamEx + GlassParamEx + ShapeParamEx 区域）
  → setWindowLiquidGlassEnable(窗口 SurfaceControl, true, material)
- 每一步结果都写入 LSPosed 日志，tag = MagicGlassProbe
- 方法面依据：真机 framework-magic.jar dexdump 提取（见同作者笔记
  《MagicOS液态玻璃系统API_方法面》）
