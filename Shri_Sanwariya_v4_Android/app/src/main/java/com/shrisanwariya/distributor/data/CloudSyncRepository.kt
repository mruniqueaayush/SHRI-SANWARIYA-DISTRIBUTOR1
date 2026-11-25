package com.shrisanwariya.distributor.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.shrisanwariya.distributor.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Real-Time Multi-Device Repository:
 * - Automatically syncs Products, Companies, Categories, Schemes, and Orders via Firebase Firestore
 *   snapshot listeners so any product added on Phone A appears instantly on Phone B.
 * - Keeps an always-available local JSON cache so the app works smoothly offline and before
 *   google-services.json is linked.
 */
class CloudSyncRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val localCacheFile = File(context.filesDir, "local_store_v4.json")

    private val _isCloudConnected = MutableStateFlow(false)
    val isCloudConnected: StateFlow<Boolean> = _isCloudConnected.asStateFlow()

    private val _syncStatusText = MutableStateFlow("Local Offline Mode")
    val syncStatusText: StateFlow<String> = _syncStatusText.asStateFlow()

    private val _settings = MutableStateFlow(StoreSettings())
    val settings: StateFlow<StoreSettings> = _settings.asStateFlow()

    private val _companies = MutableStateFlow<List<Company>>(emptyList())
    val companies: StateFlow<List<Company>> = _companies.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _schemes = MutableStateFlow<List<Scheme>>(emptyList())
    val schemes: StateFlow<List<Scheme>> = _schemes.asStateFlow()

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _orders = MutableStateFlow<List<PartyOrder>>(emptyList())
    val orders: StateFlow<List<PartyOrder>> = _orders.asStateFlow()

    private val listeners = mutableListOf<ListenerRegistration>()
    private var db: FirebaseFirestore? = null

    init {
        loadLocalCacheOrSeed()
        startCloudSyncIfConfigured()
    }

    private fun startCloudSyncIfConfigured() {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                _isCloudConnected.value = false
                _syncStatusText.value = "Local Mode (Add google-services.json for Live Sync)"
                return
            }
            val firestore = FirebaseFirestore.getInstance()
            db = firestore
            _isCloudConnected.value = true
            _syncStatusText.value = "Live Cloud Sync Active"

            listeners += firestore.collection("companies").addSnapshotListener { snap, err ->
                if (err == null && snap != null) {
                    val list = snap.documents.mapNotNull { it.toObject(Company::class.java) }
                    if (list.isNotEmpty()) {
                        _companies.value = list.sortedBy { it.name }
                        persistLocalCache()
                    }
                }
            }
            listeners += firestore.collection("categories").addSnapshotListener { snap, err ->
                if (err == null && snap != null) {
                    val list = snap.documents.mapNotNull { it.toObject(Category::class.java) }
                    if (list.isNotEmpty()) {
                        _categories.value = list.sortedBy { it.name }
                        persistLocalCache()
                    }
                }
            }
            listeners += firestore.collection("schemes").addSnapshotListener { snap, err ->
                if (err == null && snap != null) {
                    val list = snap.documents.mapNotNull { it.toObject(Scheme::class.java) }
                    if (list.isNotEmpty()) {
                        _schemes.value = list.sortedByDescending { it.updatedAt }
                        persistLocalCache()
                    }
                }
            }
            listeners += firestore.collection("products").addSnapshotListener { snap, err ->
                if (err == null && snap != null) {
                    val list = snap.documents.mapNotNull { it.toObject(Product::class.java) }
                    if (list.isNotEmpty()) {
                        _products.value = list.sortedByDescending { it.updatedAt }
                        persistLocalCache()
                    }
                }
            }
            listeners += firestore.collection("orders").addSnapshotListener { snap, err ->
                if (err == null && snap != null) {
                    val list = snap.documents.mapNotNull { it.toObject(PartyOrder::class.java) }
                    if (list.isNotEmpty()) {
                        _orders.value = list.sortedByDescending { it.createdAt }
                        persistLocalCache()
                    }
                }
            }
        } catch (e: Exception) {
            _isCloudConnected.value = false
            _syncStatusText.value = "Local Mode (${e.localizedMessage ?: "Offline"})"
        }
    }

    fun upsertProduct(product: Product) {
        val updated = product.copy(updatedAt = System.currentTimeMillis())
        _products.value = (_products.value.filterNot { it.id == updated.id } + updated)
            .sortedByDescending { it.updatedAt }
        persistLocalCache()
        db?.collection("products")?.document(updated.id)?.set(updated, SetOptions.merge())
    }

    fun deleteProduct(productId: String) {
        _products.value = _products.value.filterNot { it.id == productId }
        persistLocalCache()
        db?.collection("products")?.document(productId)?.delete()
    }

    fun upsertCompany(company: Company) {
        val updated = company.copy(updatedAt = System.currentTimeMillis())
        _companies.value = (_companies.value.filterNot { it.id == updated.id } + updated)
            .sortedBy { it.name }
        persistLocalCache()
        db?.collection("companies")?.document(updated.id)?.set(updated, SetOptions.merge())
    }

    fun upsertCategory(category: Category) {
        val updated = category.copy(updatedAt = System.currentTimeMillis())
        _categories.value = (_categories.value.filterNot { it.id == updated.id } + updated)
            .sortedBy { it.name }
        persistLocalCache()
        db?.collection("categories")?.document(updated.id)?.set(updated, SetOptions.merge())
    }

    fun upsertScheme(scheme: Scheme) {
        val updated = scheme.copy(updatedAt = System.currentTimeMillis())
        _schemes.value = (_schemes.value.filterNot { it.id == updated.id } + updated)
            .sortedByDescending { it.updatedAt }
        persistLocalCache()
        db?.collection("schemes")?.document(updated.id)?.set(updated, SetOptions.merge())
    }

    fun addOrder(order: PartyOrder) {
        _orders.value = listOf(order) + _orders.value
        persistLocalCache()
        db?.collection("orders")?.document(order.id)?.set(order, SetOptions.merge())
    }

    fun snapshotBackupPayload(): AppBackupPayload = AppBackupPayload(
        schemaVersion = 4,
        exportedAt = System.currentTimeMillis(),
        settings = _settings.value,
        companies = _companies.value,
        categories = _categories.value,
        schemes = _schemes.value,
        products = _products.value,
        orders = _orders.value
    )

    fun restoreFromPayload(payload: AppBackupPayload) {
        _settings.value = payload.settings
        _companies.value = payload.companies
        _categories.value = payload.categories
        _schemes.value = payload.schemes
        _products.value = payload.products
        _orders.value = payload.orders
        persistLocalCache()

        // Push restored records to cloud so other devices update too
        payload.companies.forEach { db?.collection("companies")?.document(it.id)?.set(it, SetOptions.merge()) }
        payload.categories.forEach { db?.collection("categories")?.document(it.id)?.set(it, SetOptions.merge()) }
        payload.schemes.forEach { db?.collection("schemes")?.document(it.id)?.set(it, SetOptions.merge()) }
        payload.products.forEach { db?.collection("products")?.document(it.id)?.set(it, SetOptions.merge()) }
        payload.orders.forEach { db?.collection("orders")?.document(it.id)?.set(it, SetOptions.merge()) }
    }

    private fun persistLocalCache() {
        runCatching {
            localCacheFile.writeText(json.encodeToString(snapshotBackupPayload()), Charsets.UTF_8)
        }
    }

    private fun loadLocalCacheOrSeed() {
        if (localCacheFile.exists()) {
            val loaded = runCatching {
                json.decodeFromString<AppBackupPayload>(localCacheFile.readText(Charsets.UTF_8))
            }.getOrNull()
            if (loaded != null) {
                _settings.value = loaded.settings
                _companies.value = loaded.companies
                _categories.value = loaded.categories
                _schemes.value = loaded.schemes
                _products.value = loaded.products
                _orders.value = loaded.orders
                return
            }
        }
        // Seed initial illustrative catalog matching Shri Sanwariya Distributor brands
        val c1 = Company("comp_hul", "Unilever (HUL)", "#1559A8", "Personal & Home Care")
        val c2 = Company("comp_pg", "P&G India", "#5B3CC4", "Baby & Fabric Care")
        val c3 = Company("comp_dettol", "Dettol / Reckitt", "#006C67", "Hygiene & Antiseptic")
        val c4 = Company("comp_itc", "ITC FMCG", "#9A5B18", "Staples & Personal Care")

        val cat1 = Category("cat_baby", "Baby Care", "baby")
        val cat2 = Category("cat_hygiene", "Hygiene & Bath", "soap")
        val cat3 = Category("cat_home", "Home & Detergent", "home")

        val s1 = Scheme("sch_10_2", "Buy 10 Get 2 FREE", SchemeType.FREE_UNITS, minPaidUnits = 10, freeUnits = 2)
        val s2 = Scheme("sch_8pct", "8% Extra Scheme Off MRP", SchemeType.PERCENT_OFF_MRP, discountPercent = 8.0)
        val s3 = Scheme("sch_flat10", "₹10 Flat Off per Unit", SchemeType.FLAT_PER_UNIT, flatDiscountPerUnit = 10.0)

        val p1 = Product("prod_1", "Dettol Antiseptic Liquid", c3.id, c3.name, cat2.id, cat2.name, "250 ml", mrp = 155.0, rate = 132.0, schemeId = s1.id)
        val p2 = Product("prod_2", "Pampers Baby Dry Pants", c2.id, c2.name, cat1.id, cat1.name, "1 kg", mrp = 549.0, rate = 470.0, schemeId = s2.id)
        val p3 = Product("prod_3", "Surf Excel Quick Wash", c1.id, c1.name, cat3.id, cat3.name, "1 kg", mrp = 235.0, rate = 204.0, schemeId = s3.id)
        val p4 = Product("prod_4", "Clinic Plus Strong Shampoo", c1.id, c1.name, cat2.id, cat2.name, "180 ml", mrp = 128.0, rate = 108.0, schemeId = s1.id)

        _companies.value = listOf(c1, c2, c3, c4)
        _categories.value = listOf(cat1, cat2, cat3)
        _schemes.value = listOf(s1, s2, s3)
        _products.value = listOf(p1, p2, p3, p4)
        persistLocalCache()
    }
}
