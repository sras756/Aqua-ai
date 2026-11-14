package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.WorkspaceService
import com.example.ui.AquaMainViewModel
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaAzure
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaDeepSurface
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMintSuccess
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal

@Composable
fun WorkspaceScreen(viewModel: AquaMainViewModel) {
    val context = LocalContext.current
    val activity = context.findWorkspaceActivity()

    val busy by viewModel.workspaceBusy.collectAsStateWithLifecycle()
    val status by viewModel.workspaceStatus.collectAsStateWithLifecycle()
    val consentHelpAccount by viewModel.workspaceConsentHelpAccount.collectAsStateWithLifecycle()
    val pendingIntent by viewModel.workspaceAuthPendingIntent.collectAsStateWithLifecycle()

    val contacts by viewModel.workspaceContacts.collectAsStateWithLifecycle()
    val events by viewModel.workspaceEvents.collectAsStateWithLifecycle()
    val emails by viewModel.workspaceEmails.collectAsStateWithLifecycle()
    val sheetCreated by viewModel.workspaceSheetCreated.collectAsStateWithLifecycle()
    val sheetRead by viewModel.workspaceSheetRead.collectAsStateWithLifecycle()
    val formInfo by viewModel.workspaceFormInfo.collectAsStateWithLifecycle()
    val formResponses by viewModel.workspaceFormResponses.collectAsStateWithLifecycle()
    val chatSpaces by viewModel.workspaceChatSpaces.collectAsStateWithLifecycle()
    val chatMessages by viewModel.workspaceChatMessages.collectAsStateWithLifecycle()

    var selectedService by rememberSaveable { mutableStateOf(WorkspaceService.GMAIL.name) }
    val activeService = WorkspaceService.entries.firstOrNull { it.name == selectedService }
        ?: WorkspaceService.GMAIL

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.handleWorkspaceConsentResult(result.data, result.resultCode)
    }

    LaunchedEffect(pendingIntent) {
        val pi = pendingIntent ?: return@LaunchedEffect
        viewModel.consumeWorkspaceAuthPendingIntent()
        consentLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AquaAbyss, AquaDeepSurface)))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GOOGLE WORKSPACE HUB",
                    style = MaterialTheme.typography.titleLarge,
                    color = AquaCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = status ?: "Contacts • Forms • Calendar • Gmail • Sheets • Chat",
                    style = MaterialTheme.typography.bodySmall,
                    color = AquaMutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.connectAllWorkspace(activity) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("workspace_connect_all_button")
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Connect All")
            }
        }

        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                color = AquaCyan,
                trackColor = AquaCardSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (consentHelpAccount != null) {
            Spacer(Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, com.example.ui.theme.AquaAmber, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Access Blocked (Error 403: access_denied)?",
                        style = MaterialTheme.typography.titleSmall,
                        color = com.example.ui.theme.AquaAmber,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Your OAuth consent screen in Google Cloud is in Testing mode, so only the developer account or approved Test Users can authorize Workspace scopes. Add '$consentHelpAccount' under Google Auth Platform → Audience → Test users (project gen-lang-client-0656700857), or choose your developer Google account when consenting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AquaIceText
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.openBrowserUrlExternally(
                                    "https://console.cloud.google.com/auth/audience?project=gen-lang-client-0656700857"
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Add Test User", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.dismissWorkspaceConsentHelp() }
                        ) {
                            Text("Dismiss", color = AquaMutedText)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(WorkspaceService.entries) { service ->
                val isSelected = activeService == service
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedService = service.name },
                    label = { Text(service.label) },
                    leadingIcon = {
                        Icon(
                            imageVector = iconForWorkspaceService(service),
                            contentDescription = service.label,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AquaAzure.copy(alpha = 0.35f),
                        selectedLabelColor = AquaCyan,
                        selectedLeadingIconColor = AquaCyan,
                        containerColor = AquaCardSurface,
                        labelColor = AquaIceText,
                        iconColor = AquaMutedText
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("workspace_tab_${service.name.lowercase()}")
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeService) {
                WorkspaceService.GMAIL -> GmailPanel(
                    busy = busy,
                    emails = emails,
                    onLoad = { q -> viewModel.loadGmailMessages(activity, q) },
                    onSend = { to, sub, body -> viewModel.sendGmailEmail(activity, to, sub, body) }
                )

                WorkspaceService.CALENDAR -> CalendarPanel(
                    busy = busy,
                    events = events,
                    onLoad = { viewModel.loadCalendarEvents(activity) },
                    onCreate = { sum, start, end, desc, loc ->
                        viewModel.createCalendarEvent(activity, sum, start, end, desc, loc)
                    }
                )

                WorkspaceService.CONTACTS -> ContactsPanel(
                    busy = busy,
                    contacts = contacts,
                    onLoad = { q -> viewModel.loadContacts(activity, q) },
                    onCreate = { name, email, phone -> viewModel.createContact(activity, name, email, phone) }
                )

                WorkspaceService.SHEETS -> SheetsPanel(
                    busy = busy,
                    createdSheet = sheetCreated,
                    readResult = sheetRead,
                    onCreateSheet = { title -> viewModel.createGoogleSheet(activity, title) },
                    onReadRange = { id, range -> viewModel.readGoogleSheet(activity, id, range) },
                    onAppendRow = { id, range, csv -> viewModel.appendGoogleSheetRow(activity, id, range, csv) }
                )

                WorkspaceService.FORMS -> FormsPanel(
                    busy = busy,
                    formInfo = formInfo,
                    responses = formResponses,
                    onCreateForm = { title, q -> viewModel.createGoogleForm(activity, title, q) },
                    onLoadForm = { formId -> viewModel.loadGoogleFormAndResponses(activity, formId) }
                )

                WorkspaceService.CHAT -> ChatPanel(
                    busy = busy,
                    spaces = chatSpaces,
                    messages = chatMessages,
                    onLoadSpaces = { viewModel.loadChatSpaces(activity) },
                    onLoadMessages = { space -> viewModel.loadChatMessages(activity, space) },
                    onSendMessage = { space, text -> viewModel.sendChatMessage(activity, space, text) }
                )
            }
        }
    }
}

@Composable
private fun GmailPanel(
    busy: Boolean,
    emails: List<com.example.data.repository.WorkspaceEmailMessage>,
    onLoad: (String) -> Unit,
    onSend: (String, String, String) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var toEmail by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var bodyText by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            WorkspaceCard(title = "Gmail Inbox") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search query (optional, e.g. is:unread)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onLoad(searchQuery) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("gmail_load_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Load Live Gmail Messages")
                }
            }
        }

        item {
            WorkspaceCard(title = "Compose & Send Email (Requires Confirmation)") {
                OutlinedTextField(
                    value = toEmail,
                    onValueChange = { toEmail = it },
                    label = { Text("Recipient Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Subject") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = bodyText,
                    onValueChange = { bodyText = it },
                    label = { Text("Message Body") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onSend(toEmail, subject, bodyText) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AquaTeal, contentColor = AquaAbyss),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("gmail_send_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Review & Send Email")
                }
            }
        }

        if (emails.isEmpty()) {
            item { EmptyWorkspaceHint("Tap 'Load Live Gmail Messages' to fetch emails from your Gmail account.") }
        } else {
            items(emails, key = { it.id }) { email ->
                WorkspaceDataCard(
                    heading = email.subject,
                    subheading = "From: ${email.from}",
                    body = email.snippet,
                    footer = email.date
                )
            }
        }
    }
}

@Composable
private fun CalendarPanel(
    busy: Boolean,
    events: List<com.example.data.repository.WorkspaceCalendarEvent>,
    onLoad: () -> Unit,
    onCreate: (String, String, String, String, String) -> Unit
) {
    var summary by rememberSaveable { mutableStateOf("") }
    var startIso by rememberSaveable { mutableStateOf("") }
    var endIso by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            WorkspaceCard(title = "Google Calendar Upcoming Events") {
                Button(
                    onClick = onLoad,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("calendar_load_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Load Upcoming Events")
                }
            }
        }

        item {
            WorkspaceCard(title = "Create Calendar Event") {
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Event Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = startIso,
                    onValueChange = { startIso = it },
                    label = { Text("Start ISO-8601 (e.g. 2026-11-16T09:00:00Z)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = endIso,
                    onValueChange = { endIso = it },
                    label = { Text("End ISO-8601 (e.g. 2026-11-16T10:00:00Z)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onCreate(summary, startIso, endIso, description, location) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AquaTeal, contentColor = AquaAbyss),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("calendar_create_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Create Calendar Event")
                }
            }
        }

        if (events.isEmpty()) {
            item { EmptyWorkspaceHint("Tap 'Load Upcoming Events' to view events from your Google Calendar.") }
        } else {
            items(events, key = { it.id }) { ev ->
                WorkspaceDataCard(
                    heading = ev.summary,
                    subheading = "${ev.startTime} -> ${ev.endTime}",
                    body = ev.description.ifBlank { ev.location },
                    footer = ev.location
                )
            }
        }
    }
}

@Composable
private fun ContactsPanel(
    busy: Boolean,
    contacts: List<com.example.data.repository.WorkspaceContact>,
    onLoad: (String) -> Unit,
    onCreate: (String, String, String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var fullName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            WorkspaceCard(title = "Google Contacts Directory") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search name, email, or phone (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onLoad(query) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("contacts_load_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Load Google Contacts")
                }
            }
        }

        item {
            WorkspaceCard(title = "Add Google Contact") {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Full Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onCreate(fullName, email, phone) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AquaTeal, contentColor = AquaAbyss),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("contacts_create_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save Contact")
                }
            }
        }

        if (contacts.isEmpty()) {
            item { EmptyWorkspaceHint("Tap 'Load Google Contacts' to browse or search your live Google Contacts.") }
        } else {
            items(contacts, key = { it.resourceName.ifBlank { it.displayName + it.email } }) { c ->
                WorkspaceDataCard(
                    heading = c.displayName,
                    subheading = c.organization.ifBlank { "Google Contact" },
                    body = listOfNotNull(
                        c.email.takeIf { it.isNotBlank() }?.let { "Email: $it" },
                        c.phone.takeIf { it.isNotBlank() }?.let { "Phone: $it" }
                    ).joinToString(" • ").ifBlank { "No phone or email stored" },
                    footer = ""
                )
            }
        }
    }
}

@Composable
private fun SheetsPanel(
    busy: Boolean,
    createdSheet: com.example.data.repository.WorkspaceSheetCreated?,
    readResult: com.example.data.repository.WorkspaceSheetReadResult?,
    onCreateSheet: (String) -> Unit,
    onReadRange: (String, String) -> Unit,
    onAppendRow: (String, String, String) -> Unit
) {
    var newTitle by rememberSaveable { mutableStateOf("") }
    var spreadsheetId by rememberSaveable { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf("Sheet1!A1:J25") }
    var rowCsv by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(createdSheet?.spreadsheetId) {
        val createdId = createdSheet?.spreadsheetId
        if (!createdId.isNullOrBlank() && spreadsheetId.isBlank()) {
            spreadsheetId = createdId
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            WorkspaceCard(title = "Create New Google Sheet") {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Spreadsheet Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onCreateSheet(newTitle) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AquaTeal, contentColor = AquaAbyss),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("sheets_create_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Create Spreadsheet")
                }
                createdSheet?.let { created ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Active ID: ${created.spreadsheetId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AquaMintSuccess
                    )
                }
            }
        }

        item {
            WorkspaceCard(title = "Read & Append Rows") {
                OutlinedTextField(
                    value = spreadsheetId,
                    onValueChange = { spreadsheetId = it },
                    label = { Text("Spreadsheet ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = range,
                    onValueChange = { range = it },
                    label = { Text("A1 Range (e.g. Sheet1!A1:J25)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = rowCsv,
                    onValueChange = { rowCsv = it },
                    label = { Text("Comma-separated row values (for Append)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onReadRange(spreadsheetId, range) },
                        enabled = !busy,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("sheets_read_button")
                    ) {
                        Text("Read Range")
                    }
                    Button(
                        onClick = { onAppendRow(spreadsheetId, range, rowCsv) },
                        enabled = !busy,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("sheets_append_button")
                    ) {
                        Text("Append Row")
                    }
                }
            }
        }

        if (readResult != null) {
            if (readResult.rows.isEmpty()) {
                item { EmptyWorkspaceHint("No cell values returned for ${readResult.range}.") }
            } else {
                items(readResult.rows) { row ->
                    WorkspaceDataCard(
                        heading = row.joinToString(" | ").ifBlank { "(Empty row)" },
                        subheading = readResult.range,
                        body = "",
                        footer = ""
                    )
                }
            }
        } else {
            item { EmptyWorkspaceHint("Create a spreadsheet or enter a Spreadsheet ID to read or append live data.") }
        }
    }
}

@Composable
private fun FormsPanel(
    busy: Boolean,
    formInfo: com.example.data.repository.WorkspaceFormInfo?,
    responses: List<com.example.data.repository.WorkspaceFormResponseItem>,
    onCreateForm: (String, String) -> Unit,
    onLoadForm: (String) -> Unit
) {
    var formTitle by rememberSaveable { mutableStateOf("") }
    var firstQuestion by rememberSaveable { mutableStateOf("") }
    var formIdInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(formInfo?.formId) {
        val id = formInfo?.formId
        if (!id.isNullOrBlank() && formIdInput.isBlank()) {
            formIdInput = id
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            WorkspaceCard(title = "Create Google Form") {
                OutlinedTextField(
                    value = formTitle,
                    onValueChange = { formTitle = it },
                    label = { Text("Form Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = firstQuestion,
                    onValueChange = { firstQuestion = it },
                    label = { Text("Initial Question (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onCreateForm(formTitle, firstQuestion) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AquaTeal, contentColor = AquaAbyss),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("forms_create_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Create Google Form")
                }
            }
        }

        item {
            WorkspaceCard(title = "Inspect Form & Responses") {
                OutlinedTextField(
                    value = formIdInput,
                    onValueChange = { formIdInput = it },
                    label = { Text("Google Form ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onLoadForm(formIdInput) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("forms_load_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Load Form Metadata & Responses")
                }
            }
        }

        formInfo?.let { info ->
            item {
                WorkspaceDataCard(
                    heading = info.title,
                    subheading = "Form ID: ${info.formId}",
                    body = buildString {
                        if (info.questionTitles.isNotEmpty()) {
                            append("Questions: ${info.questionTitles.joinToString(", ")}")
                        }
                        if (info.responderUri.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append("Share URL: ${info.responderUri}")
                        }
                    },
                    footer = "${responses.size} response(s)"
                )
            }
        }

        if (responses.isEmpty()) {
            item { EmptyWorkspaceHint("Create a Google Form or enter a Form ID above to view submitted responses.") }
        } else {
            items(responses, key = { it.responseId }) { resp ->
                WorkspaceDataCard(
                    heading = resp.respondentEmail.ifBlank { "Anonymous Respondent" },
                    subheading = resp.lastSubmittedTime,
                    body = resp.answersSummary,
                    footer = resp.responseId
                )
            }
        }
    }
}

@Composable
private fun ChatPanel(
    busy: Boolean,
    spaces: List<com.example.data.repository.WorkspaceChatSpace>,
    messages: List<com.example.data.repository.WorkspaceChatMessage>,
    onLoadSpaces: () -> Unit,
    onLoadMessages: (String) -> Unit,
    onSendMessage: (String, String) -> Unit
) {
    var selectedSpaceName by rememberSaveable { mutableStateOf("") }
    var messageText by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            WorkspaceCard(title = "Google Chat Spaces") {
                Button(
                    onClick = onLoadSpaces,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("chat_load_spaces_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Load Google Chat Spaces")
                }
            }
        }

        if (spaces.isNotEmpty()) {
            items(spaces, key = { it.name }) { space ->
                val isSelected = selectedSpaceName == space.name
                Surface(
                    color = if (isSelected) AquaAzure.copy(alpha = 0.28f) else AquaCardSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (isSelected) AquaCyan else AquaBorderGlow, RoundedCornerShape(14.dp))
                        .clickable {
                            selectedSpaceName = space.name
                            onLoadMessages(space.name)
                        }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(space.displayName, style = MaterialTheme.typography.titleSmall, color = AquaIceText)
                        Text("${space.name} • ${space.spaceType}", style = MaterialTheme.typography.labelSmall, color = AquaMutedText)
                    }
                }
            }
        }

        item {
            WorkspaceCard(title = "Post Message to Space (Requires Confirmation)") {
                OutlinedTextField(
                    value = selectedSpaceName,
                    onValueChange = { selectedSpaceName = it },
                    label = { Text("Space Resource Name (e.g. spaces/AAAA...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Message Text") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onLoadMessages(selectedSpaceName) },
                        enabled = !busy && selectedSpaceName.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                    ) {
                        Text("Refresh Chat")
                    }
                    Button(
                        onClick = { onSendMessage(selectedSpaceName, messageText) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = AquaTeal, contentColor = AquaAbyss),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("chat_send_message_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Send Message")
                    }
                }
            }
        }

        if (messages.isEmpty()) {
            item { EmptyWorkspaceHint("Tap 'Load Google Chat Spaces' and select a space to read or post messages.") }
        } else {
            items(messages, key = { it.name.ifBlank { "${it.senderName}_${it.createTime}" } }) { msg ->
                WorkspaceDataCard(
                    heading = msg.senderName,
                    subheading = msg.createTime,
                    body = msg.text,
                    footer = msg.name
                )
            }
        }
    }
}

@Composable
private fun WorkspaceCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AquaCyan,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun WorkspaceDataCard(
    heading: String,
    subheading: String,
    body: String,
    footer: String
) {
    Surface(
        color = AquaCardSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AquaBorderGlow, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = heading, style = MaterialTheme.typography.titleSmall, color = AquaIceText)
            if (subheading.isNotBlank()) {
                Text(text = subheading, style = MaterialTheme.typography.labelSmall, color = AquaTeal)
            }
            if (body.isNotBlank()) {
                Text(text = body, style = MaterialTheme.typography.bodyMedium, color = AquaIceText)
            }
            if (footer.isNotBlank()) {
                Text(text = footer, style = MaterialTheme.typography.labelSmall, color = AquaMutedText)
            }
        }
    }
}

@Composable
private fun EmptyWorkspaceHint(text: String) {
    Surface(
        color = AquaCardSurface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AquaMutedText,
            modifier = Modifier.padding(16.dp)
        )
    }
}

private fun iconForWorkspaceService(service: WorkspaceService): ImageVector = when (service) {
    WorkspaceService.GMAIL -> Icons.Filled.Email
    WorkspaceService.CALENDAR -> Icons.Filled.CalendarMonth
    WorkspaceService.CONTACTS -> Icons.Filled.Contacts
    WorkspaceService.SHEETS -> Icons.Filled.TableChart
    WorkspaceService.FORMS -> Icons.Filled.Description
    WorkspaceService.CHAT -> Icons.AutoMirrored.Filled.Chat
}

private tailrec fun Context.findWorkspaceActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findWorkspaceActivity()
    else -> null
}
