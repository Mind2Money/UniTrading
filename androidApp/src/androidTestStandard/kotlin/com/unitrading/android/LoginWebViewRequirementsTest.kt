package com.unitrading.android

import androidx.webkit.WebViewFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginWebViewRequirementsTest {
    @Test fun aWebViewWithoutProfilesCanStillLogIn() {
        assertEquals(emptyList<String>(), missingLoginFeatures { it != WebViewFeature.MULTI_PROFILE })
    }

    @Test fun theMessageNamesWhatIsMissingAndTheWebView() {
        assertEquals(listOf("页面启动脚本", "受限消息通道"), missingLoginFeatures { false })
        val missing = missingLoginFeatures { it != WebViewFeature.WEB_MESSAGE_LISTENER }
        assertEquals(listOf("受限消息通道"), missing)
        val message = webViewTooOldMessage(missing, "com.huawei.webview 14.0.0.331")
        assertTrue(message, "受限消息通道" in message && "com.huawei.webview 14.0.0.331" in message && "-webview.apk" in message)
    }
}
