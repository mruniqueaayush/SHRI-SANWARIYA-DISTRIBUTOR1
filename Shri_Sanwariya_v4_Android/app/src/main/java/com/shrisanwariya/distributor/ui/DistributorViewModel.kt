package com.shrisanwariya.distributor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shrisanwariya.distributor.data.BackupManager
import com.shrisanwariya.distributor.data.CloudSyncRepository
import com.shrisanwariya.distributor.model.*
import com.shrisanwariya.distributor.pdf.OrderPdfGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class DistributorViewModel(application: Application) : AndroidViewModel(application) {
    val repository = CloudSyncRepository(application)
    val backupManager = BackupManager(application)
    val pdfGenerator = OrderPdfGenerator(application)

    private val _cart = MutableStateFlow<Map<String, Int>>(emptyMap())
    val cart: StateFlow<Map<String, Int>> = _cart.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _lastGeneratedPdf = MutableStateFlow<File?>(null)
    val lastGeneratedPdf: StateFlow<File?> = _lastGeneratedPdf.asStateFlow()

    fun clearToast() { _toastMessage.value = null }

    fun addToCart(productId: String, delta: Int = 1) {
        val current = _cart.value.toMutableMap()
        val next = (current[productId] ?: 0) + delta
        if (next <= 0) current.remove(productId) else current[productId] = next
        _cart.value = current
    }

    fun getCartLineItems(): List<OrderLineItem> {
        val productMap = repository.products.value.associateBy { it.id }
        val schemeMap = repository.schemes.value.associateBy { it.id }
        return _cart.value.mapNotNull { (pid, qty) ->
            val p = productMap[pid] ?: return@mapNotNull null
            val s = p.schemeId?.let { schemeMap[it] }
            PricingCalculator.buildLineItem(p, qty, s)
        }
    }

    fun placeOrderAndGeneratePdf(partyName: String, partyMobile: String, partyAddress: String, notes: String): PartyOrder? {
        val lines = getCartLineItems()
        if (lines.isEmpty()) {
            _toastMessage.value = "Cart is empty. Add products first."
            return null
        }
        val order = PartyOrder(
            partyName = partyName.ifBlank { "Cash Party" },
            partyMobile = partyMobile,
            partyAddress = partyAddress,
            notes = notes,
            items = lines,
            grossMrpTotal = lines.sumOf { it.mrp * (it.paidQty + it.freeQty) },
            totalDiscount = lines.sumOf { it.discountAmount },
            totalFreeUnits = lines.sumOf { it.freeQty },
            grandTotal = lines.sumOf { it.netAmount }
        )
        repository.addOrder(order)
        _cart.value = emptyMap()

        val pdfOut = pdfGenerator.generateOrderPdf(order, repository.settings.value)
        _lastGeneratedPdf.value = pdfOut.internalFile
        _toastMessage.value = "Order ${order.id} placed! ${pdfOut.savedToDownloadsMessage}"
        return order
    }

    fun downloadAndShareOrderPdf(order: PartyOrder) {
        val pdfOut = pdfGenerator.generateOrderPdf(order, repository.settings.value)
        _lastGeneratedPdf.value = pdfOut.internalFile
        _toastMessage.value = pdfOut.savedToDownloadsMessage
        pdfGenerator.sharePdf(pdfOut.internalFile)
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val res = backupManager.exportBackupToUri(uri, repository.snapshotBackupPayload())
            _toastMessage.value = res.message
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val res = backupManager.importBackupFromUri(uri)
            if (res.success && res.payload != null) {
                repository.restoreFromPayload(res.payload)
            }
            _toastMessage.value = res.message
        }
    }
}
