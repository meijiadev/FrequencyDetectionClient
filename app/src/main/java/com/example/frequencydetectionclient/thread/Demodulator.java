package com.example.frequencydetectionclient.thread;

import com.example.frequencydetectionclient.ComplexFirFilter;
import com.example.frequencydetectionclient.bean.SamplePacket;
import com.example.frequencydetectionclient.utils.FirFilter;
import com.orhanobut.logger.Logger;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * Module:      Demodulator.java
 * Description: 这个类实现了各种模拟无线电模式(FM, AM, SSB)的解调。它作为一个单独的线程运行。它将从队列中读取原始的复杂样本，处理它们(通道选择，滤波，解调)并将其转发到AudioSink线程。
 */
public class Demodulator extends Thread {
	private boolean stopRequested = true;

	private static final int AUDIO_RATE = 31250;	// Even though this is not a proper audio rate, the Android system can
													// handle it properly and it is a integer fraction of the input rate (1MHz).
	// The quadrature rate is the sample rate that is used for the demodulation:
	private static final int[] QUADRATURE_RATE = {	1,				// off; this value is not 0 to avoid divide by zero errors!
													2*AUDIO_RATE,	// AM
													2*AUDIO_RATE,	// nFM
													8*AUDIO_RATE,	// wFM
													2*AUDIO_RATE,	// LSB
													2*AUDIO_RATE};	// USB
	public static final int INPUT_RATE = 2000000;	// 传入样本的预期速率

	// DECIMATION
	private Decimator decimator;	// will do INPUT_RATE --> QUADRATURE_RATE

	// FILTERING (This is the channel filter controlled by the user)
	private static final int USER_FILTER_ATTENUATION = 20;
	private FirFilter userFilter = null;
	private int userFilterCutOff = 0;
	private SamplePacket quadratureSamples;
	public static final int[] MIN_USER_FILTER_WIDTH = {0,		// off
														3000,	// AM
														3000,	// nFM
														50000,	// wFM
														1500,	// LSB
														1500};	// USB
	public static final int[] MAX_USER_FILTER_WIDTH = {0,		// off
														15000,	// AM
														15000,	// nFM
														120000,	// wFM
														5000,	// LSB
														5000};  // USB

	// DEMODULATION
	private SamplePacket demodulatorHistory;	// used for FM demodulation
	private float lastMax = 0;	// used for gain control in AM / SSB demodulation
	private ComplexFirFilter bandPassFilter = null;	// used for SSB demodulation
	private static final int BAND_PASS_ATTENUATION = 40;
	public static final int DEMODULATION_OFF 	= 0;
	public static final int DEMODULATION_AM 	= 1;
	public static final int DEMODULATION_NFM 	= 2;
	public static final int DEMODULATION_WFM 	= 3;
	public static final int DEMODULATION_LSB 	= 4;
	public static final int DEMODULATION_USB 	= 5;
	public int demodulationMode;

	// AUDIO OUTPUT
	private AudioSink audioSink = null;		// Will do QUADRATURE_RATE --> AUDIO_RATE and audio output

	/**
	 * Constructor. Creates a new demodulator block reading its samples from the given input queue and
	 * returning the buffers to the given output queue. Expects input samples to be at baseband (mixing
	 * is done by the scheduler)
	 *
	 * @param inputQueue	Queue that delivers received baseband signals
	 * @param outputQueue	Queue to return used buffers from the inputQueue
	 * @param packetSize	Size of the packets in the input queue
	 */
	public Demodulator (ArrayBlockingQueue<SamplePacket> inputQueue, ArrayBlockingQueue<SamplePacket> outputQueue, int packetSize) {
		// Create internal sample buffers:
		// Note that we create the buffers for the case that there is no downsampling necessary
		// All other cases with input decimation > 1 are also possible because they only need
		// smaller buffers.
		this.quadratureSamples = new SamplePacket(packetSize);

		// Create Audio Sink
		this.audioSink = new AudioSink(packetSize, AUDIO_RATE);

		// Create Decimator block
		// Note that the decimator directly reads from the inputQueue and also returns processed packets to the
		// output queue.
		this.decimator = new Decimator(QUADRATURE_RATE[demodulationMode], packetSize, inputQueue, outputQueue);
	}

	/**
	 * @return	Demodulation Mode (DEMODULATION_OFF, *_AM, *_NFM, *_WFM, ...)
	 */
	public int getDemodulationMode() {
		return demodulationMode;
	}

	/**
	 * Sets a new demodulation mode. This can be done while the demodulator is running!
	 * Will automatically adjust internal sample rate conversions and the user filter
	 * if necessary
	 *
	 * @param demodulationMode	Demodulation Mode (DEMODULATION_OFF, *_AM, *_NFM, *_WFM, ...)
	 */
	public void setDemodulationMode(int demodulationMode) {
		if(demodulationMode > 5 || demodulationMode < 0) {
			Logger.e("setDemodulationMode: invalid mode: " + demodulationMode);
			return;
		}
		this.decimator.setOutputSampleRate(QUADRATURE_RATE[demodulationMode]);
		this.demodulationMode = demodulationMode;
		this.userFilterCutOff = (MAX_USER_FILTER_WIDTH[demodulationMode] + MIN_USER_FILTER_WIDTH[demodulationMode])/2;
	}

	/**
	 * 将设置用户滤波器的截止频率
	 * @param channelWidth	通道宽度（单侧），Hz
	 * @return true 如果通道宽度有效，则为false如果超出范围
	 */
	public boolean setChannelWidth(int channelWidth) {
		if(channelWidth < MIN_USER_FILTER_WIDTH[demodulationMode] || channelWidth > MAX_USER_FILTER_WIDTH[demodulationMode])
			return false;
		this.userFilterCutOff = channelWidth;
		return true;
	}

	/**
	 * @return Current width (cut-off frequency - one sided) of the user filter
	 */
	public int getChannelWidth() {
		return userFilterCutOff;
	}

	/**
	 * Starts the thread. This thread will start 2 more threads for decimation and audio output.
	 * These threads are managed by the Demodulator and terminated, when the Demodulator thread
	 * terminates.
	 */
	@Override
	public synchronized void start() {
		stopRequested = false;
		super.start();
	}

	/**
	 * Stops the thread
	 */
	public void stopDemodulator () {
		stopRequested = true;
	}

	@Override
	public void run() {
		SamplePacket inputSamples = null;
		SamplePacket audioBuffer = null;

		Logger.i("Demodulator started. (Thread: " + this.getName() + ")"+stopRequested);

		// 启动音频接收线程:
		audioSink.start();

		// 启动十进制线程:
		decimator.start();

		while (!stopRequested) {

			// Get downsampled packet from the decimator:
			inputSamples = decimator.getDecimatedPacket(1000);

			// Verify the input sample packet is not null:
			if (inputSamples == null) {
				//Logger.d("run: Decimated sample is null. skip this round...");
				continue;
			}

			// 滤波[采样率为QUADRATURE rate]
			applyUserFilter(inputSamples, quadratureSamples);		// The result from filtering is stored in quadratureSamples

			// 将输入样本返回到 十进制块:
			decimator.returnDecimatedPacket(inputSamples);

			// get buffer from audio sink
			audioBuffer = audioSink.getPacketBuffer(1000);

			if(audioBuffer == null) {
				Logger.d("run: Audio buffer is null. skip this round...");
				continue;
			}

			// demodulate		[sample rate is QUADRATURE_RATE]
			switch (demodulationMode) {
				case DEMODULATION_OFF:
					break;

				case DEMODULATION_AM:
					demodulateAM(quadratureSamples, audioBuffer);
					break;

				case DEMODULATION_NFM:
					demodulateFM(quadratureSamples, audioBuffer, 5000);
					break;

				case DEMODULATION_WFM:
					demodulateFM(quadratureSamples, audioBuffer, 75000);
					break;

				case DEMODULATION_LSB:
					demodulateSSB(quadratureSamples, audioBuffer, false);
					break;

				case DEMODULATION_USB:
					demodulateSSB(quadratureSamples, audioBuffer, true);
					break;

				default:
					Logger.e("run: invalid demodulationMode: " + demodulationMode);
			}

			//播放音频	[采样率为QUADRATURE_rate]
			audioSink.enqueuePacket(audioBuffer);
		}

		// 停止音频接收器线程：
		audioSink.stopSink();

		// 停止抽取线程：
		decimator.stopDecimator();

		this.stopRequested = true;
		Logger.i("Demodulator stopped. (Thread: " + this.getName() + ")");
	}

	/**
	 * Will filter the samples in input according to the user filter settings.
	 * Filtered samples are stored in output. Note: All samples in output
	 * will always be overwritten!
	 *
	 * @param input		incoming (unfiltered) samples
	 * @param output	outgoing (filtered) samples
	 */
	private void applyUserFilter(SamplePacket input, SamplePacket output) {
		// Verify that the filter is still correct configured:
		if(userFilter == null || ((int) userFilter.getCutOffFrequency()) != userFilterCutOff) {
			// We have to (re-)create the user filter:
			this.userFilter = FirFilter.createLowPass(	1,
														1,
														input.getSampleRate(),
														userFilterCutOff,
														input.getSampleRate()*0.10f,
														USER_FILTER_ATTENUATION);
			if(userFilter == null)
				return;	// This may happen if input samples changed rate or demodulation was turned off. Just skip the filtering.
			Logger.d("applyUserFilter: created new user filter with " + userFilter.getNumberOfTaps()
					+ " taps. Decimation=" + userFilter.getDecimation() + " Cut-Off="+userFilter.getCutOffFrequency()
					+ " transition="+userFilter.getTransitionWidth());
		}
		output.setSize(0);	// mark buffer as empty
		if(userFilter.filter(input, output, 0, input.size()) < input.size()) {
			Logger.e("applyUserFilter: could not filter all samples from input packet.");
		}
	}

	/**
	 * 将FM解调输入中的样本。宽带调频使用约75000偏差，窄带调频使用约3000偏差。
	 * 解调后的样本存储在输出的真实阵列中。注意：输出中的所有样本将始终被覆盖！
	 *
	 * @param input		incoming (modulated) samples
	 * @param output	outgoing (demodulated) samples
	 */
	private void demodulateFM(SamplePacket input, SamplePacket output, int maxDeviation) {
		float[] reIn = input.re();
		float[] imIn = input.im();
		float[] reOut = output.re();
		float[] imOut = output.im();
		int inputSize = input.size();
		float quadratureGain =  QUADRATURE_RATE[demodulationMode]/(2*(float)Math.PI*maxDeviation);

		if(demodulatorHistory == null) {
			demodulatorHistory = new SamplePacket(1);
			demodulatorHistory.re()[0] = reIn[0];
			demodulatorHistory.im()[0] = reOut[0];
		}

		// Quadrature demodulation:
		reOut[0] = reIn[0]*demodulatorHistory.re(0) + imIn[0] * demodulatorHistory.im(0);
		imOut[0] = imIn[0]*demodulatorHistory.re(0) - reIn[0] * demodulatorHistory.im(0);
		reOut[0] = quadratureGain * (float) Math.atan2(imOut[0], reOut[0]);
		for (int i = 1; i < inputSize; i++) {
			reOut[i] = reIn[i]*reIn[i-1] + imIn[i] * imIn[i-1];
			imOut[i] = imIn[i]*reIn[i-1] - reIn[i] * imIn[i-1];
			reOut[i] = quadratureGain * (float) Math.atan2(imOut[i], reOut[i]);
		}
		demodulatorHistory.re()[0] = reIn[inputSize-1];
		demodulatorHistory.im()[0] = imIn[inputSize-1];
		output.setSize(inputSize);
		output.setSampleRate(QUADRATURE_RATE[demodulationMode]);
	}

	/**
	 * Will AM demodulate the samples in input.
	 * Demodulated samples are stored in the real array of output. Note: All samples in output
	 * will always be overwritten!
	 *
	 * @param input		incoming (modulated) samples
	 * @param output	outgoing (demodulated) samples
	 */
	private void demodulateAM(SamplePacket input, SamplePacket output) {
		float[] reIn = input.re();
		float[] imIn = input.im();
		float[] reOut = output.re();
		float avg = 0;
		lastMax *= 0.95;	// simplest AGC

		// Complex to magnitude
		for (int i = 0; i < input.size(); i++) {
			reOut[i] = (reIn[i] * reIn[i] + imIn[i] * imIn[i]);
			avg += reOut[i];
			if(reOut[i] > lastMax)
				lastMax = reOut[i];
		}
		avg = avg / input.size();

		// normalize values:
		float gain = 0.75f/lastMax;
		for (int i = 0; i < output.size(); i++)
			reOut[i] = (reOut[i] - avg) * gain;

		output.setSize(input.size());
		output.setSampleRate(QUADRATURE_RATE[demodulationMode]);
	}

	/**
	 * Will SSB demodulate the samples in input.
	 * Demodulated samples are stored in the real array of output. Note: All samples in output
	 * will always be overwritten!
	 *
	 * @param input		incoming (modulated) samples
	 * @param output	outgoing (demodulated) samples
	 * @param upperBand	if true: USB; if false: LSB
	 */
	private void demodulateSSB(SamplePacket input, SamplePacket output, boolean upperBand) {
		float[] reOut = output.re();

		// complex band pass:
		if(bandPassFilter == null
				|| (upperBand && (((int) bandPassFilter.getHighCutOffFrequency()) != userFilterCutOff))
				|| (!upperBand && (((int) bandPassFilter.getLowCutOffFrequency()) != -userFilterCutOff))) {
			// We have to (re-)create the band pass filter:
			this.bandPassFilter = ComplexFirFilter.createBandPass(	2,		// Decimate by 2; => AUDIO_RATE
																	1,
																	input.getSampleRate(),
																	upperBand ? 200f : -userFilterCutOff,
																	upperBand ? userFilterCutOff : -200f,
																	input.getSampleRate()*0.01f,
																	BAND_PASS_ATTENUATION);
			if(bandPassFilter == null)
				return;	// This may happen if input samples changed rate or demodulation was turned off. Just skip the filtering.
			Logger.d("demodulateSSB: created new band pass filter with " + bandPassFilter.getNumberOfTaps()
					+ " taps. Decimation=" + bandPassFilter.getDecimation() + " Low-Cut-Off="+bandPassFilter.getLowCutOffFrequency()
					+ " High-Cut-Off="+bandPassFilter.getHighCutOffFrequency() + " transition="+bandPassFilter.getTransitionWidth());
		}
		output.setSize(0);	// mark buffer as empty
		if(bandPassFilter.filter(input, output, 0, input.size()) < input.size()) {
			Logger.e("demodulateSSB: could not filter all samples from input packet.");
		}

		// gain control: searching for max:
		lastMax *= 0.95;	// simplest AGC
		for (int i = 0; i < output.size(); i++) {
			if(reOut[i] > lastMax)
				lastMax = reOut[i];
		}
		// normalize values:
		float gain = 0.75f/lastMax;
		for (int i = 0; i < output.size(); i++)
			reOut[i] *= gain;
	}
}
