package com.blockthis.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.blockthis.app.R

/**
 * Empty device admin. While it is active Android refuses to uninstall the app;
 * deactivating it requires the device admin settings screen, which the
 * accessibility service keeps unreachable while a strict block is running.
 */
class BlockerDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)
}
