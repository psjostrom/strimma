package com.psjostrom.strimma.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

private const val CSV_FILENAME = "strimma_readings.csv"
private const val CSV_MIME = "text/csv"

fun shareCsv(context: Context, csv: String, chooserTitle: String) {
    val file = File(context.cacheDir, CSV_FILENAME)
    file.writeText(csv)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = CSV_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        chooserTitle
    ))
}
