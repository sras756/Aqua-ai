package com.example.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.UserSettings
import com.example.domain.AgeTier
import com.example.domain.UserGender
import com.example.domain.UserProfilePolicy
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.AquaProfileTierPill
import com.example.ui.theme.AquaTheme
import com.google.firebase.auth.FirebaseUser

@Composable
fun AccountSignOutCard(
    currentUser: FirebaseUser?,
    onSignOut: () -> Unit
) {
    val c = AquaTheme.colors
    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentUser?.displayName ?: "Authenticated Operator",
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary
                )
                Text(
                    text = currentUser?.email ?: (currentUser?.uid ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted
                )
            }
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = c.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Sign Out", color = c.error)
            }
        }
    }
}

@Composable
fun ProfileAgeAndGenderCard(
    settings: UserSettings,
    onSaveProfile: (ageYears: Int, genderId: String) -> Unit
) {
    val c = AquaTheme.colors
    var ageInput by remember(settings.ageYears) {
        mutableStateOf(if (settings.ageYears > 0) settings.ageYears.toString() else "")
    }
    var selectedGenderId by remember(settings.gender) {
        mutableStateOf(UserGender.fromId(settings.gender).id)
    }

    val parsedAge = ageInput.toIntOrNull()?.coerceIn(0, UserProfilePolicy.MAX_VALID_AGE) ?: 0
    val previewTier = UserProfilePolicy.resolveAgeTier(parsedAge, confirmed = parsedAge > 0)
    val previewGender = UserGender.fromId(selectedGenderId)

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

    AquaGlassCard(
        borderColor = when (previewTier) {
            AgeTier.UNDER_13_BLOCKED -> c.error.copy(alpha = 0.65f)
            AgeTier.TEEN_13_17 -> c.amber.copy(alpha = 0.6f)
            AgeTier.ADULT_18_PLUS -> c.success.copy(alpha = 0.5f)
            AgeTier.UNSET -> c.accentCyan.copy(alpha = 0.45f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = c.accentCyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Profile, Age (13+) & Gender", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                }
                AquaProfileTierPill(ageTier = previewTier, gender = previewGender)
            }

            Surface(
                color = when (previewTier) {
                    AgeTier.UNDER_13_BLOCKED -> c.error.copy(alpha = 0.14f)
                    AgeTier.TEEN_13_17 -> c.amber.copy(alpha = 0.14f)
                    AgeTier.ADULT_18_PLUS -> c.success.copy(alpha = 0.12f)
                    AgeTier.UNSET -> c.deepSurface
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = previewTier.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (previewTier) {
                        AgeTier.UNDER_13_BLOCKED -> c.error
                        AgeTier.TEEN_13_17 -> c.amber
                        AgeTier.ADULT_18_PLUS -> c.success
                        AgeTier.UNSET -> c.textMuted
                    },
                    modifier = Modifier.padding(10.dp)
                )
            }

            OutlinedTextField(
                value = ageInput,
                onValueChange = { raw ->
                    ageInput = raw.filter { it.isDigit() }.take(3)
                },
                label = { Text("Age in Years (13+ Required)") },
                placeholder = { Text("Enter age (e.g. 16 or 25)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_age_input")
            )

            Text(
                text = "Gender Preference (Optional — guides respectful assistant pronouns):",
                style = MaterialTheme.typography.labelSmall,
                color = c.textMuted
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UserGender.entries.forEach { g ->
                    val selected = selectedGenderId == g.id
                    FilterChip(
                        selected = selected,
                        onClick = { selectedGenderId = g.id },
                        label = { Text(g.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = c.accentCyan.copy(alpha = 0.22f),
                            selectedLabelColor = c.accentCyan,
                            labelColor = c.textMuted
                        ),
                        modifier = Modifier.testTag("settings_gender_${g.id}")
                    )
                }
            }

            Button(
                onClick = { onSaveProfile(parsedAge, selectedGenderId) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (previewTier == AgeTier.UNDER_13_BLOCKED) c.error else c.accentCyan,
                    contentColor = c.onAccent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("settings_save_profile_button")
            ) {
                Text("Save Profile & Age Tier", fontWeight = FontWeight.Bold)
            }
        }
    }
}
