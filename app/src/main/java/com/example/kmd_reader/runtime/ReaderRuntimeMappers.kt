package com.example.kmd_reader.runtime

import com.example.kmd_reader.domain.model.WorkAssetManifest
import com.example.kmd_reader.domain.model.WorkFontAsset

fun WorkAssetManifest.toReaderRuntimeAssetManifest(): ReaderRuntimeAssetManifest =
    ReaderRuntimeAssetManifest(
        baseUrl = baseUrl,
        fonts = fonts.map { it.toReader() },
        assets = assets.mapValues { (_, asset) ->
            ReaderRuntimeAssetRef(
                url = asset.url,
                type = asset.type
            )
        }
    )

// R3-D4：字体透传。domain WorkFontAsset → runtime ReaderRuntimeFontAsset，字段一一对应。
private fun WorkFontAsset.toReader(): ReaderRuntimeFontAsset =
    ReaderRuntimeFontAsset(
        family = family,
        url = url,
        weight = weight,
        style = style
    )
