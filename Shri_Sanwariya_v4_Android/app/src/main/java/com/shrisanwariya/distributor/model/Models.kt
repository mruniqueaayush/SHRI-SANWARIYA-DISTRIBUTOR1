package com.shrisanwariya.distributor.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Company(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val brandColorHex: String = "#006C67",
    val tagline: String = "",
    val hidden: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class Category(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val iconKey: String = "box",
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
enum class SchemeType {
    FREE_UNITS,      // e.g. Buy 10, get 2 FREE
    PERCENT_OFF_MRP, // e.g. 12% OFF MRP
    FLAT_PER_UNIT    // e.g. Rs. 5 off per unit
}

@Serializable
data class Scheme(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val type: SchemeType = SchemeType.FREE_UNITS,
    val minPaidUnits: Int = 10,
    val freeUnits: Int = 1,
    val discountPercent: Double = 0.0,
    val flatDiscountPerUnit: Double = 0.0,
    val active: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun shortBadge(): String = when (type) {
        SchemeType.FREE_UNITS -> "Buy $minPaidUnits + $freeUnits FREE"
        SchemeType.PERCENT_OFF_MRP -> "${String.format("%.0f", discountPercent)}% OFF MRP"
        SchemeType.FLAT_PER_UNIT -> "₹${String.format("%.0f", flatDiscountPerUnit)} OFF / unit"
    }
}

@Serializable
data class Product(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val companyId: String = "",
    val companyName: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    val packSize: String = "1 kg",
    val mrp: Double = 0.0,
    val rate: Double = 0.0,
    val schemeId: String? = null,
    val imageRelativePath: String? = null, // Portable relative path e.g. "images/<id>.jpg"
    val imageUrl: String? = null,          // Optional cloud URL for multi-device photo sync
    val available: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class OrderLineItem(
    val productId: String = "",
    val itemName: String = "",
    val companyName: String = "",
    val packSize: String = "",
    val mrp: Double = 0.0,
    val rate: Double = 0.0,
    val paidQty: Int = 1,
    val freeQty: Int = 0,
    val schemeLabel: String = "—",
    val discountAmount: Double = 0.0,
    val netAmount: Double = 0.0
)

@Serializable
data class PartyOrder(
    val id: String = "ORD-" + (100000..999999).random(),
    val partyName: String = "",
    val partyMobile: String = "",
    val partyAddress: String = "",
    val notes: String = "",
    val status: String = "Accepted",
    val createdAt: Long = System.currentTimeMillis(),
    val items: List<OrderLineItem> = emptyList(),
    val grossMrpTotal: Double = 0.0,
    val totalDiscount: Double = 0.0,
    val totalFreeUnits: Int = 0,
    val grandTotal: Double = 0.0
)

@Serializable
data class StoreSettings(
    val distributorName: String = "Shri Sanwariya Distributor",
    val tagline: String = "Big brands. Bigger possibilities.",
    val announcement: String = "Festive schemes active — automatic free units & discounts in cart!",
    val primaryColorHex: String = "#006C67",
    val distributorPhone: String = "",
    val distributorAddress: String = ""
)

@Serializable
data class AppBackupPayload(
    val schemaVersion: Int = 4,
    val exportedAt: Long = System.currentTimeMillis(),
    val appName: String = "Shri Sanwariya Distributor",
    val settings: StoreSettings = StoreSettings(),
    val companies: List<Company> = emptyList(),
    val categories: List<Category> = emptyList(),
    val schemes: List<Scheme> = emptyList(),
    val products: List<Product> = emptyList(),
    val orders: List<PartyOrder> = emptyList(),
    val embeddedImagesBase64: Map<String, String> = emptyMap()
)

@Serializable
data class BackupManifest(
    val schemaVersion: Int = 4,
    val exportedAt: Long = System.currentTimeMillis(),
    val sha256BackupJson: String = "",
    val productCount: Int = 0,
    val orderCount: Int = 0,
    val imageCount: Int = 0
)

object PricingCalculator {
    fun buildLineItem(product: Product, paidQty: Int, scheme: Scheme?): OrderLineItem {
        val safeQty = paidQty.coerceAtLeast(1)
        val baseAmount = product.rate * safeQty
        var freeQty = 0
        var schemeDiscount = 0.0
        var schemeLabel = "—"

        if (scheme != null && scheme.active) {
            when (scheme.type) {
                SchemeType.FREE_UNITS -> {
                    if (scheme.minPaidUnits > 0 && safeQty >= scheme.minPaidUnits) {
                        freeQty = (safeQty / scheme.minPaidUnits) * scheme.freeUnits
                        schemeLabel = "${scheme.shortBadge()} ($freeQty FREE)"
                    } else {
                        schemeLabel = scheme.shortBadge()
                    }
                }
                SchemeType.PERCENT_OFF_MRP -> {
                    schemeDiscount = (product.mrp * (scheme.discountPercent / 100.0)) * safeQty
                    schemeLabel = scheme.shortBadge()
                }
                SchemeType.FLAT_PER_UNIT -> {
                    schemeDiscount = scheme.flatDiscountPerUnit * safeQty
                    schemeLabel = scheme.shortBadge()
                }
            }
        }

        val tradeDiscount = ((product.mrp - product.rate).coerceAtLeast(0.0)) * safeQty
        val freeValue = product.rate * freeQty
        val netAmount = (baseAmount - schemeDiscount).coerceAtLeast(0.0)
        val totalDiscount = tradeDiscount + schemeDiscount + freeValue

        return OrderLineItem(
            productId = product.id,
            itemName = product.name,
            companyName = product.companyName,
            packSize = product.packSize,
            mrp = product.mrp,
            rate = product.rate,
            paidQty = safeQty,
            freeQty = freeQty,
            schemeLabel = schemeLabel,
            discountAmount = totalDiscount,
            netAmount = netAmount
        )
    }
}
