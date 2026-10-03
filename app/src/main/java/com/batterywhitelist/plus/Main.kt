package com.batterywhitelist.plus

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.DataOutputStream
import java.util.Locale

/**
 * MIUIX 变体版（纯 Material3 临时版）的主界面。
 *
 * 职责：
 * 1. 展示所有应用（带搜索防抖）
 * 2. 勾选要保护的应用，实时写入 SharedPreferences
 * 3. 自动通过 Root 权限部署守护脚本
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private val allApps = mutableListOf<AppItem>()

    /**
     * 缓存应用信息，防止搜索时频繁调用 loadLabel 导致卡顿。
     */
    data class AppItem(
        val name: String,
        val lowerName: String,
        val pkg: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 注意：SharedPreferences 名字必须与 BatteryWhitelistModule.java 里保持一致
        prefs = getSharedPreferences("battery_whitelist_prefs", MODE_PRIVATE)
        loadApps()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppListScreen()
                }
            }
        }

        // 部署 Root 守护脚本（三位一体防御的第三层）
        deployGuardScript()
    }

    /**
     * 预加载所有已安装应用的信息。
     * 过滤掉自己（这个 App）和系统核心包（android），防止误勾选导致系统崩坏。
     */
    private fun loadApps() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        allApps.clear()
        for (info in packages) {
            if (info.packageName != packageName && info.packageName != "android") {
                val name = info.loadLabel(pm).toString()
                allApps.add(
                    AppItem(
                        name = name,
                        lowerName = name.lowercase(Locale.getDefault()),
                        pkg = info.packageName
                    )
                )
            }
        }
    }

    /**
     * Compose UI 主体。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppListScreen() {
        var searchQuery by remember { mutableStateOf("") }

        // 读取当前已勾选保护的应用集合
        var protectedSet by remember {
            mutableStateOf(
                prefs.getStringSet("protected_packages", emptySet())?.toSet() ?: emptySet()
            )
        }

        // 根据搜索词过滤应用（只在内存中过滤，速度极快）
        val filteredApps = remember(searchQuery) {
            if (searchQuery.isEmpty()) {
                allApps
            } else {
                val q = searchQuery.lowercase()
                allApps.filter {
                    it.lowerName.contains(q) || it.pkg.lowercase().contains(q)
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题栏
            TopAppBar(
                title = {
                    Text(
                        text = "电池白名单守护者",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            )

            // 提示文字
            Text(
                text = "勾选要保护的应用（重启手机后生效）",
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("搜索应用名或包名...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            HorizontalDivider()

            // 应用列表
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredApps, key = { it.pkg }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = app.pkg,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 勾选开关
                        Switch(
                            checked = protectedSet.contains(app.pkg),
                            onCheckedChange = { checked ->
                                val newSet = protectedSet.toMutableSet()
                                if (checked) {
                                    newSet.add(app.pkg)
                                } else {
                                    newSet.remove(app.pkg)
                                }
                                protectedSet = newSet
                                // 实时写入配置
                                prefs.edit()
                                    .putStringSet("protected_packages", newSet)
                                    .apply()
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    /**
     * 通过 Root 权限部署守护脚本到 /data/adb/service.d/。
     *
     * 脚本内容：开机后启动无限循环，每 60 秒强制拉一次白名单，
     * 彻底焊死系统被暗中修改的可能性。
     */
    private fun deployGuardScript() {
        Thread {
            try {
                val saved = prefs.getStringSet("protected_packages", emptySet()) ?: return@Thread
                if (saved.isEmpty()) return@Thread

                // 构造要执行的命令列表
                val guardCmd = StringBuilder()
                for (p in saved) {
                    guardCmd
                        .append("  cmd deviceidle whitelist +").append(p).append("\n")
                        .append("  appops set ").append(p).append(" RUN_IN_BACKGROUND allow\n")
                        .append("  appops set ").append(p).append(" RUN_ANY_IN_BACKGROUND allow\n")
                }

                // 完整的 shell 脚本内容
                val script = "#!/system/bin/sh\n" +
                        "until [ \"\$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 2; done\n" +
                        "sleep 15\n" +
                        "while true; do\n" +
                        guardCmd +
                        "  sleep 60\n" +
                        "done\n"

                val scriptPath = "/data/adb/service.d/battery_guard.sh"

                // 通过 su 权限写入脚本文件
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("mkdir -p /data/adb/service.d\n")
                os.writeBytes("cat > $scriptPath << 'EOF'\n$script EOF\n")
                os.writeBytes("chmod 755 $scriptPath\n")
                os.writeBytes("exit\n")
                os.flush()
                process.waitFor()

                runOnUiThread {
                    Toast.makeText(
                        this, "守护脚本已部署", Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(
                        this, "部署脚本失败: ${t.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}