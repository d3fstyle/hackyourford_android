package com.forddiag.android.core

import android.content.Context

object AppPreferences {
    private const val PREFS_NAME = "forddiag_prefs"
    private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    private const val KEY_OBD_FAV_SCOPE = "obd_favorite_scope"
    private const val KEY_OBD_FAV_PROFILE_ID = "obd_favorite_profile_id"
    private const val KEY_OBD_FAV_DASHBOARD_ID = "obd_favorite_dashboard_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedProfileId(context: Context): String? =
        prefs(context).getString(KEY_SELECTED_PROFILE_ID, null)

    fun setSelectedProfileId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED_PROFILE_ID, id).apply()
    }

    fun getObdFavorite(context: Context): ObdFavoritePointer? {
        val sp = prefs(context)
        val id = sp.getString(KEY_OBD_FAV_DASHBOARD_ID, null) ?: return null
        val scopeRaw = sp.getString(KEY_OBD_FAV_SCOPE, null) ?: return null
        val scope = runCatching { ObdDashboardScope.valueOf(scopeRaw) }.getOrNull() ?: return null
        val profileId = sp.getString(KEY_OBD_FAV_PROFILE_ID, null)
        return ObdFavoritePointer(scope, id, profileId)
    }

    fun setObdFavorite(context: Context, favorite: ObdFavoritePointer) {
        prefs(context).edit()
            .putString(KEY_OBD_FAV_SCOPE, favorite.scope.name)
            .putString(KEY_OBD_FAV_DASHBOARD_ID, favorite.dashboardId)
            .putString(KEY_OBD_FAV_PROFILE_ID, favorite.profileId)
            .apply()
    }

    fun clearObdFavorite(context: Context) {
        prefs(context).edit()
            .remove(KEY_OBD_FAV_SCOPE)
            .remove(KEY_OBD_FAV_DASHBOARD_ID)
            .remove(KEY_OBD_FAV_PROFILE_ID)
            .apply()
    }
}
