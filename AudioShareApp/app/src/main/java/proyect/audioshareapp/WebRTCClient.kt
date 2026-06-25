package com.audioshare.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private val isOfferer: Boolean = false,
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionState: (PeerConnection.PeerConnectionState) -> Unit,
    private val onRemoteStream: (MediaStream) -> Unit,
    private val onDataChannelMessage: (ByteArray) -> Unit
) {
    private val TAG = "WebRTCClient"
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null

    companion object {
        private var initialized = false

        fun initialize(context: Context) {
            if (initialized) return
            val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            initialized = true
        }
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        if (factory == null) {
            factory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                })
                .createPeerConnectionFactory()
        }

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }

        peerConnection = factory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

            override fun onAddStream(stream: MediaStream) {}

            override fun onAddTrack(track: RtpReceiver, streams: Array<MediaStream>) {
                if (track.track()?.kind() == MediaStreamTrack.AUDIO_TRACK_KIND) {
                    Log.d(TAG, "Audio track recibido")
                    streams.forEach { onRemoteStream(it) }
                }
            }

            override fun onRemoveStream(stream: MediaStream) {}

            override fun onDataChannel(channel: DataChannel) {
                Log.d(TAG, "DataChannel recibido")
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (!buffer.binary) return
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        onDataChannelMessage(bytes)
                    }
                    override fun onStateChange() {}
                })
            }

            override fun onRenegotiationNeeded() {}

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Connection state: $state")
                onConnectionState(state)
            }

            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        })
    }

    fun createDataChannel(label: String, ordered: Boolean = false): DataChannel? {
        val init = DataChannel.Init().apply {
            this.ordered = ordered
            negotiated = false
        }
        dataChannel = peerConnection?.createDataChannel(label, init)
        return dataChannel
    }

    fun sendViaDataChannel(data: ByteArray) {
        val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), true)
        dataChannel?.send(buffer)
    }

    fun startAudioCapture() {
        if (!isOfferer) {
            Log.w(TAG, "startAudioCapture ignorado: rol no es offerer")
            return
        }
        val constraints = MediaConstraints()
        audioSource = factory?.createAudioSource(constraints)
        audioTrack = factory?.createAudioTrack("audio_local", audioSource)
        peerConnection?.addTrack(audioTrack, listOf("audio_stream_local"))
    }

    fun createOffer(callback: (SessionDescription) -> Unit) {
        if (!isOfferer) {
            Log.w(TAG, "createOffer ignorado: rol no es offerer")
            return
        }
        peerConnection?.createOffer(
            SdpObserverAdapter { sdp ->
                Handler(Looper.getMainLooper()).post {
                    peerConnection?.setLocalDescription(SdpObserverAdapter(null), sdp)
                    callback(sdp)
                }
            },
            MediaConstraints()
        )
    }

    fun createAnswer(callback: (SessionDescription) -> Unit) {
        peerConnection?.createAnswer(
            SdpObserverAdapter { sdp ->
                Handler(Looper.getMainLooper()).post {
                    peerConnection?.setLocalDescription(SdpObserverAdapter(null), sdp)
                    callback(sdp)
                }
            },
            MediaConstraints()
        )
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        Handler(Looper.getMainLooper()).post {
            peerConnection?.setRemoteDescription(SdpObserverAdapter(null), sdp)
        }
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun getLocalAudioTrack(): AudioTrack? = audioTrack

    fun dispose() {
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection = null
        audioSource?.dispose()
        audioSource = null
        factory?.dispose()
        factory = null
    }
}

class SdpObserverAdapter(
    private val onCreateSuccess: ((SessionDescription) -> Unit)?
) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {
        onCreateSuccess?.invoke(sdp)
    }
    override fun onCreateFailure(error: String) {
        Log.e("SdpObserver", "onCreateFailure: $error")
    }
    override fun onSetSuccess() {}
    override fun onSetFailure(error: String) {
        Log.e("SdpObserver", "onSetFailure: $error")
    }
}
