// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class ByteArrayUploadProvider {
package fr.bonobo.dnsphere.network

import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import java.nio.ByteBuffer

/**
 * Provider de données POST pour Cronet (corps de la requête DNS)
 * Utilisé par DoH3 et DoQ
 */
class ByteArrayUploadProvider(private val data: ByteArray) : UploadDataProvider() {
    private var offset = 0

    override fun getLength() = data.size.toLong()

    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        val remaining = data.size - offset
        val toWrite = minOf(remaining, byteBuffer.remaining())
        byteBuffer.put(data, offset, toWrite)
        offset += toWrite
        uploadDataSink.onReadSucceeded(false)
    }

    override fun rewind(uploadDataSink: UploadDataSink) {
        offset = 0
        uploadDataSink.onRewindSucceeded()
    }
}