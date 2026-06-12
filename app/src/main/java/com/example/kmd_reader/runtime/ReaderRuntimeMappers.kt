package com.example.kmd_reader.runtime

import com.example.kmd_reader.domain.model.WorkAssetManifest

fun WorkAssetManifest.toReaderRuntimeAssetManifest(): ReaderRuntimeAssetManifest =
    ReaderRuntimeAssetManifest(
        baseUrl = baseUrl,
        assets = assets.mapValues { (_, asset) ->
            ReaderRuntimeAssetRef(
                url = asset.url,
                type = asset.type
            )
        }
    )
