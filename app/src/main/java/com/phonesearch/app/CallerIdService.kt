package com.phonesearch.app

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * ఇన్‌కమింగ్ కాల్ వచ్చినప్పుడు నంబర్‌ను లోకల్ డేటాబేస్‌లో వెతికి పేరు చూపిస్తుంది.
 * ఇంటర్నెట్ అవసరం లేదు. కాల్‌ను ఆపదు/బ్లాక్ చేయదు — సాధారణంగానే రింగ్ అవుతుంది.
 */
class CallerIdService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        try {
            val incoming = Build.VERSION.SDK_INT < 29 || callDetails.callDirection == Call.Details.DIRECTION_INCOMING
            if (incoming) {
                val number = callDetails.handle?.schemeSpecificPart
                if (!number.isNullOrBlank()) {
                    val name = Db.get(applicationContext).lookup(number) ?: liveContactName(number)
                    if (name != null) Overlay.show(applicationContext, name, number)
                }
            }
        } catch (e: Exception) {
            // ఏ లోపం వచ్చినా కాల్‌కి అడ్డు రాకూడదు
        }
        respondToCall(callDetails, CallScreeningService.CallResponse.Builder().build())
    }

    /** డేటాబేస్ పాతబడి ఉంటే (ఇప్పుడే సేవ్ చేసిన కాంటాక్ట్), ఫోన్ కాంటాక్ట్స్‌లో నేరుగా వెతుకుతుంది. */
    private fun liveContactName(number: String): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val uri = android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
        )
        contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }
}
