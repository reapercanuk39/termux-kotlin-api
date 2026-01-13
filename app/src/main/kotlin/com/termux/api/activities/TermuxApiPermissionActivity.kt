package com.termux.api.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.util.JsonWriter

import com.termux.api.util.ResultReturner
import com.termux.shared.android.PermissionUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants

class TermuxApiPermissionActivity : Activity() {

    companion object {
        private const val LOG_TAG = "TermuxApiPermissionActivity"

        /**
         * Intent extra containing the permissions to request.
         */
        const val PERMISSIONS_EXTRA: String = TermuxConstants.TERMUX_API_PACKAGE_NAME + ".permission_extra"

        /**
         * Check for and request permissions if necessary.
         *
         * @return if all permissions were already granted
         */
        @JvmStatic
        fun checkAndRequestPermissions(context: Context, intent: Intent, vararg permissions: String): Boolean {
            val permissionsToRequest = ArrayList<String>()
            for (permission in permissions) {
                if (!PermissionUtils.checkPermission(context, permission)) {
                    permissionsToRequest.add(permission)
                }
            }

            return if (permissionsToRequest.isEmpty()) {
                true
            } else {
                ResultReturner.returnData(context, intent, object : ResultReturner.ResultJsonWriter() {
                    @Throws(Exception::class)
                    override fun writeJson(out: JsonWriter) {
                        val errorMessage = "Please grant the following permission" +
                                (if (permissionsToRequest.size > 1) "s" else "") +
                                " to use this command: " +
                                TextUtils.join(" ,", permissionsToRequest)
                        out.beginObject().name("error").value(errorMessage).endObject()
                    }
                })

                val startIntent = Intent(context, TermuxApiPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putStringArrayListExtra(PERMISSIONS_EXTRA, permissionsToRequest)
                ResultReturner.copyIntentExtras(intent, startIntent)
                context.startActivity(startIntent)
                false
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        Logger.logDebug(LOG_TAG, "onNewIntent")

        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        Logger.logVerbose(LOG_TAG, "onResume")

        super.onResume()
        val permissionValues = intent.getStringArrayListExtra(PERMISSIONS_EXTRA)
        PermissionUtils.requestPermissions(this, permissionValues?.toTypedArray() ?: emptyArray(), 0)
        finish()
    }
}
