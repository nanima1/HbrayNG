package com.v2ray.ang.util

import android.content.Intent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.v2ray.ang.dto.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManagerUtil {
    /**
     * Load the list of network applications.
     *
     * @param context The context to use.
     * @return A list of AppInfo objects representing the network applications.
     */
    suspend fun loadNetworkAppList(context: Context): ArrayList<AppInfo> =
        withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val apps = linkedMapOf<String, AppInfo>()

            fun addApplication(packageName: String, applicationInfo: ApplicationInfo?) {
                if (packageName.isBlank() || apps.containsKey(packageName) || applicationInfo == null) {
                    return
                }
                val appName = applicationInfo.loadLabel(packageManager).toString().ifBlank { packageName }
                val appIcon = applicationInfo.loadIcon(packageManager) ?: return
                val isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM > 0
                apps[packageName] = AppInfo(appName, packageName, appIcon, isSystemApp, 0)
            }

            runCatching {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }.getOrDefault(emptyList()).forEach { pkg ->
                addApplication(pkg.packageName, pkg.applicationInfo)
            }

            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            runCatching {
                packageManager.queryIntentActivities(launcherIntent, 0)
            }.getOrDefault(emptyList()).forEach { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@forEach
                addApplication(activityInfo.packageName, activityInfo.applicationInfo)
            }

            return@withContext ArrayList(apps.values)
        }

    fun getLastUpdateTime(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

}
