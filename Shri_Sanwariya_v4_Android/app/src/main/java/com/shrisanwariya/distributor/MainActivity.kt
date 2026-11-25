package com.shrisanwariya.distributor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.shrisanwariya.distributor.model.*
import com.shrisanwariya.distributor.ui.DistributorViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val teal = Color(0xFF006C67)
            val scheme = lightColorScheme(
                primary = teal,
                secondary = Color(0xFF1559A8),
                tertiary = Color(0xFF9A5B18),
                background = Color(0xFFF6FAF9),
                surface = Color.White
            )
            MaterialTheme(colorScheme = scheme) {
                ShriSanwariyaRootScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShriSanwariyaRootScreen(vm: DistributorViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddProduct by remember { mutableStateOf(false) }

    val products by vm.repository.products.collectAsState()
    val companies by vm.repository.companies.collectAsState()
    val categories by vm.repository.categories.collectAsState()
    val schemes by vm.repository.schemes.collectAsState()
    val orders by vm.repository.orders.collectAsState()
    val cart by vm.cart.collectAsState()
    val isCloud by vm.repository.isCloudConnected.collectAsState()
    val syncStatus by vm.repository.syncStatusText.collectAsState()
    val toast by vm.toastMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearToast()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? -> uri?.let { vm.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.importBackup(it) } }

    val cartCount = cart.values.sum()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (selectedTab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showAddProduct = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add Product") },
                    containerColor = Color(0xFF006C67),
                    contentColor = Color.White
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Storefront, null) },
                    label = { Text("Catalog") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(badge = { if (cartCount > 0) Badge { Text("$cartCount") } }) {
                            Icon(Icons.Default.ShoppingCart, null)
                        }
                    },
                    label = { Text("Order") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.PictureAsPdf, null) },
                    label = { Text("Invoices") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.CloudSync, null) },
                    label = { Text("Sync & Backup") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> CatalogTab(
                    products = products,
                    companies = companies,
                    schemes = schemes,
                    cart = cart,
                    isCloud = isCloud,
                    syncStatus = syncStatus,
                    vm = vm
                )
                1 -> CartCheckoutTab(vm = vm, onOrderPlaced = { selectedTab = 2 })
                2 -> OrdersPdfTab(orders = orders, onDownloadPdf = { vm.downloadAndShareOrderPdf(it) })
                3 -> SyncBackupTab(
                    isCloud = isCloud,
                    syncStatus = syncStatus,
                    productCount = products.size,
                    orderCount = orders.size,
                    onExport = { exportLauncher.launch("ShriSanwariya_Backup_v4.zip") },
                    onImport = { importLauncher.launch(arrayOf("application/zip", "application/json", "*/*")) }
                )
            }
        }
    }

    if (showAddProduct) {
        AddProductDialog(
            companies = companies,
            categories = categories,
            schemes = schemes,
            vm = vm,
            onDismiss = { showAddProduct = false }
        )
    }
}

@Composable
fun CatalogTab(
    products: List<Product>,
    companies: List<Company>,
    schemes: List<Scheme>,
    cart: Map<String, Int>,
    isCloud: Boolean,
    syncStatus: String,
    vm: DistributorViewModel
) {
    var selectedCompanyId by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val schemeMap = remember(schemes) { schemes.associateBy { it.id } }

    val filtered = products.filter { p ->
        (selectedCompanyId == null || p.companyId == selectedCompanyId) &&
            (search.isBlank() || p.name.contains(search, true) || p.companyName.contains(search, true))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Illustrative Hero Banner
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF006C67), Color(0xFF0E8F85), Color(0xFF1559A8))
                        )
                    )
                    .padding(18.dp)
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawCircle(Color.White.copy(alpha = 0.08f), radius = 90f, center = Offset(size.width - 30f, 20f))
                    drawCircle(Color(0xFFFFD166).copy(alpha = 0.14f), radius = 55f, center = Offset(size.width - 90f, size.height))
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = if (isCloud) Color(0xFF10B981) else Color(0xFFF59E0B),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = if (isCloud) "● LIVE SYNC" else "● LOCAL MODE",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(syncStatus, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Shri Sanwariya Distributor", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Big brands. Bigger possibilities. Instant PDF billing.", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
                }
            }
        }

        // Search
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search products or brands...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Company Filter Chips
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedCompanyId == null,
                        onClick = { selectedCompanyId = null },
                        label = { Text("All Brands (${products.size})") }
                    )
                }
                items(companies) { c ->
                    FilterChip(
                        selected = selectedCompanyId == c.id,
                        onClick = { selectedCompanyId = if (selectedCompanyId == c.id) null else c.id },
                        label = { Text(c.name) }
                    )
                }
            }
        }

        // Product Cards
        items(filtered, key = { it.id }) { product ->
            val scheme = product.schemeId?.let { schemeMap[it] }
            val qtyInCart = cart[product.id] ?: 0
            val imgFile = vm.backupManager.resolveLocalImageFile(product.imageRelativePath)

            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Product Illustration / Photo Box
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFE6F4F1)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (imgFile != null || !product.imageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = imgFile ?: product.imageUrl,
                                contentDescription = product.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(Icons.Default.Inventory2, contentDescription = null, tint = Color(0xFF006C67), modifier = Modifier.size(34.dp))
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(product.companyName, color = Color(0xFF1559A8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(product.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2)
                        Text("Pack: ${product.packSize}", color = Color.Gray, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("₹%.2f".format(product.rate), color = Color(0xFF006C67), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("MRP ₹%.2f".format(product.mrp), color = Color.Gray, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough)
                        }
                        if (scheme != null) {
                            Spacer(Modifier.height(4.dp))
                            Surface(color = Color(0xFFFFF3E0), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    text = "★ ${scheme.shortBadge()}",
                                    color = Color(0xFF9A5B18),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (qtyInCart == 0) {
                            Button(
                                onClick = { vm.addToCart(product.id, 1) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("+ Add", fontSize = 12.sp)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { vm.addToCart(product.id, -1) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.RemoveCircleOutline, null, tint = Color(0xFFAE3256))
                                }
                                Text("$qtyInCart", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                                IconButton(onClick = { vm.addToCart(product.id, 1) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.AddCircleOutline, null, tint = Color(0xFF006C67))
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
fun CartCheckoutTab(vm: DistributorViewModel, onOrderPlaced: () -> Unit) {
    val cart by vm.cart.collectAsState()
    val lines = remember(cart) { vm.getCartLineItems() }
    var partyName by remember { mutableStateOf("") }
    var partyMobile by remember { mutableStateOf("") }
    var partyAddress by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Party Order & PDF Checkout", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF006C67))
        }
        if (lines.isEmpty()) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ShoppingBag, null, tint = Color(0xFF006C67), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Your order cart is empty", fontWeight = FontWeight.Bold)
                        Text("Add items from the Catalog to generate a Party Order PDF.", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(lines) { line ->
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(line.itemName, fontWeight = FontWeight.Bold)
                            Text("₹%.2f".format(line.netAmount), fontWeight = FontWeight.ExtraBold, color = Color(0xFF006C67))
                        }
                        Text("MRP ₹%.2f  |  Rate ₹%.2f  |  Qty: ${line.paidQty} (+${line.freeQty} FREE)".format(line.mrp, line.rate), fontSize = 12.sp, color = Color.DarkGray)
                        Text("Scheme: ${line.schemeLabel}  •  Savings: ₹%.2f".format(line.discountAmount), fontSize = 11.sp, color = Color(0xFF9A5B18), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item {
                OutlinedTextField(partyName, { partyName = it }, label = { Text("Party / Shop Name *") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(partyMobile, { partyMobile = it }, label = { Text("10-digit Mobile") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(partyAddress, { partyAddress = it }, label = { Text("Party Address / Route") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(notes, { notes = it }, label = { Text("Order Notes") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                val grand = lines.sumOf { it.netAmount }
                val saved = lines.sumOf { it.discountAmount }
                Button(
                    onClick = {
                        val created = vm.placeOrderAndGeneratePdf(partyName, partyMobile, partyAddress, notes)
                        if (created != null) onOrderPlaced()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Place Order & Generate PDF (₹%.2f | Save ₹%.0f)".format(grand, saved), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun OrdersPdfTab(orders: List<PartyOrder>, onDownloadPdf: (PartyOrder) -> Unit) {
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Party Orders & PDF Invoices", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF006C67))
        }
        if (orders.isEmpty()) {
            item { Text("No orders placed yet. Place an order to view & download its PDF invoice.", color = Color.Gray) }
        }
        items(orders, key = { it.id }) { order ->
            ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(order.partyName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("₹%.2f".format(order.grandTotal), fontWeight = FontWeight.ExtraBold, color = Color(0xFF006C67))
                    }
                    Text("${order.id} • ${fmt.format(Date(order.createdAt))}", fontSize = 11.sp, color = Color.Gray)
                    Text("${order.items.size} items • ${order.totalFreeUnits} FREE units • Discount ₹%.2f".format(order.totalDiscount), fontSize = 12.sp, color = Color(0xFF9A5B18))
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onDownloadPdf(order) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Download & Share PDF Invoice")
                    }
                }
            }
        }
    }
}

@Composable
fun SyncBackupTab(
    isCloud: Boolean,
    syncStatus: String,
    productCount: Int,
    orderCount: Int,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Real-Time Sync & Backup", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF006C67))

        ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Cloud Sync Status", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(syncStatus, color = if (isCloud) Color(0xFF059669) else Color(0xFFD97706), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Products: $productCount  •  Orders: $orderCount", fontSize = 13.sp, color = Color.Gray)
            }
        }

        ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Cross-Device Backup & Restore (v4 Fixed)", fontWeight = FontWeight.Bold)
                Text("Exports a checksum-verified .zip with UTF-8 data and portable product photos that imports cleanly on any phone or PC.", fontSize = 12.sp, color = Color.Gray)
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.UploadFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export Valid Backup (.zip)")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DownloadForOffline, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import Backup (.zip or .json)")
                }
            }
        }
    }
}

@Composable
fun AddProductDialog(
    companies: List<Company>,
    categories: List<Category>,
    schemes: List<Scheme>,
    vm: DistributorViewModel,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var packSize by remember { mutableStateOf("1 kg") }
    var mrp by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    var selectedCompany by remember { mutableStateOf(companies.firstOrNull()) }
    var selectedScheme by remember { mutableStateOf(schemes.firstOrNull()) }
    var pickedPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pickedPhotoUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Product", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Product Name *") }, singleLine = true)
                OutlinedTextField(packSize, { packSize = it }, label = { Text("Pack Size (e.g. 250 ml, 1 kg)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mrp, { mrp = it }, label = { Text("MRP ₹") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(rate, { rate = it }, label = { Text("Rate ₹") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Image, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (pickedPhotoUri != null) "Photo Selected ✓" else "Pick Product Photo")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val mrpVal = mrp.toDoubleOrNull() ?: 0.0
                val rateVal = rate.toDoubleOrNull() ?: mrpVal
                if (name.isNotBlank() && mrpVal > 0) {
                    val newId = "prod_${System.currentTimeMillis()}"
                    val savedImg = pickedPhotoUri?.let { vm.backupManager.saveProductImageFromUri(newId, it) }
                    val comp = selectedCompany ?: Company("comp_gen", "General")
                    val cat = categories.firstOrNull() ?: Category("cat_gen", "General")
                    vm.repository.upsertProduct(
                        Product(
                            id = newId,
                            name = name.trim(),
                            companyId = comp.id,
                            companyName = comp.name,
                            categoryId = cat.id,
                            categoryName = cat.name,
                            packSize = packSize.ifBlank { "1 unit" },
                            mrp = mrpVal,
                            rate = rateVal,
                            schemeId = selectedScheme?.id,
                            imageRelativePath = savedImg
                        )
                    )
                    onDismiss()
                }
            }) { Text("Save & Sync") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
