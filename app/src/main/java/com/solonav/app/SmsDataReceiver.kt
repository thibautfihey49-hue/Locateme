package com.solonav.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log

class SmsDataReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_POSITION_UPDATE = "com.solonav.POSITION_UPDATE"
        private const val TAG = "SmsDataReceiver"
        private const val PORT = 16968
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != "android.intent.action.DATA_SMS_RECEIVED") return

        try {
            val bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format")

            for (pdu in pdus) {
                val sms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }

                val msg = sms.messageBody  // ✅ Déjà une String, pas besoin de conversion
                val sender = sms.displayOriginatingAddress ?: ""

                Log.d(TAG, "SMS reçu de $sender : $msg")

                val broadcast = Intent(ACTION_POSITION_UPDATE)
                broadcast.setPackage(context?.packageName)
                broadcast.putExtra("sms_data", msg)
                broadcast.putExtra("sender", sender)  // ✅ Plus jamais null
                context?.sendBroadcast(broadcast)

                if (msg == "SOLONAV_DEMANDE_POS") {
                    val serviceIntent = Intent(context, LocationSharingService::class.java)
                    serviceIntent.putExtra("target_number", sender.replace("+33", "0"))
                    context?.startForegroundService(serviceIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur réception SMS", e)
        }
    }
}
