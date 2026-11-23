package com.music.spotui.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.myspoti.engine.R
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.LoginLoad
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.host.android.WebPlayerHost
import com.music.spotui.ui.screens.shareDiagnosticReport
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppSans
import com.music.spotui.ui.theme.AppText
import kotlinx.coroutines.delay

/** Copies a sign-in URL to the system clipboard so the user can paste it in any browser. */
fun copyLoginLink(context: Context, url: String) {
    val clean = url.trim()
    if (!clean.startsWith("https://") && !clean.startsWith("http://")) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Sign-in link", clean))
    Toast.makeText(context, context.getString(R.string.login_link_copied), Toast.LENGTH_SHORT).show()
}

/**
 * Opens [url] in a Chrome Custom Tab / external browser so OAuth flows (like Google Sign-In) run in
 * a browser context Google trusts rather than an embedded WebView.
 */
fun openInSecureBrowser(context: Context, url: String): Boolean {
    val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return false
    val customTabIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtras(Bundle().apply {
            putBinder("android.support.customtabs.extra.SESSION", null)
            putInt("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF121212.toInt())
            putInt("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
        })
    }
    val launched = runCatching { context.startActivity(customTabIntent); true }.getOrElse {
        val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(fallback); true }.getOrDefault(false)
    }
    if (!launched) {
        Toast.makeText(context, context.getString(R.string.login_browser_failed), Toast.LENGTH_SHORT).show()
    }
    return launched
}

/**
 * A failed sign-in had no way to a diagnostic report: the export lives in the settings, which
 * only a signed-in app reaches (the reported "Oops! Something went wrong" and e-mail loops came
 * with no log at all). What the page shows is noted first, then the usual share sheet.
 */
fun sendLoginReport(context: android.content.Context) {
    MyspotiRuntime.host.noteLoginPage {
        val file = runCatching { Logbook.export(context) }.getOrNull()
        if (file != null) shareDiagnosticReport(context, file)
        else android.widget.Toast.makeText(context, context.getString(R.string.diagnostic_export_failed), android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * Sign-in controls floating above Spotify's WebView without taking focus from the form or keyboard.
 */
@Composable
fun LoginHelpButton() {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val currentUrl by MyspotiRuntime.host.url.collectAsState()
    val activeUrl = remember(currentUrl) { MyspotiRuntime.host.currentLoginUrl() }
    var open by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var dismissedGoogleUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_800)
        copied = false
    }

    val top = WindowInsets.statusBars.getTop(density)
    val bottomInset = maxOf(WindowInsets.navigationBars.getBottom(density), WindowInsets.ime.getBottom(density))
    val gap = with(density) { 12.dp.roundToPx() }
    val pillBg = Color(0xD9242424)

    Popup(alignment = Alignment.TopEnd, offset = IntOffset(-gap, top + gap)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(pillBg)
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(50))
                    .clickable {
                        copyLoginLink(ctx, activeUrl)
                        copied = true
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = if (copied) AppAccent else AppText,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    stringResource(if (copied) R.string.login_copied else R.string.login_copy_link),
                    color = if (copied) AppAccent else AppText,
                    fontFamily = AppSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(pillBg)
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(50))
                    .clickable { openInSecureBrowser(ctx, activeUrl) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenInBrowser,
                    contentDescription = null,
                    tint = AppText,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    stringResource(R.string.login_browser_pill),
                    color = AppText,
                    fontFamily = AppSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
            Text(
                stringResource(R.string.login_help_button),
                color = AppText, fontFamily = AppSans, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(pillBg)
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(50))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }

    if (LoginLoad.isGoogleAuthUrl(currentUrl) && dismissedGoogleUrl != currentUrl) {
        Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -(bottomInset + gap))) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF218181C))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
                    .padding(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.login_google_title),
                        color = AppText,
                        fontFamily = AppSans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { dismissedGoogleUrl = currentUrl },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.settings_sign_in_close),
                            tint = AppText.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    stringResource(R.string.login_google_detail),
                    color = AppText.copy(alpha = 0.76f),
                    fontFamily = AppSans,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(AppAccent)
                            .clickable { openInSecureBrowser(ctx, activeUrl) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInBrowser,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            stringResource(R.string.login_open_browser),
                            color = Color.Black,
                            fontFamily = AppSans,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF28282E))
                            .clickable {
                                copyLoginLink(ctx, activeUrl)
                                copied = true
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = AppText,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            stringResource(if (copied) R.string.login_copied else R.string.login_copy_link),
                            color = AppText,
                            fontFamily = AppSans,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF28282E))
                            .clickable {
                                dismissedGoogleUrl = currentUrl
                                MyspotiRuntime.host.retryLogin()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.login_use_email_code),
                            color = AppText,
                            fontFamily = AppSans,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    if (open) LoginHelpDialog(onClose = { open = false })
}

@Composable
fun LoginHelpDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activeUrl = MyspotiRuntime.host.currentLoginUrl()
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color(0xFF242424),
        title = { Text(stringResource(R.string.login_help_title), color = AppText) },
        text = {
            Column {
                Text(stringResource(R.string.login_help_detail), color = AppText.copy(alpha = 0.7f))
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { copyLoginLink(ctx, activeUrl) }) {
                        Text(stringResource(R.string.login_copy_link), color = AppAccent)
                    }
                    TextButton(onClick = { onClose(); openInSecureBrowser(ctx, activeUrl) }) {
                        Text(stringResource(R.string.login_open_browser), color = AppText)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onClose(); sendLoginReport(ctx) }) {
                Text(stringResource(R.string.login_help_send), color = AppAccent)
            }
        },
        dismissButton = {
            // A half-finished flow is what Spotify answers "Oops! Something went wrong" to: from nothing.
            TextButton(onClick = { onClose(); MyspotiRuntime.host.retryLogin() }) {
                Text(stringResource(R.string.login_help_restart), color = AppText)
            }
        },
    )
}

/** Under a wait that lasts (the loading screen after the sign-in), the same way to a report. */
@Composable
fun LoginReportLink(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Text(
        stringResource(R.string.login_help_slow),
        color = AppText.copy(alpha = 0.6f), fontFamily = AppSans, fontSize = 14.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).clickable { sendLoginReport(ctx) }.padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
