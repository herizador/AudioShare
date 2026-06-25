package com.audioshare.app

import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

data class SdpMessage(
    val type: String,
    val sdp: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", this@SdpMessage.type)
        put("sdp", this@SdpMessage.sdp)
    }

    companion object {
        fun fromJson(json: JSONObject): SdpMessage? {
            val type = json.optString("type")
            val sdp = json.optString("sdp")
            if (type.isEmpty() || sdp.isEmpty()) return null
            return SdpMessage(type, sdp)
        }
    }
}

data class IceCandidateMsg(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val sdp: String
) {
    val type: String get() = "ice-candidate"

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "ice-candidate")
        put("sdpMid", this@IceCandidateMsg.sdpMid)
        put("sdpMLineIndex", this@IceCandidateMsg.sdpMLineIndex)
        put("sdp", this@IceCandidateMsg.sdp)
    }

    fun toNativeIceCandidate(): IceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)

    companion object {
        fun fromJson(json: JSONObject): IceCandidateMsg? {
            val sdpMid = json.optString("sdpMid")
            val sdpMLineIndex = json.optInt("sdpMLineIndex", -1)
            val sdp = json.optString("sdp")
            if (sdpMid.isEmpty() || sdpMLineIndex < 0 || sdp.isEmpty()) return null
            return IceCandidateMsg(sdpMid, sdpMLineIndex, sdp)
        }

        fun fromNative(ice: IceCandidate): IceCandidateMsg =
            IceCandidateMsg(ice.sdpMid, ice.sdpMLineIndex, ice.sdp)
    }
}
