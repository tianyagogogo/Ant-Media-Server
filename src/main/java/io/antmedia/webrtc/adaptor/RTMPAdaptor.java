package io.antmedia.webrtc.adaptor;

import static org.bytedeco.javacpp.avutil.AV_PIX_FMT_YUV420P;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceServer;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.audio.WebRtcAudioTrack;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrame.Buffer;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;
import org.webrtc.WrappedNativeI420Buffer;

import io.antmedia.recorder.FFmpegFrameRecorder;
import io.antmedia.recorder.Frame;
import io.antmedia.recorder.FrameRecorder;
import io.antmedia.websocket.WebSocketCommunityHandler;

public class RTMPAdaptor extends Adaptor {


	public static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
	public static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
	public static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
	public static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";

	FFmpegFrameRecorder recorder;
	protected long startTime;

	private static Logger logger = LoggerFactory.getLogger(RTMPAdaptor.class);

	private ExecutorService videoEncoderExecutor; 

	private ExecutorService audioEncoderExecutor;
	private volatile boolean isStopped = false;
	private ScheduledExecutorService signallingExecutor;
	private boolean enableAudio = false;

	private volatile int audioFrameCount = 0;
	private boolean started = false;
	private ScheduledFuture<?> audioDataSchedulerFuture;
	private WebRtcAudioTrack webRtcAudioTrack;

	public static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";

	private static String stunServerUri ="stun:stun.l.google.com:19302";

	public RTMPAdaptor(FFmpegFrameRecorder recorder, WebSocketCommunityHandler webSocketHandler) {
		super(webSocketHandler);
		this.recorder = recorder;

		setSdpMediaConstraints(new MediaConstraints());
		getSdpMediaConstraints().mandatory.add(
				new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
		getSdpMediaConstraints().mandatory.add(
				new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
	}

	public PeerConnectionFactory createPeerConnectionFactory(){
		PeerConnectionFactory.initialize(
				PeerConnectionFactory.InitializationOptions.builder(null)
				.createInitializationOptions());


		SoftwareVideoEncoderFactory encoderFactory = new SoftwareVideoEncoderFactory();
		SoftwareVideoDecoderFactory decoderFactory = new SoftwareVideoDecoderFactory();

		PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
		options.disableNetworkMonitor = true;


		// in receiving stream only Audio Track should be enabled
		// in sending stream only AudioRecord should be enabled 
		JavaAudioDeviceModule adm = (JavaAudioDeviceModule)
				JavaAudioDeviceModule.builder(null)
				.setUseHardwareAcousticEchoCanceler(false)
				.setUseHardwareNoiseSuppressor(false)
				.setAudioRecordErrorCallback(null)
				.setAudioTrackErrorCallback(null)
				.createAudioDeviceModule();

		webRtcAudioTrack = adm.getAudioTrack();
		return  PeerConnectionFactory.builder()
				.setOptions(options)
				.setAudioDeviceModule(adm)
				.setVideoEncoderFactory(encoderFactory)
				.setVideoDecoderFactory(decoderFactory)
				.createPeerConnectionFactory();
	}

	@Override
	public void start() {
		videoEncoderExecutor = Executors.newSingleThreadExecutor();
		audioEncoderExecutor = Executors.newSingleThreadExecutor();
		signallingExecutor = Executors.newSingleThreadScheduledExecutor();

		signallingExecutor.execute(() -> {

			peerConnectionFactory = createPeerConnectionFactory();

			List<IceServer> iceServers = new ArrayList();
			iceServers.add(new IceServer(stunServerUri));

			PeerConnection.RTCConfiguration rtcConfig =
					new PeerConnection.RTCConfiguration(iceServers);

			//TODO: pay attention to this configuration parameters
			rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
			rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
			rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;

			//TODO: which is better gather one or gather continually for our use case
			rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
			// Use ECDSA encryption.
			rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
			// Enable DTLS for normal calls and disable for loopback calls.
			rtcConfig.enableDtlsSrtp = true;
			//TODO: which is better for our use case
			rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

			peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, RTMPAdaptor.this);

			webSocketCommunityHandler.sendStartMessage(getStreamId(), getSession());

			started  = true;


		});

	}

	@Override
	public void stop() {
		if (isStopped) {
			return;
		}
		isStopped  = true;

		if (audioDataSchedulerFuture != null) {
			audioDataSchedulerFuture.cancel(false);
		}

		signallingExecutor.execute(() -> {

			webSocketCommunityHandler.sendPublishFinishedMessage(getStreamId(), getSession());


			audioEncoderExecutor.shutdownNow();
			videoEncoderExecutor.shutdownNow();

			try {
				videoEncoderExecutor.awaitTermination(10, TimeUnit.SECONDS);
			} catch (InterruptedException e1) {
				logger.error(ExceptionUtils.getStackTrace(e1));
				Thread.currentThread().interrupt();
			}
			try {
				if (peerConnection != null) {
					peerConnection.close();
					recorder.stop();
					peerConnection.dispose();
					peerConnectionFactory.dispose();
					peerConnection = null;
				}
			} catch (FrameRecorder.Exception e) {
				logger.error(ExceptionUtils.getStackTrace(e));
			}

		});
		signallingExecutor.shutdown();
	}

	public ExecutorService getSignallingExecutor() {
		return signallingExecutor;
	}



	@Override
	public void onAddStream(MediaStream stream) {
		log.warn("onAddStream for stream: {}", getStreamId());

		if (!stream.audioTracks.isEmpty()) {
			enableAudio = true;
			audioDataSchedulerFuture = signallingExecutor.scheduleAtFixedRate(() -> {

					if (startTime == 0) {
						startTime = System.currentTimeMillis();
					}

					if (audioEncoderExecutor == null || audioEncoderExecutor.isShutdown()) {
						return;
					}

					audioFrameCount++;
					ByteBuffer playoutData = webRtcAudioTrack.getPlayoutData();

					audioEncoderExecutor.execute(() -> {

						ShortBuffer audioBuffer = playoutData.asShortBuffer();
						try {
							boolean result = recorder.recordSamples(webRtcAudioTrack.getSampleRate(), webRtcAudioTrack.getChannels(), audioBuffer);
							if (!result) {
								logger.info("could not audio sample for stream Id {}", getStreamId());
							}
						} catch (FrameRecorder.Exception e) {
							logger.error(ExceptionUtils.getStackTrace(e));
						}
					});
				
			}, 0, 10, TimeUnit.MILLISECONDS);
		}



		if (!stream.videoTracks.isEmpty()) {

			VideoTrack videoTrack = stream.videoTracks.get(0);
			if (videoTrack != null) {

				videoTrack.addSink(new VideoSink() {

					private int frameCount;
					private int dropFrameCount = 0;
					private long pts;
					private int frameNumber;
					private int videoFrameLogCounter = 0;
					private int lastFrameNumber = -1;

					@Override
					public void onFrame(VideoFrame frame) {
						if (startTime == 0) {
							startTime = System.currentTimeMillis();
						}

						if (videoEncoderExecutor == null || videoEncoderExecutor.isShutdown()) {
							return;
						}

						frame.retain();
						frameCount++;
						videoFrameLogCounter++;

						if (videoFrameLogCounter % 100 == 0) {
							logger.info("Received total video frames: {}  received fps: {}" , 
									frameCount, frameCount/((System.currentTimeMillis() - startTime)/1000));
							videoFrameLogCounter = 0;

						}

						videoEncoderExecutor.execute(() -> {
							if (enableAudio) {
								//each audio frame is 10 ms 
								pts = (long)audioFrameCount * 10;
								logger.trace("audio frame count: {}", audioFrameCount);
							}
							else {
								pts = (System.currentTimeMillis() - startTime);
							}

							frameNumber = (int)(pts * recorder.getFrameRate() / 1000f);

							if (frameNumber > lastFrameNumber) {

								recorder.setFrameNumber(frameNumber);
								lastFrameNumber = frameNumber;

								Frame frameCV = new Frame(frame.getRotatedWidth(), frame.getRotatedHeight(), Frame.DEPTH_UBYTE, 2);

								Buffer buffer = frame.getBuffer();
								if (buffer instanceof WrappedNativeI420Buffer) {
									WrappedNativeI420Buffer wrappedBuffer = (WrappedNativeI420Buffer) buffer;
									((ByteBuffer)(frameCV.image[0].position(0))).put(wrappedBuffer.getDataY());
									((ByteBuffer)(frameCV.image[0])).put(wrappedBuffer.getDataU());
									((ByteBuffer)(frameCV.image[0])).put(wrappedBuffer.getDataV());
								}

								try {
									recorder.recordImage(frameCV.imageWidth, frameCV.imageHeight, frameCV.imageDepth,
											frameCV.imageChannels, frameCV.imageStride, AV_PIX_FMT_YUV420P, frameCV.image);

								} catch (FrameRecorder.Exception e) {
									logger.error(ExceptionUtils.getStackTrace(e));
								}
							}
							else {
								dropFrameCount ++;
								logger.info("dropping video, total drop count: {} frame number: {} recorder frame number: {}", 
										dropFrameCount, frameNumber, lastFrameNumber);
							}
							frame.release();
						});

					}
				});
			}
		}

		webSocketCommunityHandler.sendPublishStartedMessage(getStreamId(), getSession());

	}

	@Override
	public void onSetSuccess() {
		peerConnection.createAnswer(this, getSdpMediaConstraints());
	}

	public void setRemoteDescription(final SessionDescription sdp) {
		signallingExecutor.execute(new Runnable() {

			@Override
			public void run() {
				peerConnection.setRemoteDescription(RTMPAdaptor.this, sdp);

			}
		});

	}

	public void addIceCandidate(final IceCandidate iceCandidate) {
		signallingExecutor.execute(new Runnable() {

			@Override
			public void run() {
				if (!peerConnection.addIceCandidate(iceCandidate))
				{
					log.error("Add ice candidate failed");
				}

			}
		});
	}

	public boolean isStarted() {
		return started;
	}



}
