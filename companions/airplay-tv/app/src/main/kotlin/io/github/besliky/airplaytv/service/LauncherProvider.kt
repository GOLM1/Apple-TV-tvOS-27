package io.github.besliky.airplaytv.service

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.besliky.airplaytv.ui.NowPlayingActivity
import io.github.besliky.airplaytv.BuildConfig

/** Read-only bridge to the same-signed launcher. No notification access or receiver startup. */
class LauncherProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        ctx.enforceCallingOrSelfPermission(PERMISSION, "Same-signed launcher required")
        val result = Bundle()
        if (method != "session") return result
        MediaKeys.launcherToken?.let { token ->
            result.putParcelable("token", token)
            result.putParcelable("open", PendingIntent.getActivity(ctx, 40,
                Intent(ctx, NowPlayingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return result
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        val PERMISSION = BuildConfig.APPLICATION_ID + ".LAUNCHER_MEDIA"
        val URI: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.launcher/session")
    }
}
