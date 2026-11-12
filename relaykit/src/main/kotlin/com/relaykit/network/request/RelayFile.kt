package com.relaykit.network.request

import android.net.Uri
import java.io.File

/** A file attachment for a multipart request. Provide either [file] or [uri]. */
public data class RelayFile(
    /** Multipart field name. */
    public val name: String,
    /** File on disk, when uploading a file path. */
    public val file: File? = null,
    /** Content URI, when uploading a provider-backed file. */
    public val uri: Uri? = null,
    /** Uploaded file name. */
    public val fileName: String = file?.name ?: "upload",
    /** MIME type of the uploaded data. */
    public val mimeType: String = "application/octet-stream",
) {
    init {
        require(name.isNotBlank()) { "Multipart field name must not be blank" }
        require((file == null) xor (uri == null)) { "Provide exactly one of file or uri" }
    }
}
