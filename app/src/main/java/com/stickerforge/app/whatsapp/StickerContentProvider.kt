/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of the whatsapp/stickers repository.
 *
 * Adapted by StickerForge to serve dynamically created packs from app
 * storage instead of assets bundled in the APK. The provider contract
 * (authority, URI shapes, query columns) is unchanged.
 */
package com.stickerforge.app.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import com.stickerforge.app.BuildConfig
import com.stickerforge.app.data.PackRepository
import com.stickerforge.app.model.StickerPack

/**
 * Serves this app's sticker packs to WhatsApp.
 *
 * WhatsApp queries:
 *  - `<authority>/metadata`                          all packs
 *  - `<authority>/metadata/<pack_identifier>`        one pack
 *  - `<authority>/stickers/<pack_identifier>`        sticker entries of a pack
 *  - `<authority>/stickers_asset/<pack>/<file>`      sticker or tray bytes
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var repository: PackRepository
    private val matcher = UriMatcher(UriMatcher.NO_MATCH)

    override fun onCreate(): Boolean {
        val context = requireNotNull(context) { "No context" }
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        check(authority.startsWith(context.packageName)) {
            "Content provider authority ($authority) must start with the package name"
        }
        matcher.addURI(authority, METADATA, METADATA_CODE)
        matcher.addURI(authority, "$METADATA/*", METADATA_CODE_FOR_SINGLE_PACK)
        matcher.addURI(authority, "$STICKERS/*", STICKERS_CODE)
        matcher.addURI(authority, "$STICKERS_ASSET/*/*", STICKERS_ASSET_CODE)
        repository = PackRepository(context)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = when (matcher.match(uri)) {
        METADATA_CODE -> packInfoCursor(uri, repository.listPacks())
        METADATA_CODE_FOR_SINGLE_PACK -> {
            val identifier = uri.lastPathSegment.orEmpty()
            packInfoCursor(uri, listOfNotNull(repository.getPack(identifier)))
        }
        STICKERS_CODE -> stickersCursor(uri, uri.lastPathSegment.orEmpty())
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != STICKERS_ASSET_CODE) return null
        val segments = uri.pathSegments
        if (segments.size != 3) throw IllegalArgumentException("Path segments should be 3, uri is: $uri")
        val identifier = segments[1]
        val fileName = segments[2]
        if (TextUtils.isEmpty(identifier) || TextUtils.isEmpty(fileName)) {
            throw IllegalArgumentException("Empty identifier or file name in $uri")
        }
        val pack = repository.getPack(identifier) ?: return null
        if (!belongsToPack(pack, fileName)) return null
        val file = repository.stickerFile(identifier, fileName) ?: return null
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        METADATA_CODE -> "vnd.android.cursor.dir/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.$METADATA"
        METADATA_CODE_FOR_SINGLE_PACK -> "vnd.android.cursor.item/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.$METADATA"
        STICKERS_CODE -> "vnd.android.cursor.dir/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.$STICKERS"
        STICKERS_ASSET_CODE -> if (uri.lastPathSegment == StickerPack.TRAY_FILE_NAME) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Not supported")

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Not supported")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Not supported")

    // ---------------------------------------------------------------- internals

    private fun belongsToPack(pack: StickerPack, fileName: String): Boolean =
        fileName == pack.trayImageFileName || pack.stickers.any { it.fileName == fileName }

    private fun packInfoCursor(uri: Uri, packs: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(PACK_COLUMNS)
        for (pack in packs) {
            cursor.newRow()
                .add(pack.identifier)
                .add(pack.name)
                .add(pack.publisher)
                .add(pack.trayImageFileName)
                .add(null as String?) // android_play_store_link
                .add(null as String?) // ios_app_download_link
                .add(null as String?) // sticker_pack_publisher_email
                .add(null as String?) // sticker_pack_publisher_website
                .add(null as String?) // sticker_pack_privacy_policy_website
                .add(null as String?) // sticker_pack_license_agreement_website
                .add(pack.imageDataVersion)
                .add(0) // avoid cache is deprecated since WhatsApp 2.25.9.78
                .add(if (pack.animated) 1 else 0)
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    private fun stickersCursor(uri: Uri, identifier: String): Cursor {
        val cursor = MatrixCursor(STICKER_COLUMNS)
        val pack = repository.getPack(identifier)
        if (pack != null) {
            for (sticker in pack.stickers) {
                cursor.addRow(arrayOf(sticker.fileName, TextUtils.join(",", sticker.emojis), sticker.accessibilityText))
            }
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    companion object {
        const val STICKER_PACK_IDENTIFIER_IN_QUERY = "sticker_pack_identifier"
        const val STICKER_PACK_NAME_IN_QUERY = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER_IN_QUERY = "sticker_pack_publisher"
        const val STICKER_PACK_ICON_IN_QUERY = "sticker_pack_icon"
        const val ANDROID_APP_DOWNLOAD_LINK_IN_QUERY = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK_IN_QUERY = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"
        const val STICKER_FILE_NAME_IN_QUERY = "sticker_file_name"
        const val STICKER_FILE_EMOJI_IN_QUERY = "sticker_emoji"
        const val STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY = "sticker_accessibility_text"

        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"

        private const val METADATA_CODE = 1
        private const val METADATA_CODE_FOR_SINGLE_PACK = 2
        private const val STICKERS_CODE = 3
        private const val STICKERS_ASSET_CODE = 4

        private val PACK_COLUMNS = arrayOf(
            STICKER_PACK_IDENTIFIER_IN_QUERY,
            STICKER_PACK_NAME_IN_QUERY,
            STICKER_PACK_PUBLISHER_IN_QUERY,
            STICKER_PACK_ICON_IN_QUERY,
            ANDROID_APP_DOWNLOAD_LINK_IN_QUERY,
            IOS_APP_DOWNLOAD_LINK_IN_QUERY,
            PUBLISHER_EMAIL,
            PUBLISHER_WEBSITE,
            PRIVACY_POLICY_WEBSITE,
            LICENSE_AGREEMENT_WEBSITE,
            IMAGE_DATA_VERSION,
            AVOID_CACHE,
            ANIMATED_STICKER_PACK,
        )

        private val STICKER_COLUMNS = arrayOf(
            STICKER_FILE_NAME_IN_QUERY,
            STICKER_FILE_EMOJI_IN_QUERY,
            STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY,
        )
    }
}
