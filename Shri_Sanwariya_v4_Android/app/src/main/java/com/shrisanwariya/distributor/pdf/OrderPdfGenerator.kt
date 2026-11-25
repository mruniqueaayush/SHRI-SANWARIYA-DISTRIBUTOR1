package com.shrisanwariya.distributor.pdf

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.shrisanwariya.distributor.model.PartyOrder
import com.shrisanwariya.distributor.model.StoreSettings
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates an A4 PDF invoice for any Party Order containing:
 * Item Name, Pack Size, MRP, Rate, Quantity, Scheme (Free Units / % Off), Discount, Net Amount,
 * Savings Breakdown, and Grand Total.
 */
class OrderPdfGenerator(private val context: Context) {

    data class PdfOutput(
        val internalFile: File,
        val savedToDownloadsMessage: String
    )

    fun generateOrderPdf(order: PartyOrder, settings: StoreSettings): PdfOutput {
        val pageWidth = 595   // A4 width in PostScript points
        val pageHeight = 842  // A4 height in PostScript points
        val margin = 32f

        val pdf = PdfDocument()
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
        var page = pdf.startPage(pageInfo)
        var canvas = page.canvas

        val tealPrimary = Color.parseColor("#006C67")
        val darkInk = Color.parseColor("#111827")
        val mutedInk = Color.parseColor("#4B5563")
        val lightSurface = Color.parseColor("#F0FDF9")
        val borderGray = Color.parseColor("#D1D5DB")
        val accentAmber = Color.parseColor("#9A5B18")

        val headerBgPaint = Paint().apply { color = tealPrimary }
        val cardBgPaint = Paint().apply { color = lightSurface }
        val altRowPaint = Paint().apply { color = Color.parseColor("#F9FAFB") }
        val linePaint = Paint().apply { color = borderGray; strokeWidth = 0.8f }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCFBF1"); textSize = 10f
        }
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkInk; textSize = 10f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkInk; textSize = 9.5f
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mutedInk; textSize = 9f
        }
        val tableHeadText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 8.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val schemePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentAmber; textSize = 8.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val dateFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

        // 1. Header Banner
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 84f, headerBgPaint)
        canvas.drawText(settings.distributorName.uppercase(), margin, 36f, titlePaint)
        canvas.drawText(settings.tagline, margin, 54f, subHeaderPaint)
        canvas.drawText("ORDER INVOICE / ESTIMATE", pageWidth - margin - 145f, 36f, boldPaint.apply { color = Color.WHITE })
        canvas.drawText("Order ID: ${order.id}", pageWidth - margin - 145f, 52f, subHeaderPaint)
        canvas.drawText(dateFmt.format(Date(order.createdAt)), pageWidth - margin - 145f, 66f, subHeaderPaint)
        boldPaint.color = darkInk

        // 2. Party Details Card
        var y = 102f
        val cardRect = RectF(margin, y, pageWidth - margin, y + 66f)
        canvas.drawRoundRect(cardRect, 8f, 8f, cardBgPaint)
        canvas.drawText("PARTY DETAILS", margin + 12f, y + 18f, boldPaint)
        canvas.drawText("Party Name: ${order.partyName.ifBlank { "Walk-in Party" }}", margin + 12f, y + 34f, bodyPaint)
        canvas.drawText("Mobile: ${order.partyMobile.ifBlank { "—" }}", margin + 12f, y + 48f, bodyPaint)
        canvas.drawText("Status: ${order.status}", pageWidth - margin - 140f, y + 34f, boldPaint)
        if (order.partyAddress.isNotBlank()) {
            canvas.drawText("Address: ${order.partyAddress.take(45)}", pageWidth / 2f - 20f, y + 48f, mutedPaint)
        }

        // 3. Table Header
        y += 84f
        fun drawTableHeader(c: Canvas, topY: Float): Float {
            c.drawRect(margin, topY, pageWidth - margin, topY + 24f, headerBgPaint)
            c.drawText("#", margin + 6f, topY + 15f, tableHeadText)
            c.drawText("ITEM NAME", margin + 24f, topY + 15f, tableHeadText)
            c.drawText("MRP", margin + 185f, topY + 15f, tableHeadText)
            c.drawText("RATE", margin + 230f, topY + 15f, tableHeadText)
            c.drawText("QTY", margin + 275f, topY + 15f, tableHeadText)
            c.drawText("SCHEME", margin + 310f, topY + 15f, tableHeadText)
            c.drawText("DISC.", margin + 412f, topY + 15f, tableHeadText)
            c.drawText("NET AMT", margin + 468f, topY + 15f, tableHeadText)
            return topY + 24f
        }

        y = drawTableHeader(canvas, y)

        // 4. Itemized Rows
        order.items.forEachIndexed { idx, item ->
            if (y > pageHeight - 140f) {
                pdf.finishPage(page)
                pageNum += 1
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                page = pdf.startPage(pageInfo)
                canvas = page.canvas
                y = drawTableHeader(canvas, 40f)
            }

            val rowHeight = 30f
            if (idx % 2 == 1) {
                canvas.drawRect(margin, y, pageWidth - margin, y + rowHeight, altRowPaint)
            }

            canvas.drawText("${idx + 1}", margin + 6f, y + 14f, bodyPaint)
            canvas.drawText(item.itemName.take(28), margin + 24f, y + 13f, boldPaint)
            canvas.drawText("${item.companyName} • ${item.packSize}".take(30), margin + 24f, y + 24f, mutedPaint)
            canvas.drawText("₹%.2f".format(item.mrp), margin + 185f, y + 16f, bodyPaint)
            canvas.drawText("₹%.2f".format(item.rate), margin + 230f, y + 16f, bodyPaint)
            val qtyStr = if (item.freeQty > 0) "${item.paidQty}+${item.freeQty}" else "${item.paidQty}"
            canvas.drawText(qtyStr, margin + 275f, y + 16f, boldPaint)
            canvas.drawText(item.schemeLabel.take(18), margin + 310f, y + 16f, schemePaint)
            canvas.drawText("₹%.2f".format(item.discountAmount), margin + 412f, y + 16f, bodyPaint)
            canvas.drawText("₹%.2f".format(item.netAmount), margin + 468f, y + 16f, boldPaint)

            y += rowHeight
            canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
        }

        // 5. Totals Summary Box
        y += 18f
        val boxLeft = pageWidth - margin - 235f
        val summaryRect = RectF(boxLeft, y, pageWidth - margin, y + 96f)
        canvas.drawRoundRect(summaryRect, 8f, 8f, cardBgPaint)

        canvas.drawText("Gross MRP Value:", boxLeft + 12f, y + 20f, bodyPaint)
        canvas.drawText("₹%.2f".format(order.grossMrpTotal), pageWidth - margin - 78f, y + 20f, bodyPaint)

        canvas.drawText("Total Free Units:", boxLeft + 12f, y + 38f, bodyPaint)
        canvas.drawText("${order.totalFreeUnits} units", pageWidth - margin - 78f, y + 38f, schemePaint)

        canvas.drawText("Total Scheme & Trade Disc.:", boxLeft + 12f, y + 56f, bodyPaint)
        canvas.drawText("- ₹%.2f".format(order.totalDiscount), pageWidth - margin - 78f, y + 56f, schemePaint)

        canvas.drawLine(boxLeft + 10f, y + 66f, pageWidth - margin - 10f, y + 66f, linePaint)

        val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tealPrimary; textSize = 12.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("NET PAYABLE:", boxLeft + 12f, y + 84f, totalPaint)
        canvas.drawText("₹%.2f".format(order.grandTotal), pageWidth - margin - 86f, y + 84f, totalPaint)

        // Footer
        canvas.drawText(
            "Generated by ${settings.distributorName} • Thank you for your business!",
            margin,
            pageHeight - 24f,
            mutedPaint
        )

        pdf.finishPage(page)

        val safeParty = order.partyName.replace(Regex("[^a-zA-Z0-9]+"), "_").ifBlank { "Party" }
        val fileName = "Invoice_${order.id}_${safeParty}.pdf"

        val invoicesDir = File(context.filesDir, "invoices").apply { mkdirs() }
        val internalFile = File(invoicesDir, fileName)
        FileOutputStream(internalFile).use { pdf.writeTo(it) }
        pdf.close()

        // Also save a copy into public Downloads/ShriSanwariya_Invoices on Android 10+
        val dlMsg = saveCopyToDownloads(internalFile, fileName)
        return PdfOutput(internalFile, dlMsg)
    }

    private fun saveCopyToDownloads(source: File, fileName: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ShriSanwariya_Invoices")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    }
                    "Saved to Downloads/ShriSanwariya_Invoices/$fileName"
                } else {
                    "Saved internally: $fileName"
                }
            } else {
                "Saved: ${source.name}"
            }
        } catch (e: Exception) {
            "Saved internally (ready to share): $fileName"
        }
    }

    fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Order Invoice - ${file.nameWithoutExtension}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share Order PDF Invoice").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
