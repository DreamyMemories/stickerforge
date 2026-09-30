/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of the whatsapp/stickers repository.
 *
 * Adapted by StickerForge for dynamic packs. Behaviour unchanged.
 */
package com.stickerforge.app.whatsapp

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import com.stickerforge.app.BuildConfig

/** Asks WhatsApp whether a pack from this app has been added to the sticker tray. */
object WhitelistCheck {

    const val CONSUMER_WHATSAPP_PACKAGE_NAME = "com.whatsapp"
    const val SMB_WHATSAPP_PACKAGE_NAME = "com.whatsapp.w4b"

    private const val AUTHORITY_QUERY_PARAM = "authority"
    private const val IDENTIFIER_QUERY_PARAM = "identifier"
    private const val CONTENT_PROVIDER = ".provider.sticker_whitelist_check"
    private const val QUERY_PATH = "is_whitelisted"
    private const val QUERY_RESULT_COLUMN_NAME = "result"

    fun isWhitelisted(context: Context, identifier: String): Boolean {
        return try {
            val packageManager = context.packageManager
            if (!isWhatsAppConsumerAppInstalled(packageManager) &&
                !isWhatsAppSmbAppInstalled(packageManager)
            ) {
                return false
            }
            isStickerPackWhitelistedInWhatsAppConsumer(context, identifier) &&
                isStickerPackWhitelistedInWhatsAppSmb(context, identifier)
        } catch (_: Exception) {
            false
        }
    }

    private fun isWhitelistedFromProvider(
        context: Context,
        identifier: String,
        whatsappPackageName: String,
    ): Boolean {
        val packageManager = context.packageManager
        if (!isPackageInstalled(whatsappPackageName, packageManager)) {
            // WhatsApp is not installed, so it cannot have the pack: treat as "nothing to do".
            return true
        }
        val whatsappProviderAuthority = whatsappPackageName + CONTENT_PROVIDER
        val providerInfo: ProviderInfo? =
            packageManager.resolveContentProvider(whatsappProviderAuthority, PackageManager.GET_META_DATA)
        if (providerInfo == null) return false
        val queryUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(whatsappProviderAuthority)
            .appendPath(QUERY_PATH)
            .appendQueryParameter(AUTHORITY_QUERY_PARAM, BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendQueryParameter(IDENTIFIER_QUERY_PARAM, identifier)
            .build()
        return try {
            context.contentResolver.query(queryUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getInt(cursor.getColumnIndexOrThrow(QUERY_RESULT_COLUMN_NAME)) == 1
                } else {
                    false
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            val info: ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
            info.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isWhatsAppConsumerAppInstalled(packageManager: PackageManager): Boolean =
        isPackageInstalled(CONSUMER_WHATSAPP_PACKAGE_NAME, packageManager)

    fun isWhatsAppSmbAppInstalled(packageManager: PackageManager): Boolean =
        isPackageInstalled(SMB_WHATSAPP_PACKAGE_NAME, packageManager)

    fun isStickerPackWhitelistedInWhatsAppConsumer(context: Context, identifier: String): Boolean =
        isWhitelistedFromProvider(context, identifier, CONSUMER_WHATSAPP_PACKAGE_NAME)

    fun isStickerPackWhitelistedInWhatsAppSmb(context: Context, identifier: String): Boolean =
        isWhitelistedFromProvider(context, identifier, SMB_WHATSAPP_PACKAGE_NAME)

    /** Builds the "add this pack to WhatsApp" intent. */
    fun buildAddIntent(identifier: String, packName: String): Intent = Intent().apply {
        action = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
        putExtra("sticker_pack_id", identifier)
        putExtra("sticker_pack_authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
        putExtra("sticker_pack_name", packName)
    }
}
