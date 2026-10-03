package com.batterywhitelist.plus

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.FileWriter
import java.lang.reflect.Method
import java.util.Date

class BatteryWhitelistModule : XposedModule() {

    companion object {
        private const val TAG = "BatteryWhitelist"
        private const val PREFS_NAME = "battery_whitelist_prefs"
        private const val KEY_LIST = "protected_packages"
    }

    private var prefs: SharedPreferences? = null
    private val protectedPackages = mutableSetOf<String>()

    /**
     * 物理探针日志：由于 ColorOS 对 system_server 的 logcat 做了严格拦截，
     * 这里直接写入文件，绕过所有日志过滤。
     */
    private fun writeLog(msg: String) {
        Log.e(TAG, msg)
        runCatching {
            FileWriter("/data/system/BatteryWhitelist.log", true).use {
                it.write("${Date()} : $msg\n")
            }
        }
    }

    /**
     * 反射初始化跨进程的 SharedPreferences。
     * 使用 ActivityThread 获取系统上下文，并强行读取 UI 应用的数据。
     */
    private fun initPrefs() {
        if (prefs != null) return
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAtMethod = atClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }
            val currentAt = currentAtMethod.invoke(null)

            val getSystemContextMethod = atClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            val systemContext = getSystemContextMethod.invoke(currentAt) as? Context ?: return@runCatching

            // 注意：包名必须改成新项目的包名 com.batterywhitelist.miuix
            val uiContext = systemContext.createPackageContext(
                "com.batterywhitelist.miuix",
                Context.CONTEXT_IGNORE_SECURITY
            )

            prefs = uiContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            writeLog("成功连接 UI 配置！")
        }
    }

    /**
     * 刷新受保护的应用列表。
     * 每次触发拦截前调用此方法，保证 UI 修改后底层实时生效。
     */
    private fun refreshProtectedPackages() {
        initPrefs()
        val currentPrefs = prefs ?: return
        val saved = currentPrefs.getStringSet(KEY_LIST, emptySet()) ?: emptySet()
        protectedPackages.clear()
        protectedPackages.addAll(saved)
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        writeLog("!!! 模块已加载 onModuleLoaded !!!")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        writeLog("!!! 成功进入系统框架 onSystemServerStarting !!!")
        val loader = param.classLoader ?: run {
            writeLog("classLoader is null，放弃！")
            return
        }

        // 布置防线
        hookDeviceIdleController(loader)
        hookOplusHansManager(loader)
        hookOplusDeviceIdleHelper(loader)

        // 启动内存守护线程（兜底机制）
        startGuardianThread()

        writeLog("防御网布置完毕！")
    }

    /**
     * 拦截原生 Doze 白名单的移除动作。
     */
    private fun hookDeviceIdleController(loader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.android.server.deviceidle.DeviceIdleController", false, loader)
            for (method in clazz.declaredMethods) {
                if ("removePowerSaveWhitelistAppInternal" == method.name) {
                    hookMethod(method, object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            refreshProtectedPackages()
                            val args = chain.args
                            if (args.isNotEmpty() && args[0] is String) {
                                val pkg = args[0] as String
                                if (protectedPackages.contains(pkg)) {
                                    writeLog("拦截移除 Doze 白名单: $pkg")
                                    return when (method.returnType) {
                                        Boolean::class.javaPrimitiveType -> true
                                        Int::class.javaPrimitiveType -> 1
                                        else -> null
                                    }
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        }.onFailure { writeLog("hookDeviceIdleController 失败: ${it.message}") }
    }

    /**
     * 绞杀 ColorOS 的后台管理机制 OplusHansManager。
     */
    private fun hookOplusHansManager(loader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.android.server.am.OplusHansManager", false, loader)
            for (method in clazz.declaredMethods) {
                val name = method.name

                if ("inCachedFreezeKillWhiteList" == name || "inAndroidFreezeKillWhiteList" == name) {
                    hookMethod(method, object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            refreshProtectedPackages()
                            for (arg in chain.args) {
                                if (arg is String && protectedPackages.contains(arg)) {
                                    writeLog("伪造 HansManager 白名单豁免: $arg")
                                    return true
                                }
                            }
                            return chain.proceed()
                        }
                    })
                    writeLog("已锁定看门狗: $name")
                }

                if ("handleRemoveTask" == name) {
                    hookMethod(method, object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            refreshProtectedPackages()
                            val args = chain.args
                            if (args.size >= 3 && args[2] is String) {
                                val pkg = args[2] as String
                                if (protectedPackages.contains(pkg)) {
                                    writeLog("拦截 HansManager 清理任务: $pkg")
                                    return null
                                }
                            }
                            return chain.proceed()
                        }
                    })
                    writeLog("已锁定刽子手: $name")
                }
            }
        }.onFailure { writeLog("Hook OplusHansManager 失败: ${it.message}") }
    }

    /**
     * 篡改 ColorOS 白名单生成逻辑 OplusDeviceIdleHelper（终极杀招）。
     */
    private fun hookOplusDeviceIdleHelper(loader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.android.server.OplusDeviceIdleHelper", false, loader)
            for (method in clazz.declaredMethods) {
                val name = method.name

                if ("getNewWhiteList" == name || "whiteListChangedHandle" == name) {
                    hookMethod(method, object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val args = chain.args
                            if (args.isNotEmpty() && args[0] is ArrayList<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val list = args[0] as ArrayList<String>
                                refreshProtectedPackages()
                                for (pkg in protectedPackages) {
                                    if (!list.contains(pkg)) {
                                        list.add(pkg)
                                        writeLog("篡改生死簿: 强制注入 $name -> $pkg")
                                    }
                                }
                            }
                            return chain.proceed()
                        }
                    })
                    writeLog("已锁定生死簿: $name")
                }
            }
        }.onFailure { writeLog("Hook OplusDeviceIdleHelper 失败: ${it.message}") }
    }

    /**
     * 内存守护线程（兜底方案）。
     * 每 60 秒主动执行一次 cmd 命令，强制纠正底层状态。
     */
    private fun startGuardianThread() {
        Thread {
            while (true) {
                runCatching {
                    refreshProtectedPackages()
                    for (pkg in protectedPackages) {
                        runCatching {
                            Runtime.getRuntime().exec(arrayOf("/system/bin/cmd", "deviceidle", "whitelist", "+$pkg"))
                            Runtime.getRuntime().exec(arrayOf("/system/bin/cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow"))
                            Runtime.getRuntime().exec(arrayOf("/system/bin/cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow"))
                        }
                    }
                }.onFailure { writeLog("守护线程异常: ${it.message}") }
                Thread.sleep(60000)
            }
        }.start()
        writeLog("内存守护线程已启动")
    }

    /**
     * 反射黑魔法：绕过 AAR 包的编译缺陷进行 Hook。
     * 因为在 API 102 的 AAR 中，XposedInterface.hook 的重载签名存在编译缺陷。
     */
    private fun hookMethod(method: Method, hooker: XposedInterface.Hooker) {
        runCatching {
            val hookMethod = XposedInterface::class.java.getDeclaredMethod("hook", java.lang.reflect.Executable::class.java)
            hookMethod.isAccessible = true
            val hookBuilder = hookMethod.invoke(this, method)

            val interceptMethod = hookBuilder.javaClass.getMethod("intercept", XposedInterface.Hooker::class.java)
            interceptMethod.isAccessible = true
            interceptMethod.invoke(hookBuilder, hooker)
        }.onFailure { writeLog("反射 Hook 失败: ${it.message}") }
    }
}